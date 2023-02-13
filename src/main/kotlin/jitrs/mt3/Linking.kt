package jitrs.mt3

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

fun link() {
    ensureStdlibIsReady()

    startProcessWithStdout(
        "clang++",
        *linkerFlags,
        mt3Ll.absolutePathString(),
        mt3MainLl.absolutePathString()
    )
}

/**
 * Ensures that mt3lib.c is translated to mt3lib.ll
 */
// TODO: stdlib should be compiled in a Gradle task together with main Kotlin source code
fun ensureStdlibIsReady() {
    if (!mt3Ll.exists() || maxOf(
            getHighestModificationTime(compilerSrc),
            getHighestModificationTime(mt3LibSrc)
        ) > mt3Ll.getLastModifiedTime()
    ) {
        println("Translating mt3lib.cxx to mt3lib.ll")
        startProcessWithStdout(
            "clang++",
            *cxxLlvmFlags,
            "-o",
            mt3Ll.absolutePathString(),
            mt3Cxx.absolutePathString()
        )
    }
}

fun startProcessWithStdout(vararg strings: String) {
    startProcessWithStdout(ProcessBuilder(*strings))
}

fun startProcessWithStdout(processBuilder: ProcessBuilder) {
    println("running: " + processBuilder.command().joinToString(" "))
    val process = processBuilder.redirectErrorStream(true).start()
    process.inputStream.copyTo(System.err)
}

fun getHighestModificationTime(path: Path): FileTime {
    // Find most recent modification time
    val srcMTimeStream = Files.walk(path).map(Path::getLastModifiedTime)
    return srcMTimeStream.use { it.max(FileTime::compareTo).get() }
}

val mt3LibSrc: Path = Path.of("../src/main/resources/")
val compilerSrc: Path = Path.of("../src/main/kotlin/")
val mt3Cxx: Path = Path.of("../src/main/resources/mt3lib.cxx")
val mt3Ll: Path = Path.of("./mt3lib.ll")
val mt3MainLl: Path = Path.of("./mt3main.ll")

// -nostdlib++ -nostdinc++
val clangFlags = arrayOf<String>()
val cxxFlags = arrayOf(*clangFlags, "-Os", "-fno-rtti", "-fno-exceptions")
val cxxLlvmFlags = arrayOf(*cxxFlags, "-S", "-emit-llvm")
val linkerFlags = arrayOf(*clangFlags, "-Os", "-static-libstdc++"/*, "-flto"*/)