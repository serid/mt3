package jitrs.mt3

import jitrs.util.myAssert

fun tokenize(s: String): Iterator<Token> = iterator {
    var i = 0

    while (true) {
        while (i < s.length && s[i].isWhitespace()) i++

        while (true) {
            if (i >= s.length)
                break

            if (s[i].isWhitespace()) {
                i++
                continue
            }

            // Skip comments
            if (i + 1 < s.length && s[i] == '-' && s[i + 1] == '-') {
                i += 2
                while (i < s.length && s[i] != '\n')
                    i++
                continue
            }

            break
        }

        if (i >= s.length) break

        when {
            s[i] == '(' -> {
                i++
                yield(Token(lparen))
            }

            s[i] == ')' -> {
                i++
                yield(Token(rparen))
            }

            s[i].isDigit() -> {
                var n = 0
                while (i < s.length && s[i].isDigit()) {
                    n *= 10
                    n += s[i].digitToInt()
                    i++
                }
                yield(Token(int, n))
            }

            s[i] == '"' -> {
                i++
                val r = StringBuilder()
                while (i < s.length && s[i] != '"') {
                    r.append(s[i])
                    i++
                }
                if (i == s.length)
                    throw RuntimeException("Expected ending quote")
                // Here s[i] == '"', skip it
                i++
                yield(Token(string, r.toString()))
            }

            isIdentStart(s[i]) -> {
                val r = StringBuilder()
                while (i < s.length && isIdentPart(s[i])) {
                    r.append(s[i])
                    i++
                }
                yield(Token(ident, r.toString()))
            }
        }
    }
}

data class Token(val id: TokenId, val data: Any = Unit) : IToken {
    fun getInt(): Int {
        myAssert(id == int)
        return data as Int
    }

    fun getIdent(): String {
        myAssert(id == ident)
        return data as String
    }

    fun getString(): String {
        myAssert(id == string)
        return data as String
    }

    override fun isLParen(): Boolean = id == lparen

    override fun isRParen(): Boolean = id == rparen

    override fun toString(): String = "T($id" + if (data !is Unit) ", $data)" else ")"
}

typealias TokenId = Int

val tokenIds: Array<String> = arrayOf("(", ")", "<int>", "<ident>", "<string>")
val lparen: TokenId = tokenIds.indexOf("(")
val rparen: TokenId = tokenIds.indexOf(")")
val int: TokenId = tokenIds.indexOf("<int>")
val ident: TokenId = tokenIds.indexOf("<ident>")
val string: TokenId = tokenIds.indexOf("<string>")

//fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '-'
fun isIdentStart(c: Char): Boolean = isAsciiPrintable(c) && c != '(' && c != ')' && c != '"' && !c.isDigit()
        && !c.isWhitespace()

fun isIdentPart(c: Char): Boolean = isIdentStart(c) || c.isDigit()

// From space to tilde, inclusive
fun isAsciiPrintable(c: Char): Boolean = 32.toChar() <= c && c <= 127.toChar()