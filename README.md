# mt3

Dynamic programming language compiled with LLVM AOT. Main purpose is to experiment with different features of
language runtimes: GC, green threads, dynamic dispatch, reflection.

AOT compilation of dynamically typed languages is uncommon, this particular implementation was inspired by Dart AOT and [HOP.js](https://dl.acm.org/doi/pdf/10.1145/3473575).