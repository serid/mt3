import jitrs.util.priceyToArray
import kotlin.concurrent.thread

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
    functionalityTest("""(fun main () (print 10))""", "10")
}

private fun test2() {
    functionalityTest(
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
        |)""".trimMargin(),
        "Here are 10 fibonacci numbers: 0,1,1,2,3,5,8,13,21,34,"
    )
}