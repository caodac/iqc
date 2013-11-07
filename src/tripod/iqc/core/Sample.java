package tripod.iqc.core;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * POJO Metabolic stability sample 
 */
public class Sample implements Serializable {
    private static final long serialVersionUID = 0xd7d0162de412cf15l;

    private String name; // sample name
    private String comments; // any comments
    private boolean standard; // is this sample an internal standard?
    private boolean blank; // is this a blank sample?
    private List<Measure> measures = new ArrayList<Measure>();
    private Measure[] median;

    private BitSet replicates = new BitSet ();

    public Sample () {}
    public Sample (String name) {
        this.name = name;
    }

    public Sample setName (String name) {
        this.name = name;
        return this;
    }
    public String getName () { return name; }

    public Sample setComments (String comments) {
        this.comments = comments;
        return this;
    }
    public String getComments () { return comments; }

    public Sample setStandard (boolean standard) {
        this.standard = standard;
        return this;
    }
    public boolean getStandard () { return standard; }

    public Sample setBlank (boolean blank) {
        this.blank = blank;
        return this;
    }
    public boolean getBlank () { return blank; }

    public void add (Measure measure) {
        replicates.set(measure.getReplicate(), true);
        measures.add(measure);
    }
    public int size () { return measures.size(); }

    public int getReplicateCount () {
        return replicates.cardinality();
    }

    public Measure get (int pos) { return measures.get(pos); }
    public Iterator<Measure> measures () { 
        return measures.iterator(); 
    }

    public List<Measure> getMeasures () { 
        return Collections.unmodifiableList(measures); 
    }

    // if more than one replicates, calculate the means
    synchronized public Measure[] getMedianMeasures () { 
        if (median == null) {
            Map<Double, List<Double>> responses = 
                new TreeMap<Double, List<Double>>();

            for (Measure m : measures) {
                Double t = m.getTime();
                Double r = m.getResponse();
                if (!m.getBlank() && t != null) {
                    List<Double> lr = responses.get(t);
                    if (lr == null) {
                        responses.put(t, lr = new ArrayList<Double>());
                    }
                    if (r != null)
                        lr.add(r);
                }
            }

            List<Measure> data = new ArrayList<Measure>();            
            for (Map.Entry<Double, List<Double>> me : responses.entrySet()) {
                Double t = me.getKey();
                List<Double> lr = me.getValue();
                Measure m = new Measure ("Median-"+t);
                if (!lr.isEmpty()) {
                    m.setTime(t, TimeUnit.MINUTES);
                    if (lr.size() == 1) {
                        m.setResponse(lr.get(0));
                    }
                    else if (lr.size() > 1) {
                        Collections.sort(lr);
                        double total = 0;
                        for (Double r : lr) {
                            total += r;
                        }
                        m.setResponse(total/lr.size());
                        /*
                        int mid = lr.size()/2;
                        if (lr.size() % 2 == 0) {
                            m.setResponse((lr.get(mid) + lr.get(mid-1))/.2);
                        }
                        else {
                            m.setResponse(lr.get(mid));
                        }
                        */
                    }
                }
                data.add(m);
            }

            median = data.toArray(new Measure[0]);
        }
        return median;
    }

    public List<Measure> getMeasures (int replicate) {
        List<Measure> repl = new ArrayList<Measure>();
        for (Measure m : measures) {
            if (replicate == m.getReplicate()) {
                repl.add(m);
            }
        }
        return repl;
    }

    public String toString () { return name; }
}
