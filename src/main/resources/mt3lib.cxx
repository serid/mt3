// MT3 runtime library implemented in C++

#include <stdlib.h>
#include <stdio.h>
#include <vector>
#include <string>
#include <type_traits>

#include "util.h"
#include "gc.hxx"

static const u8 NONE_TAG = 1;
static const u8 BOOL_TAG = 2;
static const u8 INT_TAG = 3;
static const u8 ARRAY_TAG = 4;
static const u8 STRING_TAG = 5;
static const u8 FUNCTION_TAG = 6;
static const u8 OBJECT_TAG = 7;
struct MT3Value : public GCObject {
    u8 tag;

    MT3Value(u8 tag) : tag(tag) {}
};
struct MT3None : public MT3Value {
    MT3None() : MT3Value(NONE_TAG) {}
};
struct MT3Bool : public MT3Value {
    // true and false are singletons, no need to store the value

    MT3Bool() : MT3Value(BOOL_TAG) {}
};
struct MT3Int : public MT3Value {
    i64 value;

    MT3Int(i64 value) : MT3Value(INT_TAG), value(value) {}
};
struct MT3Array : public MT3Value {
    std::vector<MT3Value*> values;

    MT3Array(std::vector<MT3Value*>&& values) : MT3Value(ARRAY_TAG), values(values) {}

    void visit() override {
        if (is_marked()) return;
        set_mark();
        for (const auto& x : values)
            x->visit();
    }
};
struct MT3String : public MT3Value {
    std::string data;

    MT3String(std::string&& data) : MT3Value(STRING_TAG), data(data) {}
};
struct MT3Function : public MT3Value {
    // this function pointer has type like (MT3Value* fun(MT3Value*, MT3Value*)).
    // It would require monomorphing call1, call2, call3 in LLVM IR
    void* fun;

    // TODO: toplevel MT3 functions and native functions don't need closures,
    // maybe this class should be split in two
    std::vector<MT3Value*> closure;

    // Formal number of parameters expected by "fun". Should match actual number of arguments in "args"
    // when funptr is called
    u8 parameter_num;

    MT3Function(u8 parameter_num, void* fun, std::vector<MT3Value*>&& closure) :
        MT3Value(FUNCTION_TAG), parameter_num(parameter_num), fun(fun), closure(std::move(closure)) {}

    MT3Function(u8 parameter_num, void* fun) :
        MT3Function(parameter_num, fun, {}) {}

    void visit() override {
        if (is_marked()) return;
        set_mark();
        for (const auto& x : closure)
            x->visit();
    }
};

[[noreturn]]
static void panic(const char* message) {
    puts(message);
    exit(1);
}

template<typename T, typename... Args>
T* gc_malloc(Args... args) {
    return new T(args...);
//     return static_cast<T*>(malloc(sizeof(T)));
}

extern "C" MT3Value* mt3_none_singleton = gc_malloc<MT3None>();
extern "C" MT3Value* mt3_false_singleton = gc_malloc<MT3Bool>();
extern "C" MT3Value* mt3_true_singleton = gc_malloc<MT3Bool>();

extern "C" MT3Value* mt3_new_bool(bool x) {
    return x ? mt3_true_singleton : mt3_false_singleton;
}

extern "C" MT3Value* mt3_new_int(i64 x) {
    return gc_malloc<MT3Int>(x);
}

extern "C" MT3Value* mt3_new_string(char* s) {
    return gc_malloc<MT3String>(s);
}

extern "C" MT3Value* mt3_new_function(u8 parameter_num, void* fun) {
    return gc_malloc<MT3Function>(parameter_num, fun);
}

/// Check that the value is an MT3Function, match its number of arguments and return its function pointer.
extern "C" void* mt3_check_function_call(MT3Value* function, u8 arg_num) {
    if (function->tag != FUNCTION_TAG)
        panic("'function' is not a function");
    auto casted_function = static_cast<MT3Function*>(function);
    if (casted_function->parameter_num != arg_num)
        panic("wrong number of arguments");
    return reinterpret_cast<void*>(casted_function->fun);
}

extern "C" bool mt3_is_false(MT3Value* v) {
    if (v->tag != BOOL_TAG)
        panic("expected a bool");
    return v == mt3_false_singleton;
}

extern "C" bool mt3_is_true(MT3Value* v) {
    return !mt3_is_false(v);
}

/// This function will be reified in Codegen.kt
extern "C" MT3Value* mt3_builtin_call0(MT3Value* function);

// example of a monomorphic call sequence
MT3Value* mt3_builtin_call2_example(MT3Value* function, MT3Value* arg1, MT3Value* arg2) {
    auto fun = reinterpret_cast<MT3Value*(*)(MT3Value*, MT3Value*)>(mt3_check_function_call(function, 2));
    return fun(arg1, arg2);
}

