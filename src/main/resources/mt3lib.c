// MT3 runtime library implemented in C

#include <stdlib.h>
#include <stdio.h>
#include "util.h"

typedef struct {
    u8 tag;
} MT3ValueStruct, *MT3Value;
const u8 INT_TAG = 1;
const u8 ARRAY_TAG = 2;
const u8 OBJECT_TAG = 3;

typedef struct /* extends MT3Value */ {
    u8 tag;
    u64 value;
} MT3Int;
typedef struct /* extends MT3Value */ {
    u8 tag;
    void* data;
    size_t size;
    size_t capacity;
} MT3Array;

void panic(char* message) {
    printf("%s", message);
    exit(1);
}

void* gc_malloc(size_t size) {
    return malloc(size);
}


MT3Value newString(char*) {
    MT3Int* result = gc_malloc(sizeof(MT3Array));
}

MT3Value newString(char*) {
    MT3Int* result = gc_malloc(sizeof(MT3Array));
}

// operator+
MT3Value mt3_plus(MT3Value a, MT3Value b) {
    if (a->tag != INT_TAG || b->tag != INT_TAG)
        panic("Unsupported types for operator_plus");

    MT3Int* result = gc_malloc(sizeof(MT3Int));
    result->tag = INT_TAG;
    result->value = ((MT3Int*) a)->value + ((MT3Int*) b)->value;
    return (MT3Value) result;
}

// Guest main function generated by the mt3 compiler using LLVM
MT3Value mt3_main();

// This function is called from start.o or whatever
int main() {
    mt3_main();
}