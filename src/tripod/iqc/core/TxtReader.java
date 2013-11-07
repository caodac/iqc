package tripod.iqc.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

public class TxtReader implements Reader, Comparator<Measure> {
    private static final Logger logger = 
        Logger.getLogger(TxtReader.class.getName());

    static int DEBUG = 0;
    static {
        try {
            DEBUG = Integer.getInteger("reader.debug", 0);
        }
        catch (Exception ex) {
        }
    }

    private int lines;
    private BufferedReader reader;

    public TxtReader (InputStream is) throws IOException {
        reader = new BufferedReader (new InputStreamReader (is));
    }

    public int compare (Measure m1, Measure m2) {
        int r1 = m1.getReplicate(), r2 = m2.getReplicate();
        if (r1 == r2) {
            Double t1 = m1.getTime(), t2 = m2.getTime();
            if (t1 != null && t2 != null) {
                if (t1 < t2) return -1;
                if (t1 > t2) return 1;
            }
            else if (t1 != null) 
                return -1;
            else if (t2 != null)
                return 1;
            return 0;
        }

        return r1 - r2;
    }

    /**
     * return the next available sample (if any) from the stream.
     * if there isn't any sample left, null is returned.
     */
    public Sample read () throws IOException {
        String compound = null;

        for (String line; (line = reader.readLine()) != null; ++lines) {
            if (line.startsWith("Compound")) {
                String[] tokens = line.split(":");
                if (tokens.length == 2) {
                    compound = tokens[1].trim();
                }
                else {
                    logger.warning("Ignoring unknown Compound line: "+line);
                }
                break;
            }
        }

        if (compound != null) {
            return getNextSample (compound);
        }

        return null;
    }

    protected Sample getNextSample (String compound) 
        throws IOException {
        Sample sampl = null;

        if (DEBUG > 0) 
            logger.info("** Start parsing compound " +compound+" at "+lines);
        
        String[] header = null;
        List<Measure> measures = new ArrayList<Measure>();
        int repl = 0;

        for (String line; (line = reader.readLine()) != null; ++lines) {
            String[] tokens = line.split("\t");
            if (header == null) {
                if (tokens.length > 1) {
                    if (DEBUG > 0) {
                        System.out.print("Header: ");
                        for (int i = 0; i < tokens.length; ++i)
                            System.out.print(" "+i+":"+tokens[i]);
                        System.out.println();
                    }

                    header = tokens;
                    sampl = new Sample (compound);
                    if (compound.equalsIgnoreCase("blank"))
                        sampl.setBlank(true);
                    else if (compound.equalsIgnoreCase("albendazole"))
                        sampl.setStandard(true);
                }
            }
            // pick some number greater than 1
            else if (sampl != null && tokens.length > 5) { // measure rows
                Measure measure = new Measure (repl);
                for (int i = 0; i < tokens.length; ++i) {
                    String h = header[i].trim();
                    String v = tokens[i].trim();

                    if (v.length() == 0) {
                        // no value
                    }
                    else if ("name".equalsIgnoreCase(h))
                        measure.setName(v);
                    else if ("sample text".equalsIgnoreCase(h)) {
                        if (v.startsWith("Blank") || v.startsWith("blank")) {
                            // we use Blank to demarcate replicates
                            measure.setBlank(true);
                            repl++;
                        }
                        else if (v.startsWith("T")) {
                            // T0, T5, T10, etc.
                            int pos = 1;
                            while (pos < v.length() 
                                   && v.charAt(pos) != ' ')
                                ++pos;
                            try {
                                double time = Double.parseDouble
                                    (v.substring(1, pos));
                                measure.setTime(time, TimeUnit.MINUTES);
                            }
                            catch (NumberFormatException ex) {
                                logger.warning("Bogus time: "+v);
                            }
                        }
                        measure.setComments(v);
                    }
                    else if ("primary flags".equalsIgnoreCase(h))
                        measure.setFlag(v);
                    else if ("rt".equalsIgnoreCase(h)) {
                        try {
                            measure.setRt(Double.parseDouble(v));
                        }
                        catch (NumberFormatException ex) {
                            logger.warning("Bogus rt: "+v);
                        }
                    }
                    else if ("area".equalsIgnoreCase(h)) {
                        try {
                            measure.setArea(Double.parseDouble(v));
                        }
                        catch (NumberFormatException ex) {
                            logger.warning("Bogus area: "+v);
                        }
                    }
                    else if ("is area".equalsIgnoreCase(h)) {
                        try {
                            measure.setIsArea(Double.parseDouble(v));
                        }
                        catch (NumberFormatException ex) {
                            logger.warning("Bogus IS area: "+v);
                        }
                    }
                    else if ("response".equalsIgnoreCase(h)) {
                        try {
                            measure.setResponse(Double.parseDouble(v));
                        }
                        catch (NumberFormatException ex) {
                            logger.warning("Bogus response: "+v);
                        }
                    }
                }

                if (DEBUG > 0) {
                    System.out.println(measure);
                }

                measures.add(measure);
            }
            else {
                break;
            }
        }

        // sort the measures based on time
        Collections.sort(measures, this);
        for (Measure m : measures) {
            sampl.add(m);
        }
        //logger.info("Sample "+sampl.getName()+" "+sampl.getReplicateCount());

        return sampl;
    }

    public static void main (String[] argv) throws Exception {
        Reader reader;
        if (argv.length == 0) {
            logger.info("Reading from stdin...");
            reader = new TxtReader (System.in);
        }
        else {
            logger.info("Reading from \""+argv[0]+"\"...");
            reader = new TxtReader (new FileInputStream (argv[0]));
        }

        Estimator estimator = new LeastSquaresEstimator ();
        int c = 0;
        for (Sample sampl; (sampl = reader.read()) != null; ) {
            List<Estimator.Result> results = estimator.estimate(sampl);
            for (Estimator.Result res : results) {
                System.out.println(res);
            }
            if (++c > 5) break;
        }
    }
}
