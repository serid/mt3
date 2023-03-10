package jitrs.mt3

import jitrs.mt3.llvm.Block
import jitrs.mt3.llvm.Codegen
import jitrs.mt3.llvm.Function
import jitrs.mt3.llvm.LLVMExpression
import jitrs.util.countFrom
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

// Assumes clang version 14.0.6

/**
 * Convert an AST of an MT3 program to LLVM IR. This compiler is not incremental and puts all MT3 modules in one
 * LLVM module, then links it with mt3lib.ll.
 */
class ProgramLowering(val moduleName: String) {
    val codegen = Codegen()

    val globalVars = hashMapOf(
        "!" to "mt3_stdlib_logical_not",
        "print" to "mt3_stdlib_print",
        "==" to "mt3_stdlib_equality",
        "!=" to "mt3_stdlib_inequality",
        "+" to "mt3_stdlib_plus",
        "-" to "mt3_stdlib_minus",
        "*" to "mt3_stdlib_mul",
        "/" to "mt3_stdlib_div",
        "<" to "mt3_stdlib_less",
        "<=" to "mt3_stdlib_lax_less",
        ">" to "mt3_stdlib_greater",
        ">=" to "mt3_stdlib_lax_greater",
        "new" to "mt3_stdlib_new",

        "none" to "mt3_stdlib_none",
        "false" to "mt3_stdlib_false",
        "true" to "mt3_stdlib_true",
    )

    /**
     * When traversing a module, index of the current free native global variable where
     * a string literal or an int can be allocated.
     */
    private var moduleNativeGlobalsIndex = 1

    /**
     * Codegen will reify call sequences with arities from this set.
     * Arity 0 is needed for mt3lib.cxx.
     */
    val callSequencesToGenerate = TreeSet(arrayListOf(0))

    val moduleInitializer = ArrayList<(block: Block) -> Unit>()

    fun toLlvm(program: Program): String {
        visitProgram(program)
        return codegen.get()
    }

    private fun visitProgram(program: Program) {
        codegen.headerCode.append(
            """
            |; <codegen_include.ll>
            |${Files.readString(Path.of("./src/main/resources/codegen_include.ll"))}
            |; </ codegen_include.ll>
            |
        """.trimMargin()
        )

        // Import native globals for each predeclared global
        globalVars.forEach { (_, longName) ->
            codegen.headerCode.append("@$longName = external global %MT3Value*, align 8\n")
        }

        program.toplevels.forEach {
            visitToplevel(it)
        }

        // Emit module initializer
        val initializer = Function("void", "mt3_mainmod_init", "", 0)
        val bloc = initializer.newBlock()
        moduleInitializer.forEach {
            it(bloc)
        }
        bloc.finalizeWithReturn("void")
        initializer.blit(codegen.bodyCode)

        callSequencesToGenerate.forEach { i -> reifyCallSequence(codegen.headerCode, i) }
    }

    private fun visitToplevel(toplevel: Toplevel) {
        when (toplevel) {
            is Toplevel.Fun -> {
                FunctionLowering(this).visitFun(toplevel)
            }
        }
    }

    fun allocateNativeGlobalsIndex(): Int = moduleNativeGlobalsIndex++
}

class FunctionLowering(private val owningProgram: ProgramLowering) {
    private val localVariables = HashMap<String, Int>()

    /**
     * When compiling a function, we don't know what id the final block will have so we add jumps to it later.
     */
    private val blocksToFinalizeWithReturn = ArrayList<Block>()

    fun visitFun(func: Toplevel.Fun) {
        val arity = func.params.size

        // mt3lib.cxx expects the main function to be called mt3_main.
        // Also prefix a user-provided symbol with another "mt3_" if it already starts with one.
        // This ensures that user-provided symbol names never start with a single "mt3_" this way
        // the compiler can use "mt3_" namespace all to herself.
        // TODO: maybe it's easier to just disallow symbols starting with "mt3_"?
        //var funName =
        //    if (func.name == "main" || func.name.startsWith("mt3_")) "mt3_${func.name}" else func.name

        val shortName = func.name
        val longName = "mt3_${owningProgram.moduleName}_${mangle(shortName)}"
        val codeName = "${longName}-fun"

        // Add the function name to globals before lowering it to allow recursion
        if (owningProgram.globalVars.put(shortName, longName) != null)
            throw RuntimeException("error: global var $shortName already exists")

        val generatedParameters = countFrom(0).take(arity).map { "%MT3Value* %$it" }.joinToString()

        // Skip ssa-s already used for generatedParameters
        val cfc = Function(MT3ValueErased, codeName, generatedParameters, arity)

        val entryBlock = cfc.newBlock()

        // Add parameters as local variables
        func.params.forEachIndexed { index, name ->
            val ssa = cfc.allocateSsaVariable()
            if (localVariables.put(name, ssa) != null)
                throw RuntimeException("error: parameter $name already exists")
            emitAllocaLocalVariable(entryBlock, ssa, "%$index")
        }

        val none = emitLoadNone(entryBlock).toCode()

        // Add local variables as local variables
        // Declarations of local variables are hoisted to function header and are not lexical
        val declarations = collectFunctionsVariables(func, HashSet(localVariables.keys))
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

        var currentBlock: Block? = entryBlock

        for (it in func.body) {
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
        cfc.blit(owningProgram.codegen.bodyCode)


        // Create a native global holding MT3Value* pointer to a function value

        val valueId = if (func.name == "main") "mt3_main" else longName
        val modifier = if (func.name == "main") "local_unnamed_addr" else "private unnamed_addr"

        val funPtrTypeList = countFrom(1).take(arity).map { "%MT3Value*" }.joinToString()

        owningProgram.codegen.headerCode.append("@$valueId = $modifier global %MT3Value* null, align 8\n")

        owningProgram.moduleInitializer.add { block ->
            val casted = block.func.allocateSsaVariable()
            val res = block.func.allocateSsaVariable()
            block.body.append("    %$casted = bitcast %MT3Value* ($funPtrTypeList)* @$codeName to i8*\n")
            block.body
                .append("    %$res = tail call %MT3Value* @mt3_new_function(i8 ${func.arity()}, i8* %$casted)\n")
            emitInitializeGlobalVariable(block, res, valueId)
        }
    }

    @Suppress("UnnecessaryVariable")
    private fun visitStmt(block: Block, stmt: Stmt): Block? {
        when (stmt) {
            is Stmt.VariableDefinition -> {
                // Variable declarations are hoisted to the function header in [visitFun]

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
                var onTrue: Block? = onTrueEntry

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
                var loop: Block? = loopEntry

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
    private fun visitExpr(block: Block, expr: Expr): LLVMExpression {
        when (expr) {
            is Expr.IntConst -> {
                val valueId = "mt3_intV${owningProgram.allocateNativeGlobalsIndex()}"

                // Create a native global holding MT3Value* pointer to a string value
                owningProgram.codegen.headerCode.append("@$valueId = private unnamed_addr global %MT3Value* null, align 8\n")

                owningProgram.moduleInitializer.add { block2 ->
                    val res = block2.func.allocateSsaVariable()
                    block2.body.append("    %$res = tail call %MT3Value* @mt3_new_int(i64 ${expr.int})\n")
                    emitInitializeGlobalVariable(block2, res, valueId)
                }

                return emitLoadGlobalVariable(block, valueId)
            }

            is Expr.StringConst -> {
                val lenWithNull = (expr.string.length + 1).toString()
                val id = owningProgram.allocateNativeGlobalsIndex()
                val bytesId = "mt3_str$id"
                val valueId = "mt3_strV$id"

                // Create a native global holding bytes of this string literal
                owningProgram.codegen.headerCode.append("@$bytesId = private unnamed_addr constant [$lenWithNull x i8] c\"${expr.string}\\00\", align 1\n")

                // Create a native global holding MT3Value* pointer to a string value
                owningProgram.codegen.headerCode.append("@$valueId = private unnamed_addr global %MT3Value* null, align 8\n")

                owningProgram.moduleInitializer.add { block2 ->
                    val res = block2.func.allocateSsaVariable()
                    block2.body.append("    %$res = tail call %MT3Value* @mt3_new_string(i8* getelementptr inbounds ([$lenWithNull x i8], [$lenWithNull x i8]* @$bytesId, i64 0, i64 0))\n")
                    emitInitializeGlobalVariable(block2, res, valueId)
                }

                return emitLoadGlobalVariable(block, valueId)
            }

            is Expr.Call -> {
                owningProgram.callSequencesToGenerate.add(expr.arity())

                val functionIndex = visitExpr(block, expr.func).toCode()
                val args = (sequenceOf("%MT3Value* $functionIndex") + expr.args.asSequence().map {
                    "%MT3Value* ${visitExpr(block, it).toCode()}"
                }).joinToString()
                val ssa = block.func.allocateSsaVariable()

                block.body.append("    %$ssa = tail call %MT3Value* @mt3_builtin_call${expr.arity()}($args)\n")
                return LLVMExpression.SsaIndex(ssa)
            }

            is Expr.VariableUse -> {
                val name = expr.name

                localVariables[name].also {
                    if (it != null)
                        return emitLoadLocalVariable(block, it)
                }
                owningProgram.globalVars[name].also {
                    if (it != null)
                        return emitLoadGlobalVariable(block, it)
                }
                throw RuntimeException("variable not found: $name")
            }
        }
    }

    private fun emitAssignLocal(block: Block, name: String, expr: Expr) {
        val where = localVariables[name]!!
        val what = visitExpr(block, expr).toCode()
        emitAssignLocalVariable(block, where, what)
    }
}

private fun emitInitializeGlobalVariable(block: Block, what: Int, globalId: String) {
    block.body.append("    store %MT3Value* %$what, %MT3Value** @$globalId, align 8\n")
    emitRegisterGcRoot(block, what)
}

private fun emitRegisterGcRoot(block: Block, ssa: Int) {
    val casted = block.func.allocateSsaVariable()
    block.body.append(
        """|    %$casted = bitcast %MT3Value* %$ssa to %GCObject*
           |    tail call void @mt3_add_gc_root(%GCObject* %$casted)""".trimMargin()
    )
}

private fun emitLoadGlobalVariable(block: Block, name: String): LLVMExpression {
    val ssa = block.func.allocateSsaVariable()
    block.body.append("    %$ssa = load %MT3Value*, %MT3Value** @$name, align 8\n")
    return LLVMExpression.SsaIndex(ssa)
}

private fun emitAllocaLocalVariable(block: Block, ssa: Int, initializer: String) {
    block.body.append("    %$ssa = alloca %MT3Value*, align 8\n")
    emitAssignLocalVariable(block, ssa, initializer)
}

private fun emitAssignLocalVariable(block: Block, where: Int, what: String) {
    block.body.append("    store %MT3Value* $what, %MT3Value** %$where, align 8\n")
}

private fun emitLoadLocalVariable(block: Block, fromWhere: Int): LLVMExpression {
    val ssa = block.func.allocateSsaVariable()
    block.body.append("    %$ssa = load %MT3Value*, %MT3Value** %$fromWhere, align 8\n")
    return LLVMExpression.SsaIndex(ssa)
}

private fun emitLoadNone(block: Block): LLVMExpression {
    return emitLoadGlobalVariable(block, "mt3_stdlib_none")
}

// TODO: resolve collisions with names containing "plus"
private fun mangle(name: String): String {
    var r = name
    r = r.replace("!", "\$exclamation")
    r = r.replace("=", "\$eq")
    r = r.replace("+", "\$plus")
    r = r.replace("*", "\$asterisk")
    r = r.replace("/", "\$slash")
    r = r.replace("<", "\$lessthan")
    r = r.replace(">", "\$greaterthan")
    r = r.replace(".", "\$dot")
    return r
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