package tripod.iqc.core;

import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.apache.commons.math.stat.regression.SimpleRegression;

public class LeastSquaresEstimator implements Estimator {
    private static final Logger logger = Logger.getLogger
        (LeastSquaresEstimator.class.getName());

    private double bestMSE;
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
    public FitModel estimate (Sample sample) {
        if (maxSize > 0 && sample.size() > maxSize) {
            throw new IllegalArgumentException
                ("Sample contains too many measures!");
        }

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

        final int m = Math.max(0, measures.length - maxOutliers);
        final List<SimpleRegression> estimators = 
            new ArrayList<SimpleRegression>();

        System.out.println(">>> sample "+sample.getName());
        for (int i = 0; i < measures.length; ++i) {
            System.out.println(i+": "+measures[i]);
        }

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
                        SimpleRegression est = estimate (bv, measures);
                        estimators.add(est);
                    }
                }
            });

        // now run
        gc.generate();

        logger.info(estimators.size()+" estimators!");
        

        return null;
    }

    protected SimpleRegression estimate (int[] selector, Measure[] measures) {
        SimpleRegression reg = new SimpleRegression ();
        System.out.print("# config");
        for (int i = 0; i < selector.length; ++i) {
            if (selector[i] > 0) {
                Double t = measures[i].getTime();
                Double r = measures[i].getResponse();
                if (t != null && r != null) {
                    reg.addData(t, Math.log(r)); // natural log
                }
            }
            System.out.print(" "+selector[i]);
        }

        System.out.println(" => slope="+reg.getSlope()+" intercept="
                           +reg.getIntercept()+" R="+reg.getR()+" MSE="
                           +reg.getMeanSquareError());

        return reg;
    }


    public double getMSE () { return bestMSE; }
}
