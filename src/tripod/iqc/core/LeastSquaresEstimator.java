package tripod.iqc.core;

import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.apache.commons.math.stat.regression.SimpleRegression;

public class LeastSquaresEstimator 
    implements Estimator, Comparator<Estimator.Result> {

    private static final Logger logger = Logger.getLogger
        (LeastSquaresEstimator.class.getName());

    static public class LinearFitModel implements FitModel {
        SimpleRegression reg;
        Variable[] params = new Variable[2];
        Variable[] metrics = new Variable[4];
        final Measure[] measures;

        LinearFitModel (Measure[] measures, SimpleRegression reg) {
            this.reg = reg;
            this.measures = measures;
            params[0] = new Variable ("Slope", reg.getSlope());
            params[1] = new Variable ("Intercept", reg.getIntercept());
            metrics[0] = new Variable ("MSE", reg.getMeanSquareError());
            metrics[1] = new Variable ("r^2", reg.getR());
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

        public Measure[] getMeasures () { return measures; }

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
    private int maxOutliers = 3; 
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
        /*
        if (maxSize > 0 && sample.size() > maxSize) {
            throw new IllegalArgumentException
                ("Sample contains too many measures!");
        }
        */

        final List<Result> results = new ArrayList<Result>();
        final Measure[] measures = sample.getMedianMeasures();
        /*
        // ignore blank measures
        { List<Measure> M = new ArrayList<Measure>();
            for (Measure m : sample.getMeasures()) {
                if (!m.getBlank()) {
                    M.add(m);
                }
            }
            measures = M.toArray(new Measure[0]);
        }
        */

        if (measures.length < 3) {
            throw new IllegalArgumentException
                ("Sample "+sample.getName()
                 +" contains too few measures ("+measures.length
                 +") for a meaningful fit!");
        }

        final int m = Math.min(3, Math.max(0, measures.length - maxOutliers));

        // enumerate 2^N combinations and generate a linear regression
        //  for those configurations that has at least m measures
        int size = measures.length;
        if (maxSize > 0)
            size = Math.min(size, maxSize);

        GrayCode gc = GrayCode.createBinaryGrayCode(size);
        gc.addObserver(new Observer () {
                public void update (Observable o, Object arg) {
                    int[] bv = (int[])arg;
                    int c = 0;
                    for (int i = 0; i < bv.length; ++i)
                        if (bv[i] > 0)
                            ++c;
                    if (c >= 3) {
                        results.add(estimate (sample, bv, measures));
                    }
                }
            });

        // now run
        gc.generate();
        Collections.sort(results); // sort results
        int rank = 0;
        for (Result r : results) {
            r.setRank(++rank);
        }

        //logger.info(results.size()+" results!");
        return results;
    }

    public int compare (Result r1, Result r2) {
        if (r2.getScore() > r1.getScore()) return 1;
        if (r2.getScore() < r1.getScore()) return -1;
        return 0;
    }

    protected Result estimate (Sample sample, int[] selector, 
                               Measure[] measures) {
        SimpleRegression reg = new SimpleRegression ();
        List<Measure> selected = new ArrayList<Measure>();
        for (int i = 0; i < selector.length; ++i) {
            if (selector[i] > 0) {
                Double t = measures[i].getTime();
                Double r = measures[i].getResponse();
                if (t != null && r != null) {
                    selected.add(measures[i]);
                    reg.addData(t, Math.log(r)); // natural log
                }
            }
        }

        return new Result
            (sample, measures, selector, 
             new LinearFitModel (selected.toArray(new Measure[0]), reg),
             new LeastSquaresFitScore ());
    }
}
