// MT3 runtime library implemented in C++

#include <stdlib.h>
#include <stdio.h>
#include <optional>
#include <vector>
#include <unordered_map>
#include <string>
//#include <utility>
#include <type_traits>

#include "util.h"
#include "gc.hxx"

//// CONFIG

// Select how objects are represented in memory
#define MT3_OBJECTS_DICTIONARY 1
#define MT3_OBJECTS_HIDDENCLASS 2

#define MT3_OBJECTS_LAYOUT MT3_OBJECTS_DICTIONARY

// Select whether inline caches should be used
#define MT3_OBJECTS_IC 0

// IC can only be used with hiddenclasses
static_assert(!(MT3_OBJECTS_IC && MT3_OBJECTS_LAYOUT != MT3_OBJECTS_HIDDENCLASS));

//// MT3VALUE

#if MT3_OBJECTS_LAYOUT == MT3_OBJECTS_HIDDENCLASS
struct HiddenClass;
namespace {
    extern HiddenClass* hiddenclasses_root;
}
#endif

#define DOWNCAST_TEMPLATE(type_name, type_tag, error_message) \
    static type_name* downcast_from(MT3Value* value) { \
        my_assert(value->tag == type_tag, error_message); \
        return static_cast<type_name*>(value); \
    }

static const u8 NONE_TAG = 1;
static const u8 BOOL_TAG = 2;
static const u8 INT_TAG = 3;
static const u8 ARRAY_TAG = 4;
static const u8 STRING_TAG = 5;
static const u8 FUNCTION_TAG = 6;
static const u8 OBJECT_TAG = 7;
struct MT3Value : public GCObject {
    const u8 tag;

    MT3Value(u8 tag) : tag(tag) {}
};
struct MT3None : public MT3Value {
    MT3None() : MT3Value(NONE_TAG) {}

    DOWNCAST_TEMPLATE(MT3None, NONE_TAG, "expected a none")
};
struct MT3Bool : public MT3Value {
    // true and false are singletons, no need to store the value

    MT3Bool() : MT3Value(BOOL_TAG) {}

    DOWNCAST_TEMPLATE(MT3Bool, BOOL_TAG, "expected a bool")
};
struct MT3Int : public MT3Value {
    const i64 value;

    MT3Int(i64 value) : MT3Value(INT_TAG), value(value) {}

    DOWNCAST_TEMPLATE(MT3Int, INT_TAG, "expected an int")
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

    DOWNCAST_TEMPLATE(MT3Array, ARRAY_TAG, "expected an array")
};
struct MT3String : public MT3Value {
    const std::string data;
    std::optional<size_t> cached_hash{};

    MT3String(std::string&& data) : MT3Value(STRING_TAG), data(data) {}

    MT3String(const std::string& data) : MT3Value(STRING_TAG), data(data) {}

    bool is_equal(MT3String* other) {
        return this == other || this->data == other->data;
    }

    size_t hash() {
        if (!cached_hash.has_value())
            cached_hash = std::hash<std::string>()(data);
        return cached_hash.value();
    }

    struct Hasher {
        size_t operator()(MT3String* const& a) const {
            return a->hash();
        }
    };

    struct Equalizer {
        bool operator()(MT3String* const& a, MT3String* const& b) const {
            return a->is_equal(b);
        }
    };

    DOWNCAST_TEMPLATE(MT3String, STRING_TAG, "expected a string")
};
template<typename T>
using MT3StringMap = std::unordered_map<MT3String*, T, MT3String::Hasher, MT3String::Equalizer>;
struct MT3Function : public MT3Value {
    // this function pointer has type like (MT3Value* fun(MT3Value*, MT3Value*)).
    // It would require monomorphing call1, call2, call3 in LLVM IR
    void* fun;

    // TODO: toplevel MT3 functions and native functions don't need closures,
    // maybe this class should be split in two
    std::vector<MT3Value*> closure;

