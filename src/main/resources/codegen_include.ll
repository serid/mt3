; codegen_include.ll
; This file is included by the compiler in every codegen unit,
; it contains declarations of runtime library functions

target triple = "x86_64-unknown-linux-gnu"

%MT3Value = type opaque

declare void @mt3_stdlib_init()
;extern "C" void* builtin_call_cxx(void* function, u8 arg_num, void** args);
declare %MT3Value* @mt3_builtin_call(%MT3Value*, i8, %MT3Value**)

;; When module is initialized, this will point to an MT3Function object
;@mt3_main = global %MT3Value* null, align 8

;define i32 @main() {
;    tail call void @mt3_stdlib_init()

;    ; TODO: call module init

;    ; Call mt3_main
;    %1 = load %MT3Value*, %MT3Value** @mt3_main, align 8
;    tail call %MT3Value* @mt3_builtin_call(%MT3Value* %1, i8 0, %MT3Value** null)

;    ret i32 0
;}