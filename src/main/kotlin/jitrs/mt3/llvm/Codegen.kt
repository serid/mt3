package jitrs.mt3.llvm

class Codegen {
    /**
     * Code produced during tree walking that should be inserted before [bodyCode].
     * Examples: type declarations, function imports and string constants.
     */
    val headerCode = StringBuilder()

    val bodyCode = StringBuilder()

    /**
     * Code produced during tree walking that may be put after [bodyCode].
     */
    val footerCode = StringBuilder()

    fun appendHeader(s: String) {
        headerCode.append(s)
    }

    fun appendBody(s: String) {
        bodyCode.append(s)
    }

    fun appendFooter(s: String) {
        footerCode.append(s)
    }

    init {
        headerCode.append("; <HEADER>\n")
        bodyCode.append("; <BODY>\n")
        footerCode.append("; <FOOTER>\n")
    }

    fun get(): String {
        headerCode.append("; </ HEADER>\n\n")
        bodyCode.append("; </ BODY>\n\n")
        footerCode.append("; </ FOOTER>\n")

        headerCode.append(bodyCode.toString())
        headerCode.append(footerCode.toString())
        return headerCode.toString()
    }
}