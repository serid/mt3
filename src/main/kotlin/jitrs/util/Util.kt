package jitrs.util

inline fun <reified T> Any.isInstance(): Boolean = this is T

inline fun <reified T> Any.cast(): T = this as T

inline fun <reified T> Sequence<T>.priceyToArray(): Array<T> = this.toList().toTypedArray()

//inline fun <reified T> Sequence<T>.cheapToArray(): Array<T> {
//    val list = this.toList()
//    list as ArrayList<T>
//
//    @Suppress("UNCHECKED_CAST")
//    return unsafe.getObject(list, arrayListElementDataFieldOffset.value) as Array<T>
//}
//
//val arrayListElementDataFieldOffset = lazy {
//    unsafe.objectFieldOffset(arrayListElementDataField.value)
//}
//
//val arrayListElementDataField = lazy {
//    val field = ArrayList::class.java.getDeclaredField("elementData")
//    field.isAccessible = true
//    field
//}
//
//val unsafe = Unsafe.getUnsafe()

inline fun <T> doHere(f: () -> T): T = f()

fun myAssert(b: Boolean) {
    if (!b)
        throw RuntimeException("assertion failed")
}

fun myAssertEqual(a: Any, b: Any) {
    if (a != b)
        throw RuntimeException("equality assertion failed: '$a' and '$b'")
}

fun countFrom(from: Int): Sequence<Int> = generateSequence(from) { it + 1 }

inline fun logTime(msg: String, f: () -> Unit) {
    val before = System.nanoTime()
    f()
    println("$msg ${(System.nanoTime() - before) / 1_000_000} ms")
}

//fun StringBuilder.appendIndentedLine(s: String): StringBuilder = this.appendLine("    $s")