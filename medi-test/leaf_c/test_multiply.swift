import io;



@dispatch=WORKER
(int result) multiply_func(int i1 , int i2) "multiply" "0.0"
["set <<result>> [multiply_func <<i1>> <<i2>>	]"] ;



int result = multiply_func (3, 3); 
printf("Swift result :%i", result);
