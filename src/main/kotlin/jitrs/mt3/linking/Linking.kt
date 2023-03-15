package jitrs.mt3.linking

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.absolutePathString
import kotlin.io.path.getLastModifiedTime

private val sharedCxxFlags = arrayOf("-fno-rtti", "-fno-exceptions", "--std=c++20")

class Linker(
    private val mode: Mode,
    private val mt3MainLl: Path,
    private val mt3MainO: Path,
    private val output: Path
) {
    private val cxxFlags = when (mode) {
        Mode.DEBUG -> arrayOf(*sharedCxxFlags, "-Os", "-g")
        Mode.FAST_COMPILETIME -> arrayOf(*sharedCxxFlags, "-O0", "-U", "_FORTIFY_SOURCE")
        Mode.LTO -> arrayOf()
    }

    private val llcFlags: Array<String> = when (mode) {
        Mode.DEBUG -> arrayOf("--cost-kind=code-size")
        Mode.FAST_COMPILETIME -> arrayOf()
        Mode.LTO -> arrayOf()
    }

    // "-Os", "-static-libstdc++"
    private val linkerFlags: Array<String> = when (mode) {
        Mode.DEBUG -> arrayOf()
        Mode.FAST_COMPILETIME -> arrayOf()
        Mode.LTO -> arrayOf()
    }

    fun link() {
        val linkerProcess = if (mode != Mode.LTO) {
            // Compile stdlib and main module in parallel
            val stdlibFuture = StdlibTranslationManager.ensureStdlibIsReady(cxxFlags)
            startProcessWithStdout(
                "llc",
                "--filetype=obj",
                *llcFlags,
                mt3MainLl.absolutePathString(),
                "-o",
                mt3MainO.absolutePathString(),
            ).waitFor()
            stdlibFuture.get()

            startProcessWithStdout(
                "clang++",
                "-fuse-ld=mold",
                *linkerFlags,
                mt3LibO.absolutePathString(),
                mt3MainO.absolutePathString(),
                "-o", output.absolutePathString()
            )
        } else {
            startProcessWithStdout(
                "clang++",
                mt3LibCxx.absolutePathString(),
                mt3MainLl.absolutePathString(),
                *sharedCxxFlags,
                "-O2", "-static-libstdc++", "-flto",
                "-o", output.absolutePathString()
            )
        }
        linkerProcess.waitFor()
        if (linkerProcess.exitValue() != 0)
            throw RuntimeException("linking failed")
    }

    fun emitStdlibLlvm() {
        startProcessWithStdout(
            "clang++",
            "-Os",
            "-S",
            "-emit-llvm",
            mt3LibCxx.absolutePathString(),
            "-o",
            mt3MainLl.absolutePathString()
        ).waitFor()
    }

    enum class Mode {
        DEBUG, FAST_COMPILETIME, LTO
    }
}

fun clearWorkdir() {
    Files.newDirectoryStream(Path.of("./workdir/")).forEach { Files.delete(it) }
//    Files.walk(workdir).forEach { Files.delete(it) }
}

fun startProcessWithStdout(vararg strings: String): Process = startProcessWithStdout(ProcessBuilder(*strings))

fun startProcessWithStdout(processBuilder: ProcessBuilder): Process {
    println("running: " + processBuilder.command().joinToString(" "))
    val process = processBuilder.redirectErrorStream(true).start()
    process.inputStream.copyTo(System.err)
    return process
}

fun getHighestModificationTime(path: Path): FileTime {
    // Find most recent modification time
    val srcMTimeStream = Files.walk(path).map(Path::getLastModifiedTime)
    return srcMTimeStream.use { it.max(FileTime::compareTo).get() }
}

// -nostdlib++ -nostdinc++
val linkerFlags = arrayOf("-Os", "-static-libstdc++"/*, "-flto"*/)