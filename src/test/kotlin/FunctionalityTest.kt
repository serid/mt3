import jitrs.mt3.ProgramLowering
import jitrs.mt3.linking.Linker
import jitrs.mt3.parseSExprs
import jitrs.mt3.programFromSExprs
import jitrs.mt3.tokenize
import jitrs.util.PeekableIterator
import jitrs.util.logTime
import jitrs.util.myAssertEqual
import jitrs.util.priceyToArray
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.io.path.absolutePathString

fun functionalityTest(testName: String, src: String, expectedStdout: String) {
    myAssertEqual(execMt3(testName, src), expectedStdout)
}

/**
 * @return stdout of running the program
 */
private fun execMt3(filenames: String, src: String): String {
    val tokens = tokenize(src).asSequence().priceyToArray()
    val sexprs = parseSExprs(PeekableIterator(tokens.iterator()))
    val program = programFromSExprs(sexprs)
    val lir = ProgramLowering("lemod").toLlvm(program)

    val mt3MainLl = Files.createTempFile("$filenames-llvm-ir", ".ll")
    val mt3MainO = Files.createTempFile("$filenames-obj", ".o")
    val out = Files.createTempFile("$filenames-executable", "")

    Files.writeString(mt3MainLl, lir)

    logTime("info: transpilation and linking took") {
        Linker(Linker.Mode.FAST_COMPILETIME, mt3MainLl, mt3MainO, out).link()
    }

    val mt3Process = ProcessBuilder(out.absolutePathString()).start()

    val mt3Stdout = ByteArrayOutputStream(512)
    mt3Process.inputStream.copyTo(mt3Stdout)
    mt3Process.waitFor()
    if (mt3Process.exitValue() != 0)
        throw RuntimeException("mt3 exit code: ${mt3Process.exitValue()}")

    return mt3Stdout.toString(Charsets.UTF_8)
}