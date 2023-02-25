@file:Suppress("ArrayInDataClass")

package jitrs.mt3

import jitrs.util.cast
import jitrs.util.priceyToArray

fun programfromSExprs(es: Array<MT3SExpr>): Program = Program(es.map(::toplevelFromSExpr).toTypedArray())

fun toplevelFromSExpr(e: MT3SExpr): Toplevel {
    e as SExpr.Node
    return when (e.subexprs[0].cast<MT3Leaf>().token.getIdent()) {
        "fun" -> Toplevel.Fun(
            e.subexprs[1].cast<MT3Leaf>().token.getIdent(),
            e.subexprs[2].cast<MT3Node>().subexprs.map { it.cast<MT3Leaf>().token.getIdent() }.toTypedArray(),
            e.subexprs.asSequence().drop(3).map(::stmtFromSExpr).priceyToArray()
        )

        else -> throw RuntimeException()
    }
}

fun stmtFromSExpr(e: MT3SExpr): Stmt = Stmt.ExprStmt(exprFromSExpr(e))

fun exprFromSExpr(e: MT3SExpr): Expr = when {
    e is MT3Leaf && e.token.id == intT -> Expr.IntConst(e.token.getInt())
    e is MT3Leaf && e.token.id == stringT -> Expr.StringConst(e.token.getString())
    e is MT3Leaf && e.token.id == identT -> Expr.GlobalVarUse(e.token.getIdent())
    e is MT3Node -> Expr.Call(
        exprFromSExpr(e.subexprs[0]),
        e.subexprs.asSequence().drop(1).map(::exprFromSExpr).priceyToArray()
    )

    else -> throw RuntimeException()
}

typealias MT3SExpr = SExpr<Token>
typealias MT3Node = SExpr.Node<Token>
typealias MT3Leaf = SExpr.Leaf<Token>

data class Program(val toplevels: Array<Toplevel>)

sealed class Toplevel {
    data class Fun(
        val name: String,
        val params: Array<String>,
        val body: Array<Stmt>
    ) : Toplevel() {
        fun arity(): Int = params.size
    }
}

sealed class Stmt {
    data class ExprStmt(val e: Expr) : Stmt()
}

sealed class Expr {
    data class IntConst(val int: Int) : Expr()

    data class StringConst(val string: String) : Expr()

    data class GlobalVarUse(val name: String) : Expr()

    data class Call(val func: Expr, val args: Array<Expr>) : Expr() {
        fun arity(): Int = args.size
    }
}