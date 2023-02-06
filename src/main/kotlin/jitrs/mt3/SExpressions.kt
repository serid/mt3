// s-expressions parser

package jitrs.mt3

import jitrs.util.PeekableIterator

fun <K : IToken> parse(tokens: PeekableIterator<K>): SExpr<K> {
    val t = tokens.next()
    return if (t.isLParen())
        SExpr.Node(parseSExprs(tokens))
    else
        SExpr.Leaf(t)
}

/**
 * Parse exprs until EOF or rparen
 */
fun <K : IToken> parseSExprs(tokens: PeekableIterator<K>): Array<SExpr<K>> {
    val subexprs = ArrayList<SExpr<K>>()
    while (true) {
        if (!tokens.hasNext())
            return subexprs.toTypedArray()
        else if (tokens.peek().isRParen()) {
            tokens.next() // Skip peeked RParen
            return subexprs.toTypedArray()
        }
        subexprs.add(parse(tokens))
    }
}

sealed class SExpr<K : IToken> {
    class Node<K : IToken>(val subexprs: Array<SExpr<K>>) : SExpr<K>()

    class Leaf<K : IToken>(val token: K) : SExpr<K>()

    override fun toString(): String = when (this) {
        is Node -> "(${subexprs.joinToString(" ")})"
        is Leaf -> "$token"
    }
}

interface IToken {
    fun isLParen(): Boolean

    fun isRParen(): Boolean
}