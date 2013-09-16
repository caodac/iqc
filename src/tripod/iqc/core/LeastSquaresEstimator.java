package tripod.iqc.core;

import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.apache.commons.math.stat.regression.SimpleRegression;

public class LeastSquaresEstimator 
    implements Estimator, Comparator<Estimator.Result> {

    private static final Logger logger = Logger.getLogger
        (LeastSquaresEstimator.class.getName());

    static class LinearFitModel implements FitModel {
        SimpleRegression reg;
        Variable[] params = new Variable[2];
        Variable[] metrics = new Variable[4];

        LinearFitModel (SimpleRegression reg) {
            this.reg = reg;
            params[0] = new Variable ("Slope", reg.getSlope());
            params[1] = new Variable ("Intercept", reg.getIntercept());
            metrics[0] = new Variable ("MSE", reg.getMeanSquareError());
            metrics[1] = new Variable ("R", reg.getR());
            metrics[2] = new Variable ("SlopeStdErr", reg.getSlopeStdErr());
            metrics[3] = new Variable ("InterceptStdErr", 
                                       reg.getInterceptStdErr());
        }

        public Variable getVariable (String name) {
            for (Variable v : params) {
                if (name.equals(v.name))
                    return v;
            }
            for (Variable v : metrics) {
                if (name.equals(v.name))
                    return v;
            }
            return null;
        }

        public double getMSE () { return reg.getMeanSquareError(); }
        public double getR () { return reg.getR(); }

        public Object getModelObj () { return reg; }
        public int getNumParams () { return params.length; }
        public Variable getParam (int n) { return params[n]; }
        public Variable[] parameters () { return params; }

        public int getNumMetrics () { return metrics.length; }
        public Variable getMetric (int n) { return metrics[n]; }
        public Variable[] metrics () { return metrics; }

        public String toString () {
            return "LinearFitModel{slope="
                +String.format("%1$.5f", reg.getSlope())
                +",intercept="+String.format("%1$.5f", reg.getIntercept())
                +",MSE="+String.format("%1$.5f", reg.getMeanSquareError())
                +",R^2="+String.format("%1$.3f", reg.getR())
                +"}";
        }
    }

    // maximum number of outliers to tolerate
    private int maxOutliers = 2; 
    // maximum number of data points to allow 
    private int maxSize = 10;

    public LeastSquaresEstimator () {
    }

    public void setMaxOutliers (int max) {
        this.maxOutliers = max;
    }
    public int getMaxOutliers () { return maxOutliers; }

    public void setMaxSize (int max) { maxSize = max; }
    public int getMaxSize () { return maxSize; }

    /**
     * Let N be the number of measures for a given sample and k be
     * the number of allowed outliers. This estimator is defined as
     * follows:
     *
     *    model = argmin_{n = max(0,N-k) to N}  L(choose{N}{n} measures)
     *
     * where L(*) is the least squares fit based on the number of 
     * measures.
     */
    public List<Result> estimate (final Sample sample) {
        if (maxSize > 0 && sample.size() > maxSize) {
            throw new IllegalArgumentException
                ("Sample contains too many measures!");
        }

        final List<Result> results = new ArrayList<Result>();
        final Measure[] measures;
        // ignore blank measures
        { List<Measure> M = new ArrayList<Measure>();
            for (Measure m : sample.getMeasures()) {
                if (!m.getBlank()) {
                    M.add(m);
                }
            }
            measures = M.toArray(new Measure[0]);
        }

        if (measures.length < 3) {
            throw new IllegalArgumentException
                ("Too few measures ("+measures.length
                 +") for a meaningful fit!");
        }

        final int m = Math.max(0, measures.length - maxOutliers);

        // enumerate 2^N combinations and generate a linear regression
        //  for those configurations that has at least m measures
        GrayCode gc = GrayCode.createBinaryGrayCode(measures.length);
        gc.addObserver(new Observer () {
                public void update (Observable o, Object arg) {
                    int[] bv = (int[])arg;
                    int c = 0;
                    for (int i = 0; i < bv.length; ++i)
                        if (bv[i] > 0)
                            ++c;
                    if (c >= m) {
                        results.add(estimate (sample, bv, measures));
                    }
                }
            });

        // now run
        gc.generate();
        // sort results
        Collections.sort(results, this);

        //logger.info(results.size()+" results!");
        return results;
    }

    public int compare (Result r1, Result r2) {
        LinearFitModel m1 = (LinearFitModel)r1.getModel();
        LinearFitModel m2 = (LinearFitModel)r2.getModel();

        double d = m1.getMSE() - m2.getMSE();
        if (d < 0) return -1;
        if (d > 0) return 1;

        d = m2.getR() - m1.getR();
        if (d < 0) return -1;
        if (d > 0) return 1;
        return 0;
    }

    protected Result estimate (Sample sample, int[] selector, 
                               Measure[] measures) {
        SimpleRegression reg = new SimpleRegression ();
        for (int i = 0; i < selector.length; ++i) {
            if (selector[i] > 0) {
                Double t = measures[i].getTime();
                Double r = measures[i].getResponse();
                if (t != null && r != null) {
                    reg.addData(t, Math.log(r)); // natural log
                }
            }
        }

        return new Result (sample, measures, 
                           selector, new LinearFitModel (reg));
    }
}