    // Formal number of parameters expected by "fun". Should match actual number of arguments in "args"
    // when funptr is called
    const u8 parameter_num;

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

    DOWNCAST_TEMPLATE(MT3Function, FUNCTION_TAG, "'function' is not a function")
};
struct MT3Object : public MT3Value {
#if MT3_OBJECTS_LAYOUT == MT3_OBJECTS_DICTIONARY
    MT3StringMap<MT3Value*> fields {};
#else
    HiddenClass* hc = hiddenclasses_root;
    std::vector<MT3Value*> attrs {};
#endif

    MT3Object() : MT3Value(OBJECT_TAG) {}

    void visit() override {
        if (is_marked()) return;
        set_mark();
#if MT3_OBJECTS_LAYOUT == MT3_OBJECTS_DICTIONARY
        for (const auto& [key, value] : fields) {
            key->visit();
            value->visit();
        }
#else
        hc->visit();
        for (const auto& attr : attrs) {
            attr->visit();
        }
#endif
    }

    DOWNCAST_TEMPLATE(MT3Object, OBJECT_TAG, "expected an object")
};
#undef DOWNCAST_TEMPLATE

template<typename T, typename... Args>
T* gc_malloc(Args... args) {
    return new T(args...);
//     return static_cast<T*>(malloc(sizeof(T)));
}

#if MT3_OBJECTS_LAYOUT == MT3_OBJECTS_HIDDENCLASS
// A hidden class maps field names to indices in the attribute array
// https://v8.dev/docs/hidden-classes
struct HiddenClass {
    const MT3StringMap<u8> map;

    // Map of hidden classes derived from this one by adding fields (transitioning)
    MT3StringMap<HiddenClass*> derived;

    HiddenClass(MT3StringMap<u8>&& map,
        const MT3StringMap<HiddenClass*>&) : map(map), derived(derived) {}

    void visit() {
        for (const auto& [key, _value] : map)
            key->visit();
        for (const auto& [key, value] : derived) {
            key->visit();
            value->visit();
        }
    }
};

namespace {
    // The empty hidden class
    HiddenClass* hiddenclasses_root = new HiddenClass({}, {});
}
#endif

//// RUNTIME LIBRARY

extern "C" MT3Value* mt3_stdlib_none = gc_malloc<MT3None>();
extern "C" MT3Value* mt3_stdlib_false = gc_malloc<MT3Bool>();
extern "C" MT3Value* mt3_stdlib_true = gc_malloc<MT3Bool>();

extern "C" MT3Value* mt3_new_bool(bool x) {
    return x ? mt3_stdlib_true : mt3_stdlib_false;
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

extern "C" void mt3_set_field(MT3Value* scrutinee, MT3Value* field_name, MT3Value* rhs) {
    auto scrutinee1 = MT3Object::downcast_from(scrutinee);
    auto field_name1 = MT3String::downcast_from(field_name);

#if MT3_OBJECTS_LAYOUT == MT3_OBJECTS_DICTIONARY
    scrutinee1->fields.insert_or_assign(field_name1, rhs);
#endif
}

extern "C" MT3Value* mt3_get_field(MT3Value* scrutinee, MT3Value* field_name) {
    auto scrutinee1 = MT3Object::downcast_from(scrutinee);
    auto field_name1 = MT3String::downcast_from(field_name);

#if MT3_OBJECTS_LAYOUT == MT3_OBJECTS_DICTIONARY
    MT3Value* result = scrutinee1->fields[field_name1];

    // operator[] inserts a default nullptr and returns it if key is not present
    my_assert(result != nullptr, "field not present");

    return result;
#endif
}

/// Check that the value is an MT3Function, match its number of arguments and return its function pointer.
extern "C" void* mt3_check_function_call(MT3Value* function, u8 arg_num) {
    auto casted_function = MT3Function::downcast_from(function);
    my_assert(casted_function->parameter_num == arg_num, "wrong number of arguments");
    return reinterpret_cast<void*>(casted_function->fun);
}

extern "C" bool mt3_is_false(MT3Value* value) {
    MT3Bool::downcast_from(value);
    return value == mt3_stdlib_false;
}

extern "C" bool mt3_is_true(MT3Value* value) {
    return !mt3_is_false(value);
}

/// This function will be reified in Codegen.kt
extern "C" MT3Value* mt3_builtin_call0(MT3Value* function);

// example of a monomorphic call sequence
MT3Value* mt3_builtin_call2_example(MT3Value* function, MT3Value* arg1, MT3Value* arg2) {
    auto fun = reinterpret_cast<MT3Value*(*)(MT3Value*, MT3Value*)>(mt3_check_function_call(function, 2));
    return fun(arg1, arg2);
}

//// STANDARD LIBRARY

static MT3Value* mt3_print_impl(MT3Value* arg) {
    if (arg->tag == BOOL_TAG) {
        fputs(mt3_is_true(arg) ? "true" : "false", stdout);
    } else if (arg->tag == INT_TAG) {
        printf("%li", static_cast<MT3Int*>(arg)->value);
    } else if (arg->tag == STRING_TAG) {
        fputs(static_cast<MT3String*>(arg)->data.data(), stdout);
    } else {
        panic("unsupported types for builtin_print");
    }
    return mt3_stdlib_none;
}

static MT3Value* mt3_to_string_impl(MT3Value* arg) {
    if (arg->tag == BOOL_TAG) {
        return gc_malloc<MT3String>(mt3_is_true(arg) ? "true" : "false");
    } else if (arg->tag == INT_TAG) {
        return gc_malloc<MT3String>(std::to_string(static_cast<MT3Int*>(arg)->value));
    } else if (arg->tag == STRING_TAG) {
        return arg;
    }
    panic("unsupported types for builtin_to_string");
}

// operator!
static MT3Value* mt3_logical_not_impl(MT3Value* arg) {
    if (arg->tag == BOOL_TAG) {
        return mt3_new_bool(!mt3_is_true(arg));
    }
    panic("unsupported types for builtin_logical_not");
}

// operator== and !=
static bool mt3_equality_cxx(MT3Value* a, MT3Value* b, const char* error_message) {
    if (a->tag == BOOL_TAG && b->tag == BOOL_TAG) {
        return a == b;
    } else if (a->tag == INT_TAG && b->tag == INT_TAG) {
        return static_cast<MT3Int*>(a)->value == static_cast<MT3Int*>(b)->value;
    } else if (a->tag == STRING_TAG && b->tag == STRING_TAG) {
        return static_cast<MT3String*>(a)->data == static_cast<MT3String*>(b)->data;
    }
    panic(error_message);
}

static MT3Value* mt3_equality_impl(MT3Value* a, MT3Value* b) {
    return mt3_new_bool(mt3_equality_cxx(a, b, "unsupported types for builtin_equality"));
}

static MT3Value* mt3_inequality_impl(MT3Value* a, MT3Value* b) {
    return mt3_new_bool(!mt3_equality_cxx(a, b, "unsupported types for builtin_inequality"));
}

// operator+
static MT3Value* mt3_plus_impl(MT3Value* a, MT3Value* b) {
    if (a->tag == INT_TAG && b->tag == INT_TAG) {
        i64 sum = static_cast<MT3Int*>(a)->value + static_cast<MT3Int*>(b)->value;
        return mt3_new_int(sum);
    } else if (a->tag == STRING_TAG || b->tag == STRING_TAG) {
        // If one of the arguments is a string, convert both to string and concatenate
        std::string result {};
        result += static_cast<MT3String*>(mt3_to_string_impl(a))->data;
        result += static_cast<MT3String*>(mt3_to_string_impl(b))->data;
        return gc_malloc<MT3String>(std::move(result));
    }
    panic("unsupported types for builtin_plus");
}

// operator-*/
template<auto f>
MT3Value* generic_int_function(MT3Value* a, MT3Value* b, const char* error_message) {
    if (a->tag == INT_TAG && b->tag == INT_TAG) {
        return f(static_cast<MT3Int*>(a)->value, static_cast<MT3Int*>(b)->value);
    }
    panic(error_message);
}

static MT3Value* mt3_minus_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return mt3_new_int(x - y); }>(a, b, "unsupported types for builtin_minus");
}

static MT3Value* mt3_mul_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return mt3_new_int(x * y); }>(a, b, "unsupported types for builtin_mul");
}

static MT3Value* mt3_div_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return mt3_new_int(x / y); }>(a, b, "unsupported types for builtin_div");
}

static MT3Value* mt3_less_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return mt3_new_bool(x < y); }>(a, b, "unsupported types for builtin_less");
}

static MT3Value* mt3_lax_less_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return mt3_new_bool(x <= y); }>(a, b, "unsupported types for builtin_lax_less");
}

static MT3Value* mt3_greater_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return mt3_new_bool(x > y); }>(a, b, "unsupported types for builtin_greater");
}

static MT3Value* mt3_lax_greater_impl(MT3Value* a, MT3Value* b) {
    return generic_int_function<[](i64 x, i64 y) { return mt3_new_bool(x >= y); }>(a, b, "unsupported types for builtin_lax_greater");
}

static MT3Value* mt3_new_impl() {
    return gc_malloc<MT3Object>();
}

// Here follow native global variables with MT3Value-wrappers around stdlib functions
#define DECLARE_STDLIB_GLOBAL(name, arity) \
extern "C" MT3Value* mt3_stdlib_##name = gc_malloc<MT3Function>(arity, reinterpret_cast<void*>(mt3_##name##_impl));
DECLARE_STDLIB_GLOBAL(logical_not, 1)
DECLARE_STDLIB_GLOBAL(print, 1)
DECLARE_STDLIB_GLOBAL(equality, 2)
DECLARE_STDLIB_GLOBAL(inequality, 2)
DECLARE_STDLIB_GLOBAL(plus, 2)
DECLARE_STDLIB_GLOBAL(minus, 2)
DECLARE_STDLIB_GLOBAL(mul, 2)
DECLARE_STDLIB_GLOBAL(div, 2)
DECLARE_STDLIB_GLOBAL(less, 2)
DECLARE_STDLIB_GLOBAL(lax_less, 2)
DECLARE_STDLIB_GLOBAL(greater, 2)
DECLARE_STDLIB_GLOBAL(lax_greater, 2)
DECLARE_STDLIB_GLOBAL(new, 0)
#undef DECLARE_STDLIB_GLOBAL

// extern "C" MT3Value* mt3_mt3lib_gc_roots[] = {mt3_print, mt3_plus};
// extern "C" const size_t mt3_mt3lib_gc_roots_size = std::extent<decltype(mt3_mt3lib_gc_roots)>::value;

static void add_builtins_as_gc_roots() {
    mt3_add_gc_root(mt3_stdlib_logical_not);
    mt3_add_gc_root(mt3_stdlib_print);
    mt3_add_gc_root(mt3_stdlib_equality);
    mt3_add_gc_root(mt3_stdlib_inequality);
    mt3_add_gc_root(mt3_stdlib_plus);
    mt3_add_gc_root(mt3_stdlib_minus);
    mt3_add_gc_root(mt3_stdlib_mul);
    mt3_add_gc_root(mt3_stdlib_div);
    mt3_add_gc_root(mt3_stdlib_less);
    mt3_add_gc_root(mt3_stdlib_lax_less);
    mt3_add_gc_root(mt3_stdlib_greater);
    mt3_add_gc_root(mt3_stdlib_lax_greater);
    mt3_add_gc_root(mt3_stdlib_new);
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