package jitrs.mt3

import java.lang.StringBuilder

// Assumes clang version 11.1.0

class Lowering {
    val result = StringBuilder()

    fun toLlvm(program: Program): String {
        visitProgram(program)
        return result.toString()
    }

    private fun visitProgram(program: Program): Unit = program.toplevels.forEach {
        visitToplevel(it)
    }

    private fun visitToplevel(toplevel: Toplevel) {
        toplevel.apply {
            when (this) {
                is Toplevel.Fun -> {
                    result.append("define ptr @$name() {")

                    result.append("}")
                }
            }
        }
    }
}