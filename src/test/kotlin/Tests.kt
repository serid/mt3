import jitrs.util.priceyToArray
import kotlin.concurrent.thread

fun main() {
    val tests = sequenceOf(::test1, ::test2, ::objectTest, ::methodTest)
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
    functionalityTest("test1", """(fun main () (print 10))""", "10")
}

private fun test2() {
    functionalityTest(
        "test2",
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

private fun objectTest() {
    functionalityTest(
        "objects",
        """
        |(fun main()
        |    (let pair1 (new))
        |    (.= pair1 x 10)
        |    (.= pair1 y 20)
        |    (print (. pair1 x))
        |    (print (. pair1 y))
        |)""".trimMargin(),
        "1020"
    )
}

private fun methodTest() {
    functionalityTest(
        "methods",
        """
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
        |    
        |    (let proto1 (: get-prototype "Doge"))
        |    (let proto2 (: get-prototype (: get-prototype (: get-prototype (: get-prototype my-prototype)))))
        |    (print-ln (: reference-equals proto1 proto2))
        |)""".trimMargin(),
        "11\n10\ntrue\n"
    )
}