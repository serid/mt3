package jitrs.mt3.llvm

class Codegen {
    /**
     * Code produced during tree walking that should be inserted before [bodyCode].
     * Examples: type declarations, function imports and string constants.
     */
    val headerCode = StringBuilder()

    val bodyCode = StringBuilder().apply { ensureCapacity(10000) }

    /**
     * Code produced during tree walking that may be put after [bodyCode].
     */
    val footerCode = StringBuilder()

    init {
        headerCode.append("; <HEADER>\n")
        bodyCode.append("; <BODY>\n")
        footerCode.append("; <FOOTER>\n")
    }

    fun get(): String {
        headerCode.append("; </ HEADER>\n\n")
        bodyCode.append("; </ BODY>\n\n")
        footerCode.append("; </ FOOTER>\n")

        headerCode.append(bodyCode)
        headerCode.append(footerCode)
        return headerCode.toString()
    }
}