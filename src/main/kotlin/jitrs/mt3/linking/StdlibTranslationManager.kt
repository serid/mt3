package jitrs.mt3.linking

import jitrs.util.myAssert
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/**
 * Ensures that mt3lib.cxx is translated to mt3lib.o
 */
object StdlibTranslationManager {
    private val lock = Any()

    // Future will either complete immediately, or when the stdlib finishes compiling
    private var isStdlibReady: CompletableFuture<Unit>? = null

    // Cxx flags should not change during the lifetime of this JVM instance
    private var storedCxxFlags: Array<String>? = null

    // // TODO: stdlib should be compiled in a Gradle task together with main Kotlin source code
    fun ensureStdlibIsReady(cxxFlags: Array<String>): CompletableFuture<Unit> {
        synchronized(lock) {
            // Here we assume that the command or the dependencies don't change during lifetime of this JVM instance
            if (isStdlibReady != null)
                return isStdlibReady!!
            isStdlibReady = CompletableFuture()

            if (storedCxxFlags != null)
                myAssert(storedCxxFlags.contentEquals(cxxFlags))
            else
                storedCxxFlags = cxxFlags

            val cmd = arrayOf(
                "clang++",
                "-c",
                *cxxFlags,
                mt3LibCxx.absolutePathString(),
                "-o",
                mt3LibO.absolutePathString()
            )
            val serializedCommand = cmd.joinToString(" ")

            // Skip recompilation if mt3lib.o already exists, is newer than its dependencies,
            // and the previous compilation command should be equal to the current one
            if (mt3LibO.exists() &&
                mt3LibPreviousCommand.exists() &&
                getHighestModificationTime(mt3LibSrc) <= mt3LibO.getLastModifiedTime() &&
                serializedCommand == Files.readString(mt3LibPreviousCommand)
            )
                return isStdlibReady!!.apply { complete(Unit) }

            thread {
                // Translate stdlib
                println("info: translating mt3lib.cxx to mt3lib.o")

                val p = startProcessWithStdout(*cmd)
                // Stash the command
                Files.writeString(mt3LibPreviousCommand, serializedCommand)
                p.waitFor()
                if (p.exitValue() == 0)
                    isStdlibReady!!.complete(Unit)
                else
                    isStdlibReady!!.completeExceptionally(RuntimeException("stdlib translation failed"))
            }
            return isStdlibReady!!
        }
    }
}

val mt3LibSrc: Path = Path.of("./src/main/resources/")
val mt3LibCxx: Path = Path.of("./src/main/resources/mt3lib.cxx")
val mt3LibO: Path = Path.of("./workdir/mt3lib.o")
val mt3LibPreviousCommand: Path = Path.of("./workdir/mt3lib.command.txt")