static MT3Value* mt3_print_impl(MT3Value* arg) {
    if (arg->tag == BOOL_TAG) {
        fputs(mt3_is_true(arg) ? "true" : "false", stdout);
    } else if (arg->tag == INT_TAG) {
        printf("%li", static_cast<MT3Int*>(arg)->value);
    } else if (arg->tag == STRING_TAG) {
        fputs(static_cast<MT3String*>(arg)->data.data(), stdout);
    } else {
        panic("Unsupported types for builtin_print");
    }
    return mt3_none_singleton;
}

// operator==
static MT3Value* mt3_equality_impl(MT3Value* a, MT3Value* b) {
    if (a->tag == BOOL_TAG && b->tag == BOOL_TAG) {
        return mt3_new_bool(a == b);
    } else if (a->tag == INT_TAG && b->tag == INT_TAG) {
        bool eq = static_cast<MT3Int*>(a)->value == static_cast<MT3Int*>(b)->value;
        return mt3_new_bool(eq);
    } else if (a->tag == STRING_TAG && b->tag == STRING_TAG) {
        bool eq = static_cast<MT3String*>(a)->data == static_cast<MT3String*>(b)->data;
        return mt3_new_bool(eq);
    }
    panic("Unsupported types for builtin_equality");
}

// operator+
static MT3Value* mt3_plus_impl(MT3Value* a, MT3Value* b) {
    if (a->tag == INT_TAG && b->tag == INT_TAG) {
        i64 sum = static_cast<MT3Int*>(a)->value + static_cast<MT3Int*>(b)->value;
        return mt3_new_int(sum);
    } else if (a->tag == STRING_TAG && b->tag == STRING_TAG) {
        MT3String* result = gc_malloc<MT3String>("");
        result->data += static_cast<MT3String*>(a)->data;
        result->data += static_cast<MT3String*>(b)->data;
        return result;
    }
    panic("Unsupported types for builtin_plus");
}

// operator-*/
template<auto f>
MT3Value* generic_int_function(MT3Value* a, MT3Value* b, const char* error_message) {
    if (a->tag == INT_TAG && b->tag == INT_TAG) {
        i64 result = f(static_cast<MT3Int*>(a)->value, static_cast<MT3Int*>(b)->value);
        return mt3_new_int(result);
    }
    panic(error_message);
}

static MT3Value* mt3_minus_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return x - y; }>(a, b, "Unsupported types for builtin_minus");
}

static MT3Value* mt3_mul_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return x * y; }>(a, b, "Unsupported types for builtin_mul");
}

static MT3Value* mt3_div_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return x / y; }>(a, b, "Unsupported types for builtin_div");
}

// Here follow native global variables with MT3Value-wrappers around stdlib functions
extern "C" MT3Value* mt3_stdlib_print = gc_malloc<MT3Function>(1, reinterpret_cast<void*>(mt3_print_impl));
extern "C" MT3Value* mt3_stdlib_equality = gc_malloc<MT3Function>(2, reinterpret_cast<void*>(mt3_equality_impl));
extern "C" MT3Value* mt3_stdlib_plus = gc_malloc<MT3Function>(2, reinterpret_cast<void*>(mt3_plus_impl));
extern "C" MT3Value* mt3_stdlib_minus = gc_malloc<MT3Function>(2, reinterpret_cast<void*>(mt3_minus_impl));
extern "C" MT3Value* mt3_stdlib_mul = gc_malloc<MT3Function>(2, reinterpret_cast<void*>(mt3_mul_impl));
extern "C" MT3Value* mt3_stdlib_div = gc_malloc<MT3Function>(2, reinterpret_cast<void*>(mt3_div_impl));

// extern "C" MT3Value* mt3_mt3lib_gc_roots[] = {mt3_print, mt3_plus};
// extern "C" const size_t mt3_mt3lib_gc_roots_size = std::extent<decltype(mt3_mt3lib_gc_roots)>::value;

static void add_builtins_as_gc_roots() {
    mt3_add_gc_root(mt3_stdlib_print);
    mt3_add_gc_root(mt3_stdlib_equality);
    mt3_add_gc_root(mt3_stdlib_plus);
    mt3_add_gc_root(mt3_stdlib_minus);
    mt3_add_gc_root(mt3_stdlib_mul);
    mt3_add_gc_root(mt3_stdlib_div);
}

static void mt3_stdlib_init() {
    add_builtins_as_gc_roots();
}

// Guest main function generated by the mt3 compiler using LLVM
extern "C" MT3Value* mt3_main;
extern "C" void mt3_mainmod_init();

// This function is called from start.o or whatever
int main() {
    mt3_stdlib_init();
    mt3_mainmod_init();

    mt3_builtin_call0(mt3_main);
}