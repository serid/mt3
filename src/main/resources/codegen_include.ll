; This file is included by the compiler in every codegen unit,
; it contains declarations of runtime library functions

target triple = "x86_64-unknown-linux-gnu"

%Traceable = type opaque
%MT3Value = type opaque

declare void @mt3_add_gc_root(%Traceable*)

declare %MT3Value* @mt3_new_bool(i1)
declare %MT3Value* @mt3_new_int(i64)
declare %MT3Value* @mt3_new_string(i8*)
declare %MT3Value* @mt3_new_function(i8, i8*)

declare i8* @mt3_check_function_call(%MT3Value*, i8)
declare i1 @mt3_is_false(%MT3Value*)
declare i1 @mt3_is_true(%MT3Value*)

; parameters are: object, string with field name, new field value
declare void @mt3_set_field(%MT3Value*, %MT3Value*, %MT3Value*)
declare %MT3Value* @mt3_get_field(%MT3Value*, %MT3Value*)

; parameters: string with method name, object
declare i8* @mt3_get_method_funptr(%MT3Value*, %MT3Value*, i8)