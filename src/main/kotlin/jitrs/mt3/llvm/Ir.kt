package jitrs.mt3.llvm

/**
 * ExprFactory takes an index of current free variable, returns an LLVM IR expression and a new free index.
 */
@JvmInline
value class ExprFactory(val factory: (localVariableIndex: Int) -> Pair<String, Int>)