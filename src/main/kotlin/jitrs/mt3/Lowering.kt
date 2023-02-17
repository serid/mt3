package jitrs.mt3

import jitrs.mt3.llvm.Codegen
import jitrs.mt3.llvm.ExprFactory
import jitrs.util.countFrom
import java.nio.file.Files
import java.nio.file.Path

// Assumes clang version 14.0.6

/**
 * Convert an AST of an MT3 program to LLVM IR. This compiler is not incremental and puts all MT3 modules in one
 * LLVM module, then links it with mt3lib.ll.
 */
class Lowering(val moduleName: String) {
    val codegen = Codegen()

    /**
     * When traversing a module, index of the current free global variable where a string literal can be allocated.
     */
    private var moduleGlobalsIndex = 1

    /**
     * When traversing a function, this is the index of the current free SSA-variable.
     */
    private var localVariableIndex = 0

    /**
     * Codegen will reify call sequences with arities from this set.
     * Arity 0 is needed for mt3lib.cxx.
     */
    private val callSequencesToGenerate = hashSetOf(0)

    private val moduleInitializer = ArrayList<ExprFactory>()

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
        localVariableIndex = 1
        moduleInitializer.forEach {
            val (r, ix) = it.factory(localVariableIndex)
            codegen.appendBody(r)
            localVariableIndex = ix
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

                val funName = manglePrivate(toplevel.name)

                codegen.appendBody("define $MT3ValueErased @$funName() {\n")

                toplevel.body.forEach {
                    visitStmt(it)
                }

                codegen.appendBody("    ret $MT3ValueErased null\n")
                codegen.appendBody("}\n\n")

                val valueId = if (toplevel.name == "main") "mt3_main" else "mt3_funV${allocateGlobalsIndex()}"
                val modifier = if (toplevel.name == "main") "local_unnamed_addr" else "private unnamed_addr"

                // Create a global holding MT3Value* pointer to a function value
                codegen.appendHeader("@$valueId = $modifier global %MT3Value* null, align 8\n")

                moduleInitializer.add(ExprFactory { v ->
                    var r = "    ; Allocate function\n"
                    r += "    %$v = bitcast %MT3Value* ()* @$funName to i8*\n"
                    r += "    %${v + 1} = tail call %MT3Value* @mt3_new_function(i8 ${toplevel.arity()}, i8* %$v)\n"
                    r += emitInitializeGlobal(v + 1, valueId)
                    Pair(r, v + 2)
                })
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
                val valueId = "mt3_intV${allocateGlobalsIndex()}"

                // Create a global holding MT3Value* pointer to a string value
                codegen.appendHeader("@$valueId = private unnamed_addr global %MT3Value* null, align 8\n")

                moduleInitializer.add(ExprFactory { v ->
                    var r = "    ; Allocate int\n"
                    r += "    %$v = tail call %MT3Value* @mt3_new_int(i64 ${expr.int})\n"
                    r += emitInitializeGlobal(v, valueId)
                    Pair(r, v + 1)
                })

                // At this point an int is just a global value
                return emitLoadGlobal(Expr.GlobalRef(valueId))
            }

            is Expr.StringConst -> {
                val lenWithNull = (expr.string.length + 1).toString()
                val id = allocateGlobalsIndex()
                val bytesId = "mt3_str$id"
                val valueId = "mt3_strV$id"

                // Create a global holding bytes of this string literal
                codegen.appendHeader("@$bytesId = private unnamed_addr constant [$lenWithNull x i8] c\"${expr.string}\\00\", align 1\n")

                // Create a global holding MT3Value* pointer to a string value
                codegen.appendHeader("@$valueId = private unnamed_addr global %MT3Value* null, align 8\n")

                moduleInitializer.add(ExprFactory { v ->
                    var r = "    ; Allocate string\n"
                    r += "    %$v = tail call %MT3Value* @mt3_new_string(i8* getelementptr inbounds ([$lenWithNull x i8], [$lenWithNull x i8]* @$bytesId, i64 0, i64 0))\n"
                    r += emitInitializeGlobal(v, valueId)
                    Pair(r, v + 1)
                })

                // At this point a string is just a global value
                return emitLoadGlobal(Expr.GlobalRef(valueId))
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

            is Expr.GlobalRef -> {
                return emitLoadGlobal(expr)
            }
        }
    }

    private fun emitInitializeGlobal(v: Int, globalId: String): String = """
    |    ; Store allocated value to a global variable
    |    store %MT3Value* %$v, %MT3Value** @$globalId, align 8
    |    ${emitRegisterGcRoot(v)}
    |
    """.trimMargin()

    private fun emitLoadGlobal(expr: Expr.GlobalRef): VisitExprResult {
        var name = expr.name
        name = mangle(name)

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
        if (r in arrayOf("print", "plus")) r = "mt3_stdlib_$r"
        return r
    }

    private fun manglePrivate(name: String): String = "${moduleName}_${mangle(name)}"

    private fun allocateSsaVariable(): Int = localVariableIndex++

    private fun allocateGlobalsIndex(): Int = moduleGlobalsIndex++

    private fun emitRegisterGcRoot(ix: Int): String = "tail call void @mt3_add_gc_root($MT3ValueErased %$ix)"
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
private val MT3ValueErased = "%MT3Value*"