package tripod.iqc.core;

import java.util.List;

public interface Estimator {
    class Result {
        Sample sample;
        Measure[] measures;
        int[] config; // configuration
        FitModel model;

        Integer rank;
        String comments;

        Result (Sample sample, Measure[] measures, 
                int[] config, FitModel model) {
            this.sample = sample;
            this.measures = measures;
            this.config = (int[])config.clone();
            this.model = model;
        }

        public Sample getSample () { return sample; }
        public Measure[] getMeasures () { return measures; }
        public int[] getConfig () { return config; }
        public FitModel getModel () { return model; }

        public Integer getRank () { return rank; }
        public void setRank (int rank) { this.rank = rank; }
        public String getComments () { return comments; }
        public void setComments (String comments) { 
            this.comments = comments; 
        }

        public String toString () {
            StringBuilder sb = new StringBuilder ("Result{\n");
            sb.append(" sample: "+sample.getName()+"\n");
            sb.append(" measures:\n");
            if (config != null) {
                for (int i = 0; i < config.length; ++i) {
                    if (config[i] > 0) 
                        sb.append("  ["+i+"] " +measures[i]+"\n");
                }
            }
            sb.append(" model: "+model);
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
