#!/bin/sh
set -ex

# Compile the SWIG-generated Tcl extension library

gcc -c -fPIC -Wall multiply.c
gcc -c -fPIC $TCL_INCLUDE_SPEC multiply_wrap.c
gcc -shared -o libg.so multiply_wrap.o multiply.o
tclsh make-package.tcl > pkgIndex.tcl
