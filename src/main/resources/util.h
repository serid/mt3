#include <stdint.h>

typedef uint8_t u8;
typedef uint32_t u32;
typedef uint64_t u64;
typedef int64_t i64;

// From https://clang.llvm.org/docs/LanguageExtensions.html
#ifndef __has_builtin         // Optional of course.
  #define __has_builtin(x) 0  // Compatibility with non-clang compilers.
#endif
#if __has_builtin(__builtin_debugtrap)
  #define mt3_abort() do { __builtin_debugtrap(); abort(); } while (false)
#else
  #define mt3_abort() do { abort(); } while (false)
#endif

[[noreturn]]
static void panic(const char* message) {
    puts(message);
    mt3_abort();
}

static void my_assert(bool condition, const char* message) {
    if (!condition) {
        panic(message);
    }
}