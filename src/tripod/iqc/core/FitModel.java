package tripod.iqc.core;

public interface FitModel {
    class Variable {
        String name;
        double value;

        Variable (String name, double value) {
            this.name = name;
            this.value = value;
        }
        public String getName () { return name; }
        public double getValue () { return value; }
    }

    Object getModelObj (); // raw underlying model object
    Variable getVariable (String name);

    /**
     * model parameters
     */
    int getNumParams (); // number of parameters for this model
    Variable getParam (int n); // get the nth parameter
    Variable[] parameters ();

    /**
     * model evaluation metrics
     */
    int getNumMetrics (); // number of metrics
    Variable getMetric (int n); // get the nth metric
    Variable[] metrics ();
}
