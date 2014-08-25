package tripod.iqc.core;

import java.util.List;

public interface Estimator {
    class Result implements Comparable<Result> {
        Sample sample;
        Measure[] measures;
        int[] config; // configuration
        FitModel model;
        Double score;
        Double clint; // intrinsic clearance
        int rank;

        Result (Sample sample, Measure[] measures, 
                int[] config, FitModel model, FitScore scorer) {
            this.sample = sample;
            this.measures = measures;
            this.config = (int[])config.clone();
            this.model = model;
            this.score = scorer.eval(model);
        }

        public Sample getSample () { return sample; }

        // unique identifier associated with this result
        public String getId () {
            StringBuilder sb = new StringBuilder ();
            for (int i = 0; i < config.length; ++i) {
                if (config[i] > 0) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(i);
                }
            }
            return sample.getName()+"["+sb+"]";
        }

        public Measure[] getMeasures () { return measures; }
        public int[] getConfig () { return config; }
        public FitModel getModel () { return model; }
        public Double getScore () { return score; }
        public int getRank () { return rank; }
        public void setRank (int rank) { this.rank = rank; }

        public Double getCLint () { return clint; }
        public void setCLint (Double clint) { this.clint = clint; }

        public int compareTo (Result r) {
            Double s = r.getScore();
            if (s != null) return s.compareTo(this.score);
            if (this.score == null) return 0;
            return 1;
        }

        public String toString () {
            StringBuilder sb = new StringBuilder ("Result{\n");
            sb.append(" sample: "+sample.getName()+"\n");
            sb.append(" Rank: "+rank+"\n");
            sb.append(" measures:\n");
            if (config != null) {
                for (int i = 0; i < config.length; ++i) {
                    if (config[i] > 0) 
                        sb.append("  ["+i+"] " +measures[i]+"\n");
                }
            }
            sb.append(" model: "+model+"\n");
            sb.append(" score: "+score);
            sb.append("\n}");
            return sb.toString();
        }
    }

    /**
     * While not required, the returned model should be the one
     * that minimize the mean square error!
     */
    List<Result> estimate (Sample sample); 
}
