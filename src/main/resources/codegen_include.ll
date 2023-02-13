; This file is included by the compiler in every codegen unit,
; it contains declarations of runtime library functions

target triple = "x86_64-unknown-linux-gnu"

%MT3Value = type opaque

declare void @mt3_stdlib_init()
declare i8* @mt3_check_function_call(%MT3Value*, i8)
declare void @mt3_add_gc_root(%MT3Value*)

declare %MT3Value* @mt3_new_int(i64)
declare %MT3Value* @mt3_new_string(i8*)
declare %MT3Value* @mt3_new_function(i8, i8*)

@mt3_stdlib_print = external global %MT3Value*, align 8
@mt3_stdlib_plus = external global %MT3Value*, align 8