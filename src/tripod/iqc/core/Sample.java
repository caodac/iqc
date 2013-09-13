package tripod.iqc.core;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

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
        measures.add(measure);
    }
    public int size () { return measures.size(); }

    public Iterator<Measure> measures () { 
        return measures.iterator(); 
    }

    public List<Measure> getMeasures () { 
        return Collections.unmodifiableList(measures); 
    }
}
