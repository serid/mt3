package jitrs.mt3

import jitrs.util.Monad
import jitrs.util.Resolution
import jitrs.util.State

// This is the 6th time I port a parser combinator library. Previous languages: Haskell, Rust, C++, Python, JS

@JvmInline
value class Parsec<T, E, S>(val st: State<Resolution<T, E>, Pair<String, S>>) {
    companion object {
        @JvmStatic
        fun <T, E, S> pure(a: T): Parsec<T, E, S> = Parsec(State.pure(Resolution.Ok(a)))

        @JvmStatic
        fun <T, U, E, S> bind(o: Parsec<T, E, S>, k: (T) -> Parsec<U, E, S>): Parsec<U, E, S> {
            return Parsec(State { (str, s) ->
                TODO()
            })
        }
    }
}

object parsecMonad : Monad() {
    override fun <T> pure(a: T): Parsec<T, Any, Any> = Parsec.pure(a)

    override fun <T, U> bind(o: Any, k: (T) -> Any): Parsec<U, Any, Any> = Parsec.bind(o, k)
}