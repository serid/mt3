package jitrs.mt3

import jitrs.mt3.llvm.Codegen
import jitrs.mt3.llvm.FunctionContext
import jitrs.util.countFrom
import java.nio.file.Files
import java.nio.file.Path

// Assumes clang version 14.0.6

/**
 * Convert an AST of an MT3 program to LLVM IR. This compiler is not incremental and puts all MT3 modules in one
 * LLVM module, then links it with mt3lib.ll.
 */
class Lowering(private val moduleName: String) {
    private val codegen = Codegen()

    private val globalVars = hashMapOf(
        "print" to "mt3_stdlib_print",
        "plus" to "mt3_stdlib_plus"
    )

    /**
     * When traversing a module, index of the current free native global variable where a string literal can be allocated.
     */
    private var moduleNativeGlobalsIndex = 1

    /**
     * When traversing a function, this is the index of the current free SSA-variable.
     */
    private var localVariableIndex = 0

    /**
     * Codegen will reify call sequences with arities from this set.
     * Arity 0 is needed for mt3lib.cxx.
     */
    private val callSequencesToGenerate = hashSetOf(0)

    private val moduleInitializer = ArrayList<(functionContext: FunctionContext) -> Unit>()

    fun toLlvm(program: Program): String {
        visitProgram(program)
        callSequencesToGenerate.forEach { i -> reifyCallSequence(codegen.headerCode, i) }
        return codegen.get()
    }

    private fun visitProgram(program: Program) {
        codegen.appendHeader("""
            |; <codegen_include.ll>
            |${Files.readString(Path.of("./src/main/resources/codegen_include.ll"))}
            |; </ codegen_include.ll>
            |
        """.trimMargin())

        program.toplevels.forEach {
            visitToplevel(it)
        }

        // Emit module initializer
        codegen.appendBody("define void @mt3_mainmod_init() {\n")
        val initializer = FunctionContext(codegen.bodyCode)
        moduleInitializer.forEach {
            it(initializer)
        }
        codegen.appendBody("    ret void\n")
        codegen.appendBody("}\n")
    }

    private fun visitToplevel(toplevel: Toplevel) {
        when (toplevel) {
            is Toplevel.Fun -> {
                localVariableIndex = toplevel.params.size + 1

                // mt3lib.cxx expects the main function to be called mt3_main.
                // Also prefix a user-provided symbol with another "mt3_" if it already starts with one.
                // This ensures that user-provided symbol names never start with a single "mt3_" this way
                // the compiler can use "mt3_" namespace all to herself.
                // TODO: maybe it's easier to just disallow symbols starting with "mt3_"?
                //var funName =
                //    if (toplevel.name == "main" || toplevel.name.startsWith("mt3_")) "mt3_${toplevel.name}" else toplevel.name

                val shortName = mangle(toplevel.name)
                val longName = "mt3_${moduleName}_$shortName"
                val codeName = "${longName}-fun"

                codegen.appendBody("define $MT3ValueErased @$codeName() {\n")

                toplevel.body.forEach {
                    visitStmt(it)
                }

                codegen.appendBody("    ret $MT3ValueErased null\n")
                codegen.appendBody("}\n\n")

                val valueId = if (toplevel.name == "main") "mt3_main" else longName
                val modifier = if (toplevel.name == "main") "local_unnamed_addr" else "private unnamed_addr"

                // Create a native global holding MT3Value* pointer to a function value
                codegen.appendHeader("@$valueId = $modifier global %MT3Value* null, align 8\n")

                moduleInitializer.add { func ->
                    val casted = func.allocateSsaVariable()
                    val res = func.allocateSsaVariable()
                    func.body.append("    ; Allocate function\n")
                    func.body.append("    %$casted = bitcast %MT3Value* ()* @$codeName to i8*\n")
                    func.body.append("    %$res = tail call %MT3Value* @mt3_new_function(i8 ${toplevel.arity()}, i8* %$casted)\n")
                    emitInitializeGlobalVar(func, res, valueId)
                }

                if (globalVars.containsKey(shortName))
                    println("warning: global var $shortName already exists")

                globalVars[shortName] = longName
            }
        }
    }

    private fun visitStmt(stmt: Stmt) {
        when (stmt) {
            is Stmt.ExprStmt -> {
                visitExpr(stmt.e)
            }
        }
    }

    /**
     * @return index where result of this expression is stored. {null} if the expression does not produce an SSA value
     */
    private fun visitExpr(expr: Expr): VisitExprResult {
        when (expr) {
            is Expr.IntConst -> {
                val valueId = "mt3_intV${allocateNativeGlobalsIndex()}"

                // Create a native global holding MT3Value* pointer to a string value
                codegen.appendHeader("@$valueId = private unnamed_addr global %MT3Value* null, align 8\n")

                moduleInitializer.add { func ->
                    val res = func.allocateSsaVariable()
                    func.body.append("    ; Allocate int\n")
                    func.body.append("    %$res = tail call %MT3Value* @mt3_new_int(i64 ${expr.int})\n")
                    emitInitializeGlobalVar(func, res, valueId)
                }

                return emitLoadGlobalVar(valueId)
            }

            is Expr.StringConst -> {
                val lenWithNull = (expr.string.length + 1).toString()
                val id = allocateNativeGlobalsIndex()
                val bytesId = "mt3_str$id"
                val valueId = "mt3_strV$id"

                // Create a native global holding bytes of this string literal
                codegen.appendHeader("@$bytesId = private unnamed_addr constant [$lenWithNull x i8] c\"${expr.string}\\00\", align 1\n")

                // Create a native global holding MT3Value* pointer to a string value
                codegen.appendHeader("@$valueId = private unnamed_addr global %MT3Value* null, align 8\n")

                moduleInitializer.add { func ->
                    val res = func.allocateSsaVariable()
                    func.body.append("    ; Allocate string\n")
                    func.body.append("    %$res = tail call %MT3Value* @mt3_new_string(i8* getelementptr inbounds ([$lenWithNull x i8], [$lenWithNull x i8]* @$bytesId, i64 0, i64 0))\n")
                    emitInitializeGlobalVar(func, res, valueId)
                }

                return emitLoadGlobalVar(valueId)
            }

            is Expr.Call -> {
                callSequencesToGenerate.add(expr.arity())

                val functionIndex = visitExpr(expr.func).toCode()
                val args = (sequenceOf("%MT3Value* $functionIndex") + expr.args.asSequence().map {
                    "%MT3Value* ${visitExpr(it).toCode()}"
                }).joinToString()
                val ix = allocateSsaVariable()

                codegen.appendBody("    %$ix = tail call %MT3Value* @mt3_builtin_call${expr.arity()}($args)\n")
                return VisitExprResult.SsaIndex(ix)
            }

            is Expr.GlobalVarUse -> {
                var name = expr.name
                name = mangle(name)
                name = globalVars[name]!!

                return emitLoadGlobalVar(name)
            }
        }
    }

    private fun emitInitializeGlobalVar(func: FunctionContext, what: Int, globalId: String) {
        func.body.append("""
            |    ; Store allocated value to a native global variable
            |    store %MT3Value* %$what, %MT3Value** @$globalId, align 8
            |
            """.trimMargin())
        emitRegisterGcRoot(func, what)
    }

    private fun emitLoadGlobalVar(name: String): VisitExprResult {
        val ix = allocateSsaVariable()
        codegen.appendBody("    %$ix = load %MT3Value*, %MT3Value** @$name, align 8\n")
        return VisitExprResult.SsaIndex(ix)
    }

    sealed class VisitExprResult {
        data class SsaIndex(val ix: Int) : VisitExprResult()

        /**
         * This variant is returned if the expression cannot be assigned an index and
         * should be used inline in the containing SSA expression.
         */
        data class Immediate(val code: String) : VisitExprResult()
        object None : VisitExprResult()

        fun toCode(): String = when (this) {
            is SsaIndex -> "%$ix"
            is Immediate -> code
            is None -> throw RuntimeException("this expression has no result")
        }
    }

    // TODO: resolve collisions with names containing "plus"
    private fun mangle(name: String): String {
        var r = name.replace("+", "plus")
        return r
    }

    private fun allocateSsaVariable(): Int = localVariableIndex++

    private fun allocateNativeGlobalsIndex(): Int = moduleNativeGlobalsIndex++

    private fun emitRegisterGcRoot(func: FunctionContext, ix: Int) {
        val casted = func.allocateSsaVariable()
        func.body.append(
            """|    %$casted = bitcast %MT3Value* %$ix to %GCObject*
               |    tail call void @mt3_add_gc_root(%GCObject* %$casted)""".trimMargin()
        )
    }
}

// A call sequence is a native function that "calls" a MT3Function object. It checks that
// its argument is, in fact an MT3Function, matches number of arguments with number of parameters
// extracts a function pointer from the MT3Function object, then casts it to a required arity and invokes it.
// MT3 compiler knows number of arguments in a function call ahead of time, as a consequence we can
// generate call sequences during codegen.
//
// Example
private fun reifyCallSequence(output: StringBuilder, arity: Int) {
    val parameterList = (sequenceOf("%MT3Value* noundef readonly %0") +
            countFrom(1).take(arity).map { "%MT3Value* noundef readonly %$it" }).joinToString()
    val funPtrParameterList = countFrom(1).take(arity).map { "%MT3Value*" }.joinToString()
    val funPtrArgumentList = countFrom(1).take(arity).map { "%MT3Value* noundef %$it" }.joinToString()

    output.append("define noundef %MT3Value* @mt3_builtin_call$arity($parameterList) local_unnamed_addr {\n")
    output.append("    %${arity + 2} = tail call i8* @mt3_check_function_call(%MT3Value* noundef %0, i8 noundef zeroext $arity)\n")
    output.append("    %${arity + 3} = bitcast i8* %${arity + 2} to %MT3Value* ($funPtrParameterList)*\n")
    output.append("    %${arity + 4} = tail call noundef %MT3Value* %${arity + 3}($funPtrArgumentList)\n")
    output.append("    ret %MT3Value* %${arity + 4}\n")
    output.append("}\n")
}

// Generated code treats objects as opaque pointers
private const val MT3ValueErased = "%MT3Value*"