package jitrs.mt3.llvm

class FunctionContext(type: String, name: String, parameters: String, nOfParameters: Int) {
    private val header = "define $type @$name($parameters) {\n"
    private var freeSsa: Int = nOfParameters
    private val blocks = ArrayList<BlockContext>()

    fun allocateSsaVariable(): Int = freeSsa++

    fun newBlock(): BlockContext {
        val r = BlockContext(this, this.allocateSsaVariable())
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

class BlockContext(
    val func: FunctionContext,
    private val id: Int
) {
    val body = StringBuilder()
    private var finalized = false

    fun isFinalized(): Boolean = finalized

    fun finalizeWithReturn(returnExpression: String) {
        body.append("    ret $returnExpression\n")
        finalize1()
    }

    fun finalizeWithUnconditional(onEnd: BlockContext) {
        body.append("    br label %${onEnd.id}\n")
        finalize1()
    }

    fun finalizeWithConditional(condition: String, onTrue: BlockContext, onFalse: BlockContext) {
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