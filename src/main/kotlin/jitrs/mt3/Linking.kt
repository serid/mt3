package jitrs.mt3

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

private val sharedCxxFlags = arrayOf("-fno-rtti", "-fno-exceptions", "--std=c++20")

class Linker(private val mode: Mode) {
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
        if (mode != Mode.LTO) {
            // Compile stdlib and main module in parallel
            val stdlibFuture = ensureStdlibIsReady()
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
                "-o", "./workdir/out"
            ).waitFor()
        } else {
            startProcessWithStdout(
                "clang++",
                mt3LibCxx.absolutePathString(),
                mt3MainLl.absolutePathString(),
                *sharedCxxFlags,
                "-O2", "-static-libstdc++", "-flto",
                "-o", "./workdir/out"
            )
        }
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

    // TODO: stdlib should be compiled in a Gradle task together with main Kotlin source code
    /**
     * Ensures that mt3lib.cxx is translated to mt3lib.o
     */
    private fun ensureStdlibIsReady(): Future<Unit> {
        // Future will either complete immediately, or when the stdlib finishes compiling
        val future = CompletableFuture<Unit>()
        if (!mt3LibO.exists() || maxOf(
                getHighestModificationTime(compilerSrc),
                getHighestModificationTime(mt3LibSrc)
            ) > mt3LibO.getLastModifiedTime()
        ) {
            Thread {
                println("info: translating mt3lib.cxx to mt3lib.o")
                val p = startProcessWithStdout(
                    "clang++",
                    "-c",
                    *cxxFlags,
                    mt3LibCxx.absolutePathString(),
                    "-o",
                    mt3LibO.absolutePathString()
                )
                p.waitFor()
                future.complete(Unit)
            }.start()
        } else {
            future.complete(Unit)
        }
        return future
    }

    enum class Mode {
        DEBUG, FAST_COMPILETIME, LTO
    }
}

fun clearWorkdir() {
    Files.newDirectoryStream(workdir).forEach { Files.delete(it) }
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

val mt3LibSrc: Path = Path.of("./src/main/resources/")
val compilerSrc: Path = Path.of("./src/main/kotlin/")
val workdir: Path = Path.of("./workdir/")
val mt3LibCxx: Path = Path.of("./src/main/resources/mt3lib.cxx")
val mt3LibO: Path = Path.of("./workdir/mt3lib.o")
val mt3MainLl: Path = Path.of("./workdir/mt3main.ll")
val mt3MainO: Path = Path.of("./workdir/mt3main.o")

// -nostdlib++ -nostdinc++
val linkerFlags = arrayOf("-Os", "-static-libstdc++"/*, "-flto"*/)