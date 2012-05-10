
// Recursive multiplication based on addition, subtraction

#include <builtins.swift>
#include <swift/assert.swift>

// mult(i,0,s) = s
// mult(i,j,s) = mult(i,j-1,s+i)
(int o) my_mult_helper(int i, int j, int s)
{
  int t;
  int n;
  int m;
  n = 1;
  t = plus_integer(s,i);
  if (j)
  {
    int k;
    k = minus_integer(j,n);
    o = my_mult_helper(i,k,t);
  }
  else
  {
    o = copy_integer(s);
  }
}

// Reserve multiply() for actual multiplication function
(int u) my_mult(int i, int j)
{
  int s;
  s = 0;
  u = my_mult_helper(i,j,s);
}

// Compute z = x*y
main
{
  int x;
  int y;
  int z;

  x = 3;
  y = 3;

  z = my_mult(x,y);
  trace(z);
  assertEqual(z, x*y,"");
}
