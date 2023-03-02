import jitrs.mt3.*
import jitrs.mt3.linking.Linker
import jitrs.util.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString

fun main() {
    val tests = sequenceOf(::test1, ::test2)
    val errors = ArrayList<Throwable>()

    // Spawn a thread for each test and only after that join them
    val threads = tests.map {
        thread(start = false) {
            it()
        }.apply {
            setUncaughtExceptionHandler { _, e -> errors.add(e) }
            start()
        }
    }.priceyToArray()
    threads.forEach { it.join() }

    errors.forEach { throw it }

    println("Tests result: [SUCCESS]")
}

private fun test1() {
    myAssert(execMt3("""(fun main () (print 10))""") == "10")
}

private fun test2() {
    val actual = execMt3(
        """
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
        |        (print (+ (fibonacci i) ","))
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
        |    (print "")
        |)""".trimMargin()
    )

    val expected = "Here are 10 fibonacci numbers: 0,1,1,2,3,5,8,13,21,34,"

    myAssertEqual(actual, expected)
}

/**
 * @return stdout of running the program
 */
private fun execMt3(src: String): String {
    val tokens = tokenize(src).asSequence().priceyToArray()
    val sexprs = parseSExprs(PeekableIterator(tokens.iterator()))
    val program = programFromSExprs(sexprs)
    val lir = ProgramLowering("lemod").toLlvm(program)

    val mt3MainLl = Files.createTempFile("mainmod-llvm-ir", ".ll")
    val mt3MainO = Files.createTempFile("mainmod-obj", ".o")
    val out = Files.createTempFile("mt3-executable", "")

    Files.writeString(mt3MainLl, lir)

    logTime("info: transpilation and linking took") {
        Linker(Linker.Mode.FAST_COMPILETIME, mt3MainLl, mt3MainO, out).link()
    }

    val mt3Process = ProcessBuilder(out.absolutePathString()).start()

    val mt3Stdout = ByteArrayOutputStream(512)
    mt3Process.inputStream.copyTo(mt3Stdout)
    mt3Process.waitFor()

    return mt3Stdout.toString(Charsets.UTF_8)
}