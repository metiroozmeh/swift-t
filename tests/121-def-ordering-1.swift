
// Test out that functions can be used before they are defined

#include <builtins.swift>
#include <swift/assert.swift>

main {
    assertEqual(f(), 1, "");
}

(int r) f () {
  r = 1;
}
