package jitrs.util

class ObjectPool<T : Resettable>(private val factory: () -> T) : Resettable {
    private val freeList = ArrayList<T>()

    fun borrow(): T {
        if (freeList.isEmpty())
            return factory()
        return freeList.removeLast()
    }

    @Suppress("FunctionName")
    fun zurückkehren(returned: T) {
        returned.reset()
        freeList.add(returned)
    }

    inline fun withBorrowed(action: (T) -> Unit) {
        val o = borrow()
        action(o)
        zurückkehren(o)
    }

    override fun reset() {
        freeList.clear()
    }
}

/**
 * Classes implementing this interface can be reset to a default value
 */
interface Resettable {
    fun reset()
}