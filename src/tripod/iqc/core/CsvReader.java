package tripod.iqc.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

public class CsvReader implements Reader {
    private static final Logger logger = 
        Logger.getLogger(CsvReader.class.getName());

    /* parse csv format of the form:
Sample,T0,T0-S,T5,T5-S,T10,T10-S,T15,T15-S,T30,T30-S,T60,T60-S
(-)-(5S-1,N/F,1.677076542E9,N/F,1.618658723E9,N/F,1.607323645E9,N/F,1.541469267E9,10210.0,1.363242889E9,N/F,1.353079267E9
1-(3-Chlorophenyl)piperazine-1,N/F,1.848450755E9,N/F,1.531455623E9,N/F,1.717126041E9,N/F,1.697875042E9,N/F,1.603803318E9,N/F,1.54343551E9
1-Phenyl-3-methyl-5-pyrazolone-1,2.7697778E7,1.749435363E9,4.3810164E7,1.730342426E9,4.7372897E7,1.767680206E9,5.0247226E7,1.746245646E9,4.6486083E7,1.740417192E9,4.3340614E7,1.705327411E9
     */

    static final int[] TIMES = {
        0,
        5,
        10,
        15,
        30,
        60
    };

    private int lines;
    private String[] header;
    private BufferedReader reader;
    private Map<String, Sample> samples = new HashMap<String, Sample>();

    public CsvReader (InputStream is) throws IOException {
        reader = new BufferedReader (new InputStreamReader (is));
        String line = reader.readLine();
        if (line == null) {
            throw new IllegalArgumentException ("End of stream");
        }
        
        header = line.split(",");
        if (header.length != 13) {
            throw new IllegalArgumentException ("Invalid header: "+line);
        }
        lines = 1;
    }

    public Sample read () throws IOException {
        String line = reader.readLine();
        Sample sampl = null;
        if (line != null) {
            ++lines;
            //String[] toks = line.split(",");
            String[] toks = tokenizer (line, ',');
            if (toks.length != header.length) {
                logger.warning(lines+": invalid number of tokens "+toks.length
                               +"; expecting "+header.length);
            }
            else if (toks[0] != null) {
                String sample = toks[0];
                int repl = 0;
                int pos = sample.lastIndexOf('-');
                if (pos > 0) {
                    repl = Integer.parseInt(sample.substring(pos+1)) - 1;
                    sample = sample.substring(0, pos);
                }

                sampl = samples.get(sample);
                if (sampl == null) {
                    samples.put(sample, sampl = new Sample (sample));
                }
                
                for (int i = 0, j = 1; i < TIMES.length; ++i, j += 2) {
                    if ("N/F".equals(toks[j])) {
                    }
                    else {
                        try {
                            // make sure the time is correct Tx
                            int t = Integer.parseInt(header[j].substring(1));
                            if (t != TIMES[i]) {
                                logger.warning
                                    (lines+": time point mismatched; "
                                     +"expecting T"+TIMES[i]+" but got "
                                     +header[j]+"!");
                            }
                            else if (toks[j] != null && toks[j+1] != null) {
                                Measure m = new Measure (repl);
                                m.setName(toks[0]);
                                m.setTime((double)TIMES[i], TimeUnit.MINUTES);
                                double res = Double.parseDouble(toks[j]);
                                double std = Double.parseDouble(toks[j+1]);
                                m.setResponse(res/std);
                                sampl.add(m);
                            }
                        }
                        catch (NumberFormatException ex) {
                            logger.warning(lines+": bogus number: "+line+"; either \""+toks[j]+"\" or \""+toks[j+1]+"\" is bogus!");
                        }
                    }
                }
                //logger.info(sample+": "+sampl.size()+" measurement(s)");
            }
        }
        return sampl;
    }

    static String[] tokenizer (String line, char delim) {
        List<String> toks = new ArrayList<String>();

        int len = line.length(), parity = 0;
        StringBuilder curtok = new StringBuilder ();
        for (int i = 0; i < len; ++i) {
            char ch = line.charAt(i);
            if (ch == '"') {
                parity ^= 1;
            }
            if (ch == delim) {
                if (parity == 0) {
                    String tok = null;
                    if (curtok.length() > 0) {
                        tok = curtok.toString();
                    }
                    toks.add(tok);
                    curtok.setLength(0);
                }
                else {
                    curtok.append(ch);
                }
            }
            else if (ch != '"') {
                curtok.append(ch);
            }
        }

        if (curtok.length() > 0) {
            toks.add(curtok.toString());
        }
        // if the line ends with the delimiter, then append an empty token
        else if (line.charAt(line.length()-1) == delim)
            toks.add(null); 


        return toks.toArray(new String[0]);
    }

    public static void main (String[] argv) throws Exception {
        Reader reader;
        if (argv.length == 0) {
            logger.info("Reading from stdin...");
            reader = new CsvReader (System.in);
        }
        else {
            logger.info("Reading from \""+argv[0]+"\"...");
            reader = new CsvReader (new FileInputStream (argv[0]));
        }

        Estimator estimator = new LeastSquaresEstimator ();
        int c = 0;
        for (Sample sampl; (sampl = reader.read()) != null; ) {
            if (sampl.size() > 2) {
                List<Estimator.Result> results = estimator.estimate(sampl);
                for (Estimator.Result res : results) {
                    System.out.println(res);
                }
            }
            else {
                logger.warning(sampl.getName()+": too few measurements ("
                               +sampl.size()+")");
            }
        }
    }
}
