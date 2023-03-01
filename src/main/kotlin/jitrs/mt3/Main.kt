package jitrs.mt3

import jitrs.mt3.linking.Linker
import jitrs.util.PeekableIterator
import jitrs.util.logTime
import jitrs.util.priceyToArray
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    println("Hello World!")

    val src = """
        |(fun fibonacci (n)
        |    (if (== n 0) (return 0))
        |    (if (== n 1) (return 1))
        |    (return (+
        |        (fibonacci (- n 1))
        |        (fibonacci (- n 2))
        |    ))
        |)
        |
        |(fun print-numbers (num)
        |    (let i 0)
        |    (while (< i num)
        |        (print (+ (fibonacci i) " "))
        |        (= i (+ i 1))
        |    )
        |)
        |
        |(fun println (s)
        |    (print s)
        |    (print "\n")
        |)
        |
        |(fun main ()
        |    (print "Here are 10 fibonacci numbers: ")
        |    (print-numbers 10)
        |    (println "")
        |    
        |    (let number (+ 1 2))
        |    (print "Test: ")
        |    (println number)
        |    (= number (* 2 4))
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

    val mt3MainLl = Files.createTempFile("mainmod-llvm-ir", ".ll")
    val mt3MainO = Files.createTempFile("mainmod-obj", ".o")

    Files.writeString(mt3MainLl, lir)

    logTime("info: transpilation and linking took") {
        Linker(Linker.Mode.FAST_COMPILETIME, mt3MainLl, mt3MainO, Path.of("./workdir/out")).link()
    }

//    Files.delete(mt3MainLl)
//    Files.delete(mt3MainO)
}