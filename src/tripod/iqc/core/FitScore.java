package tripod.iqc.core;

/**
 * An interface for scoring a FitModel
 */
public interface FitScore {
    double eval (FitModel model);
}
