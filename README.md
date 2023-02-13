# mt3

Dynamic programming language compiled with LLVM AOT. Main purpose is to experiment with different features of
language runtimes: GC, green threads, dynamic dispatch, reflection.

AOT compilation of dynamically typed languages is uncommon, this particular implementation was inspired by Dart AOT and [HOP.js](https://dl.acm.org/doi/pdf/10.1145/3473575).

# Internal documentation

Here I will document some facts about MT3 ABI, calling convention and the runtime system architecture
1. GC is tracing free-list allocating and with conservative stack scanning. Heap objects are visited precisely.
 Inspired by [Conservative Immix](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/conservative-gc-oopsla-2014.pdf).
2. Clang reserves from generating LLVM "call" instructions with "tail" marker in presence of "alloca"s.
 MT3 ABI uses "alloca" for passing arguments to calls, therefore it follows Clang's suit and
 MT3 codegen does not generate "call"s with "tail" markers because "alloca" instructions. 