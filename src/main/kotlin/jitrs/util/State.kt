package jitrs.util

@JvmInline
value class State<T, S>(val f: (S) -> Pair<T, S>) {
    fun run(s: S): Pair<T, S> = f(s)

    fun <U> bind(k: (T) -> State<U, S>): State<U, S> = bind(this, k)

    companion object {
        @JvmStatic
        fun <T, S> pure(a: T): State<T, S> = State { s -> a to s }

        @JvmStatic
        fun <T, U, S> bind(o: State<T, S>, k: (T) -> State<U, S>): State<U, S> {
            return State { s0 ->
                val (r1, s1) = o.f(s0)
                val o2 = k(r1)
                o2.f(s1)
            }
        }
    }
}

object stateMonad : Monad() {
    override fun <T> pure(a: T): State<T, Any> = State.pure(a)

    @Suppress("UNCHECKED_CAST")
    override fun <T, U> bind(o: Any, k: (T) -> Any): State<U, Any> {
        o as State<T, Any>
        return State.bind(o, k as (T) -> State<U, Any>)
    }
}