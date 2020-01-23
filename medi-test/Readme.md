Currently, I am trying to export a c library as Tcl package. However, I managed to complete described steps in guide, section 3.2, I get following error while trying to test my tcl package. 


    couldn't find procedure G_Init
        while executing
    "load /home/fh1-project-devel/th7356/work/examples_tcl/10_mine/libg.so"
        ("package ifneeded my_pkg 0.0" script)
        invoked from within
    "package require my_pkg 0.0"
        (file "test_tcl.tcl" line 1)


Additional note: The test case is only an extension of your second example (g function with sum operation) with almost same structure. Please let me know what you think.