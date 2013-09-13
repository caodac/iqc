package tripod.iqc.core;

public interface Estimator {
    /**
     * While not required, the returned model should be the one
     * that minimize the mean square error!
     */
    FitModel estimate (Sample sample); 
    double getMSE (); // mean square error
}
