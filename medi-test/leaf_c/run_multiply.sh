#!/bin/sh -ex 


gcc -c multiply.c
gcc -c main.c
gcc -o multiply.x main.o multiply.o


./multiply.x
