
#include <builtins.swift>
#include <swift/assert.swift>
#include <swift/stats.swift>

(int M[]) f () {
    M[0] = 1;
    M[1] = 2;
    M[2] = 3;
}

main {
    trace(sum_integer(f()));
    assertEqual(sum_integer(f()), 6,"");
}
