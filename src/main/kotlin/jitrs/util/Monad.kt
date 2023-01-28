package jitrs.util

/**
 * A bundle of functions needed for an M to be called a monad.
 */
//abstract class Monad1<M> {
//    abstract fun <T> pure(a: T): M<T>
//    abstract fun <T, U> bind(o: M<T>, k: (T) -> M<U>): M<U>
//}

abstract class Monad {
    abstract fun <T> pure(a: T): Any
    abstract fun <T, U> bind(o: Any, k: (T) -> Any): Any
}