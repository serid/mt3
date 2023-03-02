package jitrs.mt3.llvm

class Function(type: String, name: String, parameters: String, nOfParameters: Int) {
    private val header = "define $type @$name($parameters) {\n"
    private var freeSsa: Int = nOfParameters
    private val blocks = ArrayList<Block>()

    fun allocateSsaVariable(): Int = freeSsa++

    fun newBlock(): Block {
        val r = Block(this, this.allocateSsaVariable())
        blocks.add(r)
        return r
    }

    fun blit(out: StringBuilder) {
        out.append(header)
        blocks.forEach {
            it.blit(out)
        }
        out.append("}\n\n")
    }
}

class Block(
    val func: Function,
    private val id: Int
) {
    val body = StringBuilder()
    private var finalized = false

    fun isFinalized(): Boolean = finalized

    fun finalizeWithReturn(returnExpression: String) {
        body.append("    ret $returnExpression\n")
        finalize1()
    }

    fun finalizeWithUnconditional(onEnd: Block) {
        body.append("    br label %${onEnd.id}\n")
        finalize1()
    }

    fun finalizeWithConditional(condition: String, onTrue: Block, onFalse: Block) {
        body.append("    br i1 $condition, label %${onTrue.id}, label %${onFalse.id}\n")
        finalize1()
    }

    private fun finalize1() {
        if (finalized) throw RuntimeException("already finalized")
        finalized = true
    }

    fun blit(out: StringBuilder) {
        if (!finalized) throw RuntimeException("not finalized yet")
        out.append("$id:\n")
        out.append(body)
    }
}

sealed class LLVMExpression {
    data class SsaIndex(val ssa: Int) : LLVMExpression()

    /**
     * This variant is returned if the expression cannot be assigned an index and
     * should be used inline in the containing SSA expression.
     */
    data class Immediate(val code: String) : LLVMExpression()
    object None : LLVMExpression()

    fun toCode(): String = when (this) {
        is SsaIndex -> "%$ssa"
        is Immediate -> code
        is None -> throw RuntimeException("this expression has no result")
    }
}