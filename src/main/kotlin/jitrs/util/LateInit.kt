package jitrs.util

// Copied from https://github.com/serid/jlinks/blob/master/src/main/kotlin/jitrs/util/LateInit.kt
/**
 * Late initialization without mutable access.
 * Useful for making cyclic immutable datastructures.
 */
class LateInit<T : Any /* Require to be non-nullable */>(
    private var x: T? = null
) {
    fun get(): T =
        if (x == null)
            throw RuntimeException("value not set yet")
        else
            x!!

    fun resolve(value: T) =
        if (x != null)
            throw RuntimeException("value already set")
        else
            x = value
}