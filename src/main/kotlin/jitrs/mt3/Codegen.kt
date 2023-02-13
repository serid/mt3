package jitrs.mt3

import java.nio.file.Files
import java.nio.file.Path

// Assumes clang version 11.1.0

class Lowering {
    /**
     * Code produced during tree walking that should be inserted before [bodyCode].
     * Examples: type declarations, function imports and string constants.
     */
    private val headerCode = StringBuilder()

    private val bodyCode = StringBuilder()

    /**
     * Code produced during tree walking that may be put after [bodyCode].
     */
    private val footerCode = StringBuilder()

    /**
     * When traversing a module, index of the current free global variable where a string literal can be allocated.
     */
    private var moduleStringsIndex = 1

    /**
     * When traversing a function, this is the index of the current free SSA-variable.
     */
    private var localVariableIndex = 0

    fun toLlvm(program: Program): String {
        visitProgram(program)
        bodyCode.insert(0, headerCode.toString())
        bodyCode.append(footerCode.toString())
        return bodyCode.toString()
    }

    private fun visitProgram(program: Program) {
        headerCode.append("; GENERATED CODE\n")
        headerCode.append(Files.readString(Path.of("../src/main/resources/codegen_include.ll")) + "\n\n")

        program.toplevels.forEach {
            visitToplevel(it)
        }
    }

    private fun visitToplevel(toplevel: Toplevel) {
        toplevel.apply {
            when (this) {
                is Toplevel.Fun -> {
                    // mt3lib.cxx expects the main function to be called mt3_main.
                    // Also prefix a user-provided symbol with another "mt3_" if it already starts with one.
                    // This ensures that user-provided symbol names never start with a single "mt3_" this way
                    // the compiler can use "mt3_" namespace all to herself.
                    // TODO: maybe it's easier to just disallow symbols starting with "mt3_"?
                    val funName = if (name == "main" || name.startsWith("mt3_")) "mt3_$name" else name

                    bodyCode.append("define $MT3ValueErased @$funName($MT3ValueErased*) {\n")

                    body.forEach {
                        visitStmt(it)
                    }

                    bodyCode.append("    ret $MT3ValueErased null\n")
                    bodyCode.append("}\n\n")
                }
            }
        }
    }

    private fun visitStmt(stmt: Stmt) {
        stmt.apply {
            when (this) {
                is Stmt.ExprStmt -> {
                    visitExpr(e)
                }
            }
        }
    }

    /**
     * @return index where result of this expression is stored. {null} if the expression does not produce an SSA value
     */
    private fun visitExpr(expr: Expr): VisitExprResult {
        return VisitExprResult.None
        when (expr) {
            is Expr.IntConst -> {
                return VisitExprResult.Immediate(expr.int.toString())
            }

            is Expr.StringConst -> {
                val lenWithNull = (expr.string.length + 1).toString()
                headerCode.append("@mt3_str$moduleStringsIndex = private unnamed_addr constant [$lenWithNull x i8] c\"${expr.string}\\00\", align 1\n")
                return VisitExprResult.Immediate("getelementptr inbounds [$lenWithNull x i8], [$lenWithNull x i8]* @.str, i64 0, i64 0")
            }

            is Expr.Call -> {
                // %1 = load %MT3Value*, %MT3Value** @mt3_main, align 8
                // tail call %MT3Value* @mt3_builtin_call(%MT3Value* %1, i8 0, %MT3Value** null)

                val functionIndex = visitExpr(expr.func).toCode()

                TODO()

                // %1 = alloca [8 x i8], align 16
                // %2 = getelementptr inbounds [8 x i8], [8 x i8]* %1, i64 0, i64 0
                val args = expr.args.map {
                    visitExpr(it)
                }
                // N. B.: no "tail" marker since there is an "alloca". See README.md
                bodyCode.append("call %MT3Value* @mt3_builtin_call(%MT3Value* $functionIndex, i8 ${expr.args.size}, %MT3Value** null)\n")
            }

            is Expr.GlobalRef -> {
                TODO()
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

    private fun allocateSsaVariable(): Int = localVariableIndex++
}

// Generated code treats objects as opaque pointers
private val MT3ValueErased = "%MT3Value*"