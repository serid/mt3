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
        |(fun print-ln (s)
        |    (print s)
        |    (print "\n")
        |)
        |
        |(fun main ()
        |    (let my-prototype (new))
        |    (.= my-prototype increment (fun (self) (+ self 1)))
        |    
        |    (let obj 10)
        |    (: set-prototype obj my-prototype)
        |    (print-ln (: increment obj))
        |    (print-ln (: to-string obj))
        |)
    """.trimMargin()
    val tokens = tokenize(src).asSequence().priceyToArray()
    val sexprs = parseSExprs(PeekableIterator(tokens.iterator()), true)
    val program = programFromSExprs(sexprs)
    val lir = ProgramLowering("lemod").toLlvm(program)

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