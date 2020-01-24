Generated TCL package from c library works fine, howevr i can not not run it with turbine and generate the following error: 


    can't find package multiply 0.0
        while executing
    "package require multiply 0.0"
        (file "test_multiply.tic" line 78)

let mw know what do you think. 
regards,
medi



##SOLVED### Currently, I am trying to export a c library as Tcl package. However, I managed to complete described steps in guide, section 3.2, I get following error while trying to test my tcl package. 


    couldn't find procedure G_Init
        while executing
    "load /home/fh1-project-devel/th7356/work/examples_tcl/10_mine/libg.so"
        ("package ifneeded my_pkg 0.0" script)
        invoked from within
    "package require my_pkg 0.0"
        (file "test_tcl.tcl" line 1)


Additional note: The test case is only an extension of your second example (g function with sum operation) with almost same structure. Please let me know what you think.

Please generate the package by: 


    swig -module multiply multiply.h
    bash run_multiply_swig.sh
    tclsh test_tcl.tcl
