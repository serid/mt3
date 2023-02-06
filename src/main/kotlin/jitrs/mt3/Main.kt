package jitrs.mt3

import jitrs.util.PeekableIterator
import jitrs.util.priceyToArray

fun main(args: Array<String>) {
    println("Hello World!")

    // Try adding program arguments via Run/Debug configuration.
    // Learn more about running applications: https://www.jetbrains.com/help/idea/running-applications.html.
    println("Program arguments: ${args.joinToString()}")

    val tokens = tokenize("(fun main () (+ 1 2))").asSequence().priceyToArray()
    val sexpr = parseSExprs(PeekableIterator( tokens.iterator()))
    val program = programfromSExprs(sexpr)

    println(tokens.joinToString())
    println(sexpr.joinToString())
    println(program)
}