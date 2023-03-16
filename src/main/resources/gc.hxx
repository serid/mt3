class Traceable {
public:
    /// A traceable object should call visit() on Traceable-s in fields.
    virtual void visit() = 0;

    virtual ~Traceable() {
    };
};

class GCObject : public Traceable {
    GCObject* next = nullptr;
    bool mark = false;

public:
    void set_mark() { mark = true; }
    bool is_marked() { return mark; }

    /// Implementors are expected to check if mark is set, then return.
    /// If not set, then set it with this->set_mark() and call visit() on Traceable-s in fields.
    virtual void visit() override {
        set_mark();
    }
};

std::vector<Traceable*> gc_roots;

// MT3 modules should call this function during init to add their native global variables as gc roots
extern "C" void mt3_add_gc_root(Traceable* root) {
    gc_roots.push_back(root);
}