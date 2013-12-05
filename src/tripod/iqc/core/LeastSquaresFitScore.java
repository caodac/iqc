package tripod.iqc.core;

import static tripod.iqc.core.LeastSquaresEstimator.*;

/**
 * This class define the heuristics used to score a linear
 * fit model. 
 */
public class LeastSquaresFitScore implements FitScore {
    final static double BEST_SCORE = 11.81250; // 6(1+1/2+1/4+1/8+1/16+1/32)

    public LeastSquaresFitScore () {
    }

    /**
     * The current fit score is as follows:
     * score = N * exp(-MSE) * R^2 * sum_{i} t_i
     * for i = {0, 1, 2, ..., N-1} where each i corresponds to 
     * {T0, T5, T10, T15, T30, T60} in order, t_i = 1/2^i,
     * and R is the Pearson's correlation. The score is raw
     * so to normalize it we take the ratio with respective to
     * the best possible score where N = 6, R = 1, min(N, MSE) = 0, 
     * and the summation is 1+1/2+1/4+1/8+1/16+1/32. The best 
     * possible score is thus 378/32.
     */
    public double eval (FitModel model) {
        if (!(model instanceof LinearFitModel)) {
            throw new IllegalArgumentException ("Not a LinearFitModel");
        }

        LinearFitModel lfm = (LinearFitModel)model;
        Measure[] measures = lfm.getMeasures();
        double score = 0.;
        for (Measure m : measures) {
            int t = m.getTime().intValue();
            switch (t) {
            case  0: score += 1; break;
            case  5: score += 1/2.; break;
            case 10: score += 1/4.; break;
            case 15: score += 1/8.; break;
            case 30: score += 1/16.; break;
            case 60: score += 1/32.; break;
            }
        }
        score *= measures.length * Math.exp(-lfm.getMSE()) 
            * lfm.getR() * lfm.getR();

        return score/BEST_SCORE;
    }
}
