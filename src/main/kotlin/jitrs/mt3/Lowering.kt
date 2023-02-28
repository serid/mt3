package jitrs.mt3

import jitrs.mt3.llvm.BlockContext
import jitrs.mt3.llvm.Codegen
import jitrs.mt3.llvm.FunctionContext
import jitrs.util.countFrom
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

// Assumes clang version 14.0.6

/**
 * Convert an AST of an MT3 program to LLVM IR. This compiler is not incremental and puts all MT3 modules in one
 * LLVM module, then links it with mt3lib.ll.
 */
class Lowering(private val moduleName: String) {
    private val codegen = Codegen()

    private val globalVars = hashMapOf(
        "print" to "mt3_stdlib_print",
        "equality" to "mt3_stdlib_equality",
        "plus" to "mt3_stdlib_plus",
        "minus" to "mt3_stdlib_minus",
        "mul" to "mt3_stdlib_mul",
        "div" to "mt3_stdlib_div",

        "none" to "mt3_none_singleton",
        "false" to "mt3_false_singleton",
        "true" to "mt3_true_singleton",
    )

    /**
     * When traversing a module, index of the current free native global variable where a string literal can be allocated.
     */
    private var moduleNativeGlobalsIndex = 1

    private val localVariables = HashMap<String, Int>()

    /**
     * When compiling a function, we don't know what id the final block will have so we add jumps to it later.
     */
    private val blocksToFinalizeWithReturn = ArrayList<BlockContext>()

    /**
     * Codegen will reify call sequences with arities from this set.
     * Arity 0 is needed for mt3lib.cxx.
     */
    private val callSequencesToGenerate = TreeSet(arrayListOf(0))

    private val moduleInitializer = ArrayList<(block: BlockContext) -> Unit>()

    fun toLlvm(program: Program): String {
        visitProgram(program)
        callSequencesToGenerate.forEach { i -> reifyCallSequence(codegen.headerCode, i) }
        return codegen.get()
    }

    private fun visitProgram(program: Program) {
        codegen.appendHeader(
            """
            |; <codegen_include.ll>
            |${Files.readString(Path.of("./src/main/resources/codegen_include.ll"))}
            |; </ codegen_include.ll>
            |
        """.trimMargin()
        )

        program.toplevels.forEach {
            visitToplevel(it)
        }

        // Emit module initializer
        val initializer = FunctionContext("void", "mt3_mainmod_init", "", 0)
        val bloc = initializer.newBlock()
        moduleInitializer.forEach {
            it(bloc)
        }
        bloc.finalizeWithReturn("void")
        initializer.blit(codegen.bodyCode)
    }

    private fun visitToplevel(toplevel: Toplevel) {
        when (toplevel) {
            is Toplevel.Fun -> {
                val arity = toplevel.params.size

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

                // Add the function name to globals before lowering it to allow recursion
                if (globalVars.put(shortName, longName) != null)
                    throw RuntimeException("warning: global var $shortName already exists")

                val generatedParameters = countFrom(0).take(arity).map { "%MT3Value* %$it" }.joinToString()

                // Skip ssa-s already used for generatedParameters
                val cfc = FunctionContext(MT3ValueErased, codeName, generatedParameters, arity)
                localVariables.clear()
                blocksToFinalizeWithReturn.clear()

                val entryBlock = cfc.newBlock()

                // Add parameters as local variables
                toplevel.params.forEachIndexed { index, name ->
                    val ssa = cfc.allocateSsaVariable()
                    if (localVariables.put(name, ssa) != null)
                        throw RuntimeException("error: parameter $name already exists")
                    emitAllocaLocalVariable(entryBlock, ssa, "%$index")
                }

                val none = emitLoadNone(entryBlock).toCode()

                // Add local variables as local variables
                // Declarations of local variables are hoisted to function header and are not lexical
                val declarations = collectFunctionsVariables(toplevel, HashSet(localVariables.keys)).asSequence()
                declarations.forEach { def ->
                    val ssa = cfc.allocateSsaVariable()
                    if (localVariables.put(def.name, ssa) != null)
                        throw RuntimeException("error: local var ${def.name} already exists")
                    emitAllocaLocalVariable(entryBlock, ssa, none)
                }

                // Allocate variable for return value
                val retVarSsa = cfc.allocateSsaVariable()
                if (localVariables.put("_return_value", retVarSsa) != null)
                    throw RuntimeException("error: local var '_return_value' already exists")
                emitAllocaLocalVariable(entryBlock, retVarSsa, none)

                // idk
//                allocateSsaVariable()

                var currentBlock: BlockContext? = entryBlock

                for (it in toplevel.body) {
                    currentBlock = visitStmt(currentBlock!!, it)
                    if (currentBlock == null) break
                }

                // Create the block with "ret"
                val retBlock = cfc.newBlock()

                // If penultimate block ended in "return", don't direct it to retBlock
                currentBlock?.finalizeWithUnconditional(retBlock)
                blocksToFinalizeWithReturn.forEach {
                    it.finalizeWithUnconditional(retBlock)
                }

                val returnValue = emitLoadLocalVariable(retBlock, localVariables["_return_value"]!!).toCode()
                retBlock.finalizeWithReturn("$MT3ValueErased $returnValue")
                cfc.blit(codegen.bodyCode)


                // Create a native global holding MT3Value* pointer to a function value

                val valueId = if (toplevel.name == "main") "mt3_main" else longName
                val modifier = if (toplevel.name == "main") "local_unnamed_addr" else "private unnamed_addr"

                val funPtrTypeList = countFrom(1).take(arity).map { "%MT3Value*" }.joinToString()

                codegen.appendHeader("@$valueId = $modifier global %MT3Value* null, align 8\n")

                moduleInitializer.add { block ->
                    val casted = block.func.allocateSsaVariable()
                    val res = block.func.allocateSsaVariable()
                    block.body.append("    ; Allocate function\n")
                    block.body.append("    %$casted = bitcast %MT3Value* ($funPtrTypeList)* @$codeName to i8*\n")
                    block.body
                        .append("    %$res = tail call %MT3Value* @mt3_new_function(i8 ${toplevel.arity()}, i8* %$casted)\n")
                    emitInitializeGlobalVar(block, res, valueId)
                }

                if (globalVars.put(shortName, longName) != null)
                    println("warning: global var $shortName already exists")
            }
        }
    }

    @Suppress("UnnecessaryVariable")
    private fun visitStmt(block: BlockContext, stmt: Stmt): BlockContext? {
        when (stmt) {
            is Stmt.VariableDefinition -> {
                // Replace the MT3None with a value
                emitAssignLocal(block, stmt.name, stmt.initializer)
                return block
            }

            is Stmt.Assignment -> {
                emitAssignLocal(block, stmt.name, stmt.e)
                return block
            }

            is Stmt.If -> {
                val ifBlock = block
                val arg = visitExpr(ifBlock, stmt.cond).toCode()
                val condition = block.func.allocateSsaVariable()
                ifBlock.body.append("%$condition = tail call i1 @mt3_is_true(%MT3Value* $arg)\n")

                val onTrueEntry = block.func.newBlock()
                var onTrue: BlockContext? = onTrueEntry

                for (it in stmt.body) {
                    onTrue = visitStmt(onTrue!!, it)
                    if (onTrue == null)
                        break
                }

                val onMerge = block.func.newBlock()

                ifBlock.finalizeWithConditional(
                    "%$condition",
                    onTrueEntry,
                    onMerge
                )

                onTrue?.finalizeWithUnconditional(
                    onMerge
                )

                return onMerge
            }

            is Stmt.While -> {
                val headerBlock = block.func.newBlock()
                block.finalizeWithUnconditional(headerBlock)

                val arg = visitExpr(headerBlock, stmt.cond).toCode()
                val condition = block.func.allocateSsaVariable()
                headerBlock.body.append("%$condition = tail call i1 @mt3_is_true(%MT3Value* $arg)\n")

                val loopEntry = block.func.newBlock()
                var loop: BlockContext? = loopEntry

                for (it in stmt.body) {
                    loop = visitStmt(loop!!, it)
                    if (loop == null)
                        break
                }

                val onMerge = block.func.newBlock()

                headerBlock.finalizeWithConditional(
                    "%$condition",
                    loopEntry,
                    onMerge
                )

                loop?.finalizeWithUnconditional(
                    headerBlock
                )

                return onMerge
            }

            is Stmt.Return -> {
                emitAssignLocal(block, "_return_value", stmt.value)
                blocksToFinalizeWithReturn.add(block)
                return null
            }

            is Stmt.ExprStmt -> {
                visitExpr(block, stmt.e)
                return block
            }
        }
    }

    /**
     * @return LLVM expression where result of this MT3 expression is stored
     */
    private fun visitExpr(block: BlockContext, expr: Expr): VisitExprResult {
        when (expr) {
            is Expr.IntConst -> {
                val valueId = "mt3_intV${allocateNativeGlobalsIndex()}"

                // Create a native global holding MT3Value* pointer to a string value
                codegen.appendHeader("@$valueId = private unnamed_addr global %MT3Value* null, align 8\n")

                moduleInitializer.add { block2 ->
                    val res = block2.func.allocateSsaVariable()
                    block2.body.append("    ; Allocate int\n")
                    block2.body.append("    %$res = tail call %MT3Value* @mt3_new_int(i64 ${expr.int})\n")
                    emitInitializeGlobalVar(block2, res, valueId)
                }

                return emitLoadGlobalVar(block, valueId)
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

                moduleInitializer.add { block2 ->
                    val res = block2.func.allocateSsaVariable()
                    block2.body.append("    ; Allocate string\n")
                    block2.body.append("    %$res = tail call %MT3Value* @mt3_new_string(i8* getelementptr inbounds ([$lenWithNull x i8], [$lenWithNull x i8]* @$bytesId, i64 0, i64 0))\n")
                    emitInitializeGlobalVar(block2, res, valueId)
                }

                return emitLoadGlobalVar(block, valueId)
            }

            is Expr.Call -> {
                callSequencesToGenerate.add(expr.arity())

                val functionIndex = visitExpr(block, expr.func).toCode()
                val args = (sequenceOf("%MT3Value* $functionIndex") + expr.args.asSequence().map {
                    "%MT3Value* ${visitExpr(block, it).toCode()}"
                }).joinToString()
                val ssa = block.func.allocateSsaVariable()

                block.body.append("    %$ssa = tail call %MT3Value* @mt3_builtin_call${expr.arity()}($args)\n")
                return VisitExprResult.SsaIndex(ssa)
            }

            is Expr.VariableUse -> {
                val name = mangle(expr.name)

                localVariables[name].also {
                    if (it != null) {
                        val res = block.func.allocateSsaVariable()
                        block.body.append("    %$res = load %MT3Value*, %MT3Value** %$it, align 8\n")
                        return VisitExprResult.SsaIndex(res)
                    }
                }
                globalVars[name].also {
                    if (it != null)
                        return emitLoadGlobalVar(block, it)
                }
                throw RuntimeException("variable not found: $name")
            }
        }
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

    private fun emitAssignLocal(block: BlockContext, name: String, expr: Expr) {
        val where = localVariables[name]!!
        val what = visitExpr(block, expr).toCode()
        emitAssignLocalVariable(block, where, what)
    }

    // TODO: resolve collisions with names containing "plus"
    private fun mangle(name: String): String {
        var r = name
        r = r.replace("==", "equality")
        r = r.replace("+", "plus")
        r = r.replace("-", "minus")
        r = r.replace("*", "mul")
        r = r.replace("/", "div")
        return r
    }

    private fun allocateNativeGlobalsIndex(): Int = moduleNativeGlobalsIndex++
}

private fun emitInitializeGlobalVar(block: BlockContext, what: Int, globalId: String) {
    block.body.append(
        """|    ; Store allocated value to a native global variable
           |    store %MT3Value* %$what, %MT3Value** @$globalId, align 8
           |
           """.trimMargin()
    )
    emitRegisterGcRoot(block, what)
}

private fun emitRegisterGcRoot(block: BlockContext, ix: Int) {
    val casted = block.func.allocateSsaVariable()
    block.body.append(
        """|    %$casted = bitcast %MT3Value* %$ix to %GCObject*
           |    tail call void @mt3_add_gc_root(%GCObject* %$casted)""".trimMargin()
    )
}

private fun emitLoadGlobalVar(block: BlockContext, name: String): Lowering.VisitExprResult {
    val ssa = block.func.allocateSsaVariable()
    block.body.append("    %$ssa = load %MT3Value*, %MT3Value** @$name, align 8\n")
    return Lowering.VisitExprResult.SsaIndex(ssa)
}

private fun emitAllocaLocalVariable(block: BlockContext, ssa: Int, initializer: String) {
    block.body.append("    %$ssa = alloca %MT3Value*, align 8\n")
    emitAssignLocalVariable(block, ssa, initializer)
}

private fun emitAssignLocalVariable(block: BlockContext, where: Int, what: String) {
    block.body.append("    store %MT3Value* $what, %MT3Value** %$where, align 8\n")
}

private fun emitLoadLocalVariable(block: BlockContext, where: Int): Lowering.VisitExprResult {
    val ssa = block.func.allocateSsaVariable()
    block.body.append("    %$ssa = load %MT3Value*, %MT3Value** %$where, align 8\n")
    return Lowering.VisitExprResult.SsaIndex(ssa)
}

private fun emitLoadNone(block: BlockContext): Lowering.VisitExprResult {
    return emitLoadGlobalVar(block, "mt3_none_singleton")
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