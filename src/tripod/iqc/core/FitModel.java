package tripod.iqc.core;

import java.util.Iterator;

public interface FitModel {
    class Parameter {
        String name;
        double value;

        Parameter (String name, double value) {
            this.name = name;
            this.value = value;
        }
        public String getName () { return name; }
        public double getValue () { return value; }
    }

    int getNumParams (); // number of parameters for this model
    Parameter getParam (int n); // get the nth parameter
    Iterator<Parameter> parameters ();
}
