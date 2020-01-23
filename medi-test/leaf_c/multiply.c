#include <stdio.h>
#include <unistd.h>
#include "multiply.h"


int multiply_func(int x , int y) 

{

	int sum = x+y;
        printf("The proccess is sleeping for %d second \n", sum );
        sleep(sum);
        int mult=x*y;
        printf("Result for x= %d , y = %d , mult= %d \n", x, y, mult);
        return  mult;

}
