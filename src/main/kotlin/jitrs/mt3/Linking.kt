package jitrs.mt3

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

fun link() {
    ensureStdlibIsReady()

    runProcess(ProcessBuilder(
        "clang++",
        "-Os",
        *linker_flags,
        mt3Ll.absolutePathString()
    ))
}

/**
 * Ensures that mt3lib.c is translated to mt3lib.ll
 */
// TODO: stdlib should be compiled in a Gradle task together with main Kotlin source code
fun ensureStdlibIsReady() {
    if (!mt3Ll.exists() || mt3C.getLastModifiedTime() > mt3Ll.getLastModifiedTime()) {
        runProcess(ProcessBuilder(
            "clang++",
            *cxx_llvm_flags,
            "-o",
            mt3Ll.absolutePathString(),
            mt3C.absolutePathString()
        ))
    }

    println("mt3lib.ll: ${mt3Ll.absolutePathString()}")
}

fun runProcess(processBuilder: ProcessBuilder) {
    val process = processBuilder.redirectErrorStream(true).start()
    process.inputStream.copyTo(System.err)
}

val mt3C: Path = Path.of("../src/main/resources/mt3lib.cxx")
val mt3Ll: Path = Path.of("./mt3lib.ll")

// -nostdlib++ -nostdinc++
val linker_flags = arrayOf("-static-libstdc++")
val cxx_flags = arrayOf("-Os", "-fno-rtti", "-fno-exceptions")

val cxx_llvm_flags = arrayOf(*cxx_flags, "-S", "-emit-llvm")