; This file is included by the compiler in every codegen unit,
; it contains declarations of runtime library functions

target triple = "x86_64-unknown-linux-gnu"

%GCObject = type opaque
%MT3Value = type opaque

declare i8* @mt3_check_function_call(%MT3Value*, i8)
declare void @mt3_add_gc_root(%GCObject*)

declare %MT3Value* @mt3_new_bool(i1)
declare %MT3Value* @mt3_new_int(i64)
declare %MT3Value* @mt3_new_string(i8*)
declare %MT3Value* @mt3_new_function(i8, i8*)

@mt3_none_singleton = external global %MT3Value*, align 8
@mt3_false_singleton = external global %MT3Value*, align 8
@mt3_true_singleton = external global %MT3Value*, align 8

@mt3_stdlib_print = external global %MT3Value*, align 8
@mt3_stdlib_equality = external global %MT3Value*, align 8
@mt3_stdlib_plus = external global %MT3Value*, align 8