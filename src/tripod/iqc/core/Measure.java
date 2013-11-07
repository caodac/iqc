package tripod.iqc.core;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * POJO Metabolic stability measure 
 */
public class Measure implements Serializable {
    private static final long serialVersionUID = 0x45f2411f7f3052c5l;

    private String name; // measure name
    private String comments; // any comments
    private String flag;
    private Double time;
    private TimeUnit timeUnit;
    private Double rt; // retention time
    private Double area;
    private Double isArea;
    private Double response;
    private boolean blank; // is this a blank measure?
    private int replicate; // replicate #

    public Measure () {}
    public Measure (int replicate) {
        this.replicate = replicate;
    }
    public Measure (String name) { this.name = name; }

    public Measure setName (String name) {
        this.name = name;
        return this;
    }
    public String getName () { return name; }

    public Measure setComments (String comments) {
        this.comments = comments;
        return this;
    }
    public String getComments () { return comments; }

    public Measure setFlag (String flag) { 
        this.flag = flag; 
        return this;
    }
    public String getFlag () { return flag; }

    public Measure setBlank (boolean blank) {
        this.blank = blank;
        return this;
    }
    public boolean getBlank () { return blank; }

    public Measure setTime (Double time, TimeUnit unit) {
        this.time = time;
        this.timeUnit = unit;
        return this;
    }
    public Measure setUnit (TimeUnit unit) {
        this.timeUnit = unit;
        return this;
    }
    public Double getTime () { return time; }
    public TimeUnit getTimeUnit () { return timeUnit; }

    public Measure setRt (Double rt) { 
        this.rt = rt;
        return this;
    }
    public Double getRt () { return rt; }
    
    public Measure setArea (Double area) {
        this.area = area;
        return this;
    }
    public Double getArea () { return area; }

    public Measure setIsArea (Double isArea) {
        this.isArea = isArea;
        return this;
    }
    public Double getIsArea () { return area; }

    public Measure setResponse (Double response) {
        this.response = response;
        return this;
    }
    public Double getResponse () { return response; }

    public Measure setReplicate (int replicate) {
        this.replicate = replicate;
        return this;
    }
    public int getReplicate () { return replicate; }

    public String toString () {
        return getClass().getName()+"{name="+getName()+",comments="
            +getComments()+",flag="+getFlag()+",blank="+getBlank()
            +",time="+getTime()+" "+getTimeUnit()+",rt="+getRt()
            +",area="+getArea()+",isArea="+getIsArea()+",response="
            +getResponse()+",replicate="+replicate+"}";
    }
}
