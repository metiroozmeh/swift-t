
// SKIP- THIS-TEST : currently cannot handle input <<i>> (#563)

import io;

(file o[]) task(file i, int n) "turbine" "0.1"
[
"""
exec ./316-array-build.task.sh <<i>> <<n>>;
set L [ glob test-316-*.data ];
turbine::swift_array_build <<o>> $L file;
"""
];  

main
{
  printf("OK");
  file i = input("input.txt"); 
  file o[];
  o = task(i, 10);
  foreach f in o
  {
    printf("output file: %s", filename(f));
  }
}

