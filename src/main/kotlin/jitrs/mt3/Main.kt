package jitrs.mt3

import jitrs.util.PeekableIterator
import jitrs.util.priceyToArray
import java.nio.file.Files

fun main(args: Array<String>) {
    println("Hello World!")

    // Try adding program arguments via Run/Debug configuration.
    // Learn more about running applications: https://www.jetbrains.com/help/idea/running-applications.html.
    println("Program arguments: ${args.joinToString()}")

    val src = """
        |(fun println (s)
        |    (print s)
        |    (print "\n")
        |)
        |
        |(fun main ()
        |    (println "Doge")
        |    (let number (+ 1 2))
        |    (print "Test: ")
        |    (println number)
        |    (= number (- 5 (* 5 5)))
        |    (println number)
        |    (println (+ "result: " (fibonacci number)))
        |)
    """.trimMargin()
    val tokens = tokenize(src).asSequence().priceyToArray()
    val sexprs = parseSExprs(PeekableIterator(tokens.iterator()))
    val program = programFromSExprs(sexprs)
    val lir = Lowering("lemod").toLlvm(program)

    println(tokens.joinToString())
    println(sexprs.joinToString())
    println(program)
//    println(lir)

    Files.writeString(mt3MainLl, lir)

    val beforeLink = System.nanoTime()
    Linker(Linker.Mode.FAST_COMPILETIME).link()
    println("info: transpilation and linking took ${(System.nanoTime() - beforeLink) / 1_000_000} ms")
}