package jitrs.util

inline fun <reified T> Any.isInstance(): Boolean = this is T

inline fun <reified T> Any.cast(): T = this as T

inline fun <reified T> Sequence<T>.priceyToArray(): Array<T> = this.toList().toTypedArray()

fun myAssert(b: Boolean) {
    if (!b)
        throw RuntimeException("assertion failed")
}