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

fun functionalityTest(src: String, expectedStdout: String) {
    myAssertEqual(execMt3(src), expectedStdout)
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