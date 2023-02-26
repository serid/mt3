package jitrs.mt3.llvm

data class FunctionContext(val body: StringBuilder, var freeSsa: Int = 1) {
    fun allocateSsaVariable(): Int = freeSsa++
}