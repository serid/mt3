package jitrs.util

sealed class Resolution<T, E> {
    data class Ok<T, E>(val ok: T) : Resolution<T, E>()
    data class Err<T, E>(val err: E) : Resolution<T, E>()

    companion object {
        @JvmStatic
        fun <T, E> pure(a: T): Resolution<T, E> = Ok(a)

        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun <T, U, E> bind(o: Resolution<T, E>, k: (T) -> Resolution<U, E>): Resolution<U, E> {
            return when(o) {
                is Ok<T, E> -> k(o.ok)
                is Err<T, E> -> o as Resolution<U, E>
            }
        }
    }
}