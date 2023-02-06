package jitrs.util

class PeekableIterator<T: Any>(private val iter: Iterator<T>) : Iterator<T> {
    private var stashed: T? = null

    override fun hasNext(): Boolean = stashed != null || iter.hasNext()

    override fun next(): T = when (stashed) {
        null -> iter.next()
        else -> {
            val result = stashed as T
            stashed = null
            result
        }
    }

    fun peek(): T = when (stashed) {
        null -> {
            stashed = iter.next()
            stashed as T
        }
        else -> stashed as T
    }
}