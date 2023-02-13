// /// Metadata for garbage collection
// /// Mesa is the class of objects which are annotated with this metadata
// template<typename Mesa>
// class GCMeta {
//     Mesa* next = nullptr;
//     bool mark = false;
// public:
//     void set_mark() { mark = true; }
//     bool is_marked() { return mark; }
//
//     //void (*visit)(void*);
//
//     /// Implementors are expected to check if mark is set, then return.
//     /// If not set, then set it with this->set_mark() and call visit() on GCObject-s in fields.
//     virtual void visit() {
//         set_mark();
//     }
//
//     virtual ~GCObject() {
//     };
// }

class GCObject {
    GCObject* next = nullptr;
    bool mark = false;

public:
    void set_mark() { mark = true; }
    bool is_marked() { return mark; }

    /// Implementors are expected to check if mark is set, then return.
    /// If not set, then set it with this->set_mark() and call visit() on GCObject-s in fields.
    virtual void visit() {
        set_mark();
    }

    virtual ~GCObject() {
    };
};

std::vector<GCObject*> gc_roots;

// MT3 modules should call this function during init to add their global variables as gc roots
void mt3_add_gc_root_cxx(GCObject* root) {
    gc_roots.push_back(root);
}

extern "C" void mt3_add_gc_root(void* root) {
    mt3_add_gc_root_cxx(reinterpret_cast<GCObject*>(root));
}
