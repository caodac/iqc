package tripod.iqc.validator;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.text.DecimalFormat;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import java.awt.Color;
import java.awt.event.*;
import java.awt.BorderLayout;
import java.awt.Component;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.jfree.chart.*;
import org.jfree.chart.plot.*;
import org.jfree.data.Range;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.DefaultXYDataset;
import org.jfree.chart.renderer.xy.*;
import org.jfree.chart.annotations.*;
import org.jfree.chart.axis.LogAxis;
import org.jfree.chart.axis.LogarithmicAxis;

import chemaxon.struc.Molecule;
import chemaxon.util.MolHandler;
import chemaxon.marvin.beans.MViewPane;
import chemaxon.formats.MolImporter;

import org.apache.http.*;
import org.apache.http.client.*;
import org.apache.http.impl.client.*;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.client.methods.*;

import tripod.iqc.core.*;
import lychi.LyChIStandardizer;

public class IQCValidator extends JFrame 
    implements ActionListener, ListSelectionListener, TreeSelectionListener {
    static final Logger logger = 
        Logger.getLogger(IQCValidator.class.getName());

    static final String[] ENZYMES = {
        "3A4",
        "2C9"
    };
    
    static final String URL_BASE = System.getProperty
        ("iqc-web2", "http://tripod.nih.gov");
    //static final String URL_BASE = "http://localhost:8080";

    enum CLUnit {
        mL_min_pmol,
            mL_hr_nmol
            }

    enum Format {
        Txt, Csv;
    }

    static double DEFAULT_CYP_CONC = 29.03;

    static final ImageIcon STATUS_GREEN = new ImageIcon 
        (IQCValidator.class.getResource("resources/status.png"));
    static final ImageIcon STATUS_GRAY = new ImageIcon 
        (IQCValidator.class.getResource("resources/status-offline.png"));
    static final ImageIcon STATUS_YELLOW = new ImageIcon 
        (IQCValidator.class.getResource("resources/status-away.png"));
    static final ImageIcon STATUS_RED = new ImageIcon 
        (IQCValidator.class.getResource("resources/status-busy.png"));
    static final ImageIcon TOGGLE = new ImageIcon 
        (IQCValidator.class.getResource("resources/toggle.png"));
    static final ImageIcon TOGGLE_EXPAND = new ImageIcon 
        (IQCValidator.class.getResource("resources/toggle-expand.png"));
    static final ImageIcon STAR_FULL = new ImageIcon 
        (IQCValidator.class.getResource("resources/star.png"));
    static final ImageIcon STAR_EMPTY = new ImageIcon 
        (IQCValidator.class.getResource("resources/star-empty.png"));
    static final ImageIcon STAR_HALF = new ImageIcon 
        (IQCValidator.class.getResource("resources/star-half.png"));

    static final Map<String, Set<String>> SAMPLE_NAMES =
        new HashMap<String, Set<String>>();
    static {
        try {
            BufferedReader br = new BufferedReader
                (new InputStreamReader
                 (IQCValidator.class.getResourceAsStream
                  ("resources/name2sample.txt")));
            for (String line; (line = br.readLine()) != null; ) {
                String[] toks = line.split("\t");
                Set<String> samples = new HashSet<String>();
                for (int i = 1; i < toks.length; ++i)
                    samples.add(toks[i]);
                if (!samples.isEmpty()) {
                    SAMPLE_NAMES.put(toks[0], samples);
                    for (String name : samples)
                        SAMPLE_NAMES.put(name, samples);
                }
            }
            br.close();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    static class SampleData {
        Sample sample;
        List<Estimator.Result> results;
        DefaultXYDataset dataset;
        DefaultXYDataset ratioDS; // ratio dataset
        boolean stable = false;

        SampleData (Sample sample, List<Estimator.Result> results) {
            this.sample = sample;
            this.results = results;
        }

        public int size () { return results.size(); }
        public Estimator.Result getResult (int i) { return results.get(i); }
        public List<Estimator.Result> getResults () { return results; }

        public XYDataset getDataset () {
            instrumentDatasets ();
            return dataset;
        }

        public XYDataset getRatioDataset () {
            instrumentDatasets ();
            return ratioDS;
        }

        protected void instrumentDatasets () {
            if (dataset != null || ratioDS != null) {
                return;
            }
            dataset = new DefaultXYDataset ();
            ratioDS = new DefaultXYDataset ();
            
            int[] repls = sample.getReplicates();
            Double minLnRes = null, maxLnRes = null;
            for (int k = 0; k < repls.length; ++k) {
                List<Measure> measures = new ArrayList<Measure>();
                Double r0 = null;
                
                for (Measure m : sample.getMeasures(repls[k])) {
                    Double r = m.getResponse();
                    Double t = m.getTime();
                    if (m.getBlank() || r == null || t == null) {
                        // skip this 
                    }
                    else {
                        if (t.equals(0.)) {
                            r0 = r;
                            minLnRes = Math.log(r);
                        }
                        measures.add(m);
                    }
                }
                
                if (!measures.isEmpty()) {
                    double[][] data = new double[2][measures.size()];
                    double[][] ratio = r0 != null 
                        ? new double[2][measures.size()] : null;
                    for (int i = 0; i < measures.size(); ++i) {
                        Double r = measures.get(i).getResponse();
                        Double t = measures.get(i).getTime();
                        data[0][i] = maxLnRes = Math.log(r); // y
                        data[1][i] = t; // x
                        
                        if (ratio != null) {
                            ratio[0][i] = 100*(r/r0); // y
                            ratio[1][i] = t; // x
                        }
                    }
                    
                    String series = sample.getName();
                    if (repls.length > 1) {
                        series += "-"+repls[k];
                    }
                    dataset.addSeries(series, data);
                    ratioDS.addSeries(series, ratio != null 
                                      ? ratio : new double[2][0]);
                }
            }

            if (minLnRes != null && maxLnRes != null)
                stable = Math.abs(maxLnRes - minLnRes) < .5
                    || maxLnRes > minLnRes;
            //logger.info(sample.getName()+": min="+minLnRes+" max="+maxLnRes+" => "+stable);
        }

        public boolean isStable () { return stable; }
        public String getName () { return sample.getName(); }
        public String toString () { 
            return sample.getName();
        }
    }

    class LoadFileWorker extends SwingWorker<Throwable, Void> {
        File file;
        Format format;
        DefaultMutableTreeNode root;
        
        LoadFileWorker (Format f, File file) {
            this.format = f;
            this.file = file;
        }

        @Override
        protected Throwable doInBackground () {
            try {
                switch (format) {
                case Txt:
                    root = loadSampleTreeTxt
                        (false, new FileInputStream (file));
                    break;
                case Csv:
                    root = loadSampleTreeCsv (new FileInputStream (file));
                    break;
                default:
                    return new RuntimeException
                        ("Unknown file format: "+format);
                }
            }
            catch (Exception ex) {
                return ex;
            }
            return null;
        }

        @Override
        protected void done () {
            try {
                Throwable t = get ();
                if (t != null) {
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, t.getMessage(), 
                         "Error", JOptionPane.ERROR_MESSAGE);
                }
                else {
                    setSampleTreeModel(file.getName(), root);
                }
            }
            catch (Exception ex) {
            }
        }
    }

    class LoadSavedResults extends SwingWorker<Throwable, Void> {
        String dataset;
        DefaultMutableTreeNode root;
        Map<String, Boolean> saves = new HashMap<String, Boolean>();
        Map<String, String> curators = new HashMap<String, String>();
        
        LoadSavedResults (String dataset, DefaultMutableTreeNode root) {
            this.dataset = dataset;
            this.root = root;
        }

        @Override
        protected Throwable doInBackground () {
            try {
                URL url = new URL 
                    (URL_BASE+"/iqc-web2/annotation/"
                     +(dataset != null ? dataset.replaceAll(" ","%20") : ""));

                logger.info("Retrieving saved results for \""+dataset+"\"...");
                BufferedReader br = new BufferedReader 
                    (new InputStreamReader (url.openStream()));

                int count = 0;
                Set<String> seen = new HashSet<String>();
                for (String line; (line = br.readLine()) != null; ++count) {
                    String[] toks = line.split("\t");
                    if (toks.length >= 3) {
                        int pos = toks[0].indexOf('[');
                        String id = pos > 0
                            ? toks[0].substring(0, pos) : toks[0];

                        if (!seen.contains(id)) {
                            boolean set = Integer.parseInt(toks[1]) == 1;
                            saves.put(toks[0], set);
                            if (toks.length > 3)
                                curators.put(toks[0], toks[3]);
                            if (set)
                                seen.add(id);
                        }
                    }
                    System.out.println(line);
                }
                br.close();

                logger.info(dataset+": "+count+" saved results!");
            }
            catch (Exception ex) {
                ex.printStackTrace();
                return ex;
            }
            return null;
        }

        @Override
        protected void done () {
            try {
                Throwable t = get ();
                if (t != null) {
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, t.getMessage(), 
                         "Error", JOptionPane.ERROR_MESSAGE);
                }
                else {
                    // now iterate through all the results and identify
                    // which ones are saved
                    savedResults.clear(); // clear the current results
                    
                    for (Enumeration en = root.depthFirstEnumeration();
                         en.hasMoreElements(); ) {
                        DefaultMutableTreeNode node = 
                            (DefaultMutableTreeNode)en.nextElement();
                        Object obj = node.getUserObject();
                        if (obj != null && obj instanceof SampleData) {
                            SampleData data = (SampleData)obj;
                            for (Estimator.Result r : data.getResults()) {
                                Boolean b = saves.get(r.getId());
                                if (b != null) {
                                    savedResults.put(r, b);
                                    if (data.getName().startsWith("NCGC")) {
                                        if (!r.getScore().isNaN()
                                            && !r.getScore().isInfinite()
                                            && r.getHalflife() > 0) {
                                            Estimator.Result rr = 
                                                annotatedResults.get
                                                (data.getName());
                                            if (rr == null 
                                                || (r.getScore() 
                                                    > rr.getScore())) {
                                                annotatedResults.put
                                                    (data.getName(), r);
                                                annotatedData.put
                                                    (data.getName(), data);
                                            }
                                            allAnnotatedResults.add(r);
                                        }
                                    }
                                    else {
                                        List<Estimator.Result> results =
                                            controlResults.get(dataset);
                                        if (results == null) {
                                            results = new ArrayList<Estimator.Result>();
                                            controlResults.put
                                                (dataset, results);
                                        }
                                        results.add(r);
                                    }
                                }
                            }
                        }
                    }
                    logger.info(dataset+": "+savedResults.size()
                                +" saved results retrieved!");

                    sampleTree.repaint();
                }
            }
            catch (Exception ex) {
                JOptionPane.showMessageDialog
                    (IQCValidator.this, ex.getMessage(), 
                     "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }


    void dumpAnnotations () throws IOException {
        TreeMap<String, Estimator.Result> sorted = 
            new TreeMap<String, Estimator.Result>
            (new Comparator<String>() {
                    public int compare (String s1, String s2) {
                        Estimator.Result r1 = annotatedResults.get(s1);
                        Estimator.Result r2 = annotatedResults.get(s2);
                        return r1.compareTo(r2);
                    }
                });
        sorted.putAll(annotatedResults);
        
        PrintWriter pw = new PrintWriter
            (new FileWriter ("iqc-annotated-results.csv"));
        pw.println("Sample,Score,t1/2");
        PrintWriter sdf = new PrintWriter
            (new FileWriter ("iqc-annotated-results.sdf"));
        for (Map.Entry<String, Estimator.Result> me
                 : sorted.entrySet()) {
            Estimator.Result r = me.getValue();
            pw.println(me.getKey()
                       +","+String.format("%1$.3f",r.getScore())
                       +","+String.format("%1$.3f",r.getHalflife()));
            Molecule m = getMol (me.getKey());
            if (m != null) {
                m = m.cloneMolecule();
                m.setProperty("SCORE", String.format("%1$.3f",r.getScore()));
                m.setProperty("T1/2",String.format("%1$.3f",r.getHalflife()));
                sdf.print(m.toFormat("sdf"));
            }
        }
        pw.close();
        sdf.close();
    }

    void dumpControls () throws IOException {
        PrintWriter pw =
            new PrintWriter (new FileWriter ("iqc-control-results.csv"));
        pw.print("Sample,Formula,MolWt,Hash,Dataset,CLint,t1/2");
        int[] measures = {0,5,10,15,30,60};
        for (int i = 0; i < measures.length; ++i)
            pw.print(",T"+measures[i]);
        pw.println();
        Set<String> hashes = new HashSet<String>();
        for (Map.Entry<String, List<Estimator.Result>> me
                 : controlResults.entrySet()) {
            for (Estimator.Result r : me.getValue()) {
                Molecule mol = getMol(r.getSample().getName());
                if (mol != null) {
                    String h = mol.getProperty("HASH");
                    pw.print(r.getSample().getName()
                             +","+mol.getFormula()
                             +","+mol.getMass()
                             +","+h
                             +","+me.getKey()
                             +","+String.format("%1$.2f",r.getCLint())
                             +","+String.format("%1$.2f",r.getHalflife()));
                    for (int i = 0; i < measures.length; ++i) {
                        Measure m = r.getMeasures()[i];
                        pw.print(","+String.format("%1.5f",m.getResponse()));
                    }
                    pw.println();
                    hashes.add(h);
                }
                else {
                    logger.warning("Can't retrieve molecule for "
                                   +r.getSample().getName());
                }
            }
        }
        
        for (Estimator.Result r : allAnnotatedResults) {
            Molecule mol = getMol(r.getSample().getName());
            if (mol != null) {
                String h = mol.getProperty("HASH");
                if (hashes.contains(h)) {
                    pw.print(r.getSample().getName()
                             +","+mol.getFormula()
                             +","+mol.getMass()
                             +","+h
                             +","
                             +","+String.format("%1$.2f",r.getCLint())
                             +","+String.format("%1$.2f",r.getHalflife()));
                    for (int i = 0; i < measures.length; ++i) {
                        Measure m = r.getMeasures()[i];
                        pw.print(","+String.format("%1.5f",m.getResponse()));
                    }
                    pw.println();
                }
            }
            else {
                logger.warning("Can't retrieve molecule for \""
                               +r.getSample().getName()+"\"");
            }
        }
        pw.close();
    }

    void dumpSamples () throws IOException {
        PrintStream ps = new PrintStream
            (new FileOutputStream  ("samples.txt"));
        for (String s : samples) {
            ps.println(s);
        }
        ps.close();
    }

    class UploadFileWorker extends SwingWorker<Throwable, Void> {
        File file;
        String status;
        String dataset;

        UploadFileWorker (String dataset, File file) {
            if (dataset == null)
                throw new IllegalArgumentException ("No dataset specified!");
            this.dataset = dataset;
            this.file = file;
        }

        @Override
        protected Throwable doInBackground () {
            try {
                HttpClient client = new DefaultHttpClient();
                HttpPost post = new HttpPost
                    (URL_BASE+"/iqc-web2/datasets/");

                FileBody uploadFilePart = new FileBody
                    (file, dataset+"/"+file.getName(), "plain/text", "utf8");
                MultipartEntity reqEntity = new MultipartEntity();
                reqEntity.addPart("upload-file", uploadFilePart);
                post.setEntity(reqEntity);

                HttpResponse response = client.execute(post);
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    try {
                        BufferedReader br = new BufferedReader
                            (new InputStreamReader (entity.getContent()));
                        status = br.readLine();
                        logger.info("## "+status);
                        br.close();
                    }
                    catch (IOException ex) {
                        return ex;
                    }
                }
                else {
                    logger.warning("Upload dataset returns nothing!");
                }
            }
            catch (Exception ex) {
                return ex;
            }
            return null;
        }

        @Override
        protected void done () {
            try {
                Throwable t = get ();
                if (t != null) {
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, t.getMessage(), 
                         "Error", JOptionPane.ERROR_MESSAGE);
                }
                else if (status != null) {
                    // refresh the datasets
                    new LoadDatasets().execute();

                    JOptionPane.showMessageDialog
                        (IQCValidator.this, "Successfully uploaded dataset\n"
                         +file+"!\n"+status, 
                         "INFO", JOptionPane.INFORMATION_MESSAGE);
                }
            }
            catch (Exception ex) {
                JOptionPane.showMessageDialog
                    (IQCValidator.this, ex.getMessage(), 
                     "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    class ConvertFileWorker extends SwingWorker<Throwable, Void> {
        File file;
        File out;

        ConvertFileWorker (File file, File out) {
            this.file = file;
            this.out = out;
            
            JOptionPane.showMessageDialog
                (IQCValidator.this,
                 "The conversion is running in the background; you may\n"
                 +"safely close this dialog. When the conversion finishes,\n"
                 +"you will be notified!",
                 "INFO", JOptionPane.INFORMATION_MESSAGE);
        }

        @Override
        protected Throwable doInBackground () {
            logger.info("## writing output file... "+out);
            try {
                ZipFile zf = new ZipFile (file);
                IqcToTxt txt = new IqcToTxt (zf);
                FileOutputStream fos = new FileOutputStream (out);
                txt.write(fos);
                fos.close();
            }
            catch (Exception ex) {
                logger.log(Level.SEVERE,
                           "Can't perform conversion for file '"+file+"'!", ex);
                return ex;
            }
            return null;
        }

        @Override
        protected void done () {
            try {
                Throwable t = get ();
                if (t != null) {
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, t.getMessage(), 
                         "Error", JOptionPane.ERROR_MESSAGE);
                }
                else {
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, "Successfully converted "
                         +file+"!\nto file \""+out+"\"!", 
                         "INFO", JOptionPane.INFORMATION_MESSAGE);
                }
            }
            catch (Exception ex) {
                JOptionPane.showMessageDialog
                    (IQCValidator.this, ex.getMessage(), 
                     "Error", JOptionPane.ERROR_MESSAGE);               
            }
        }
    }

    class LoadDatasets extends SwingWorker<Throwable, Void> {
        ArrayList<JMenuItem> items = new ArrayList<JMenuItem>();

        LoadDatasets () {
        }

        @Override
        protected Throwable doInBackground () {
            try {
                URL url = new URL (URL_BASE+"/iqc-web2/datasets/");
                BufferedReader br = new BufferedReader 
                    (new InputStreamReader (url.openStream()));
                
                Map<String, JMenu> menu = new HashMap<String, JMenu>();
                for (String enz : ENZYMES) {
                    JMenu m = new JMenu (enz);
                    menu.put(enz, m);
                    items.add(m);
                }
                
                for (String name; (name = br.readLine()) != null; ) {
                    String[] tokens = name.split("\t");
                    int count = 0;
                    if (tokens.length == 2) {
                        name = tokens[0];
                        try {
                            count = Integer.parseInt(tokens[1]);
                        }
                        catch (NumberFormatException ex) {
                        }
                    }

                    String full = name;
                    int pos = name.indexOf('/');
                    JMenu parent = null;
                    if (pos > 0) {
                        String sub = name.substring(0, pos);
                        parent = menu.get(sub);
                        if (parent == null) {
                            menu.put(sub, parent = new JMenu (sub));
                            items.add(parent);
                        }
                        name = name.substring(pos+1);
                    }

                    JMenuItem item = createMenuItem (full, name);
                    if (item != null) {
                        item.putClientProperty("fullname", full);
                        if (count == 0)
                            item.setIcon(STAR_EMPTY);
                        else if (count < 10)
                            item.setIcon(STAR_HALF);
                        else
                            item.setIcon(STAR_FULL);

                        if (parent != null) {
                            parent.add(item);
                        }
                        else {
                            items.add(item);
                        }
                    }
                }
            }
            catch (Exception ex) {
                return ex;
            }
            return null;
        }

        JMenuItem createMenuItem (String full, String name) throws Exception {
            logger.info("## loading "+name+"...");
            URL url = new URL
                (URL_BASE+"/iqc-web2/datasets/"+full.replaceAll(" ", "%20"));

            boolean correction = 
                name.indexOf("SP118414_20130816_CYP34A_Stab_Data_Final.txt")
                >= 0 || name.indexOf
                ("SP118414_20130830_CYP3A4_Stability_Data.txt") >= 0;
            
            JMenuItem item = null;
            int pos = name.lastIndexOf('.');
            if (pos > 0) {
                String ext = name.substring(pos);
                if (ext.equals(".txt") || ext.equals(".TXT")) {
                    item = new JMenuItem (name);
                    item.addActionListener(loadAction);
                    DefaultMutableTreeNode root = loadSampleTreeTxt 
                        (correction, url.openStream());
                    logger.info(name+": "+root.getChildCount()+" sample(s) read!");                 
                    //new LoadSavedResults (name, root).execute();
                    item.putClientProperty("samples", root);
                }
                else if (ext.equals(".csv") || ext.equals(".CSV")) {
                    item = new JMenuItem (name);
                    item.addActionListener(loadAction);
                    DefaultMutableTreeNode root = loadSampleTreeCsv
                        (url.openStream());
                    logger.info("########### "+name+": "+root.getChildCount()
                                +" sample(s) read #############");
                    //new LoadSavedResults (name, root).execute();
                    item.putClientProperty("samples", root);
                }
                else if (ext.equals(".sdf")) {
                    LyChIStandardizer lychi = new LyChIStandardizer ();
                    MolImporter mi = new MolImporter (url.openStream());
                    /*
                    PrintStream ps = new PrintStream
                    (new FileOutputStream ("samples-lychi.sdf"));*/
                    for (Molecule m; (m = mi.read()) != null; ) {
                        String n = m.getName();
                        pos = n.indexOf('-');
                        if (pos > 0) {
                            n = n.substring(0, pos);
                            m.setName(n);
                        }
                        /*
                        Molecule clone = m.cloneMolecule();
                        lychi.standardize(clone);
                        String[] hash = lychi.hashKeyArray(clone);
                        m.setProperty("HASH", hash[2]);
                        ps.print(m.toFormat("sdf"));
                        */
                        molDb.put(n, m);
                    }
                    //ps.close();
                    logger.info("loaded "+molDb.size()
                                +" structures from "+name);
                }
            }
            return item;
        }

        @Override
        protected void done () {
            try {
                Throwable t = get ();
                if (t != null) {
                    t.printStackTrace();
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, t.getMessage(), 
                         "ERROR", JOptionPane.ERROR_MESSAGE);
                }
                else {
                    datasetMenu.removeAll();
                    for (JMenuItem item : items) {
                        datasetMenu.add(item);
                    }
                }
            }
            catch (Exception ex) {
                JOptionPane.showMessageDialog
                    (IQCValidator.this, ex.getMessage(), 
                     "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    class PersistSavedResults extends SwingWorker<Throwable, Void> {
        String dataset;
        String message;

        PersistSavedResults (String dataset) {
            this.dataset = dataset;
        }

        @Override
        protected Throwable doInBackground () {
            /*
            try {
                System.out.println("### "+dataset+" ###");
                for (Map.Entry<Estimator.Result, Boolean> me : 
                         savedResults.entrySet()) {
                    Estimator.Result r = me.getKey();
                    System.out.println(r.getId()+","+me.getValue());
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
            */
            
            try {
                URL url = new URL (URL_BASE+"/iqc-web2/annotation/"
                                   +dataset.replaceAll(" ", "%20"));
                URLConnection con = url.openConnection();
                con.setDoOutput(true);
                con.setDoInput(true);

                PrintStream ps = new PrintStream (con.getOutputStream());
                for (Map.Entry<Estimator.Result, Boolean> me : 
                         savedResults.entrySet()) {
                    Estimator.Result r = me.getKey();
                    ps.println(r.getId()+"|"+me.getValue()+"|"+curator);
                }
                
                BufferedReader br = new BufferedReader
                    (new InputStreamReader (con.getInputStream()));
                message = br.readLine();
            }
            catch (Exception ex) {
                return ex;
            }
            return null;
        }

        @Override
        protected void done () {
            try {
                Throwable t = get ();
                if (t != null) {
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, t.getMessage(), 
                         "Error", JOptionPane.ERROR_MESSAGE);
                }
                else if (message != null) {
                    String[] toks = message.split("[\\s]+");
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, "Successfully saved "
                         +toks[1]+" result(s) for\n"+toks[0],
                         "INFO", JOptionPane.INFORMATION_MESSAGE);
                    saveDirty = false;
                }
                else {
                    JOptionPane.showMessageDialog
                        (IQCValidator.this, 
                         "No message received from server; "
                         +"please try saving again!",
                         "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
            catch (Exception ex) {
                JOptionPane.showMessageDialog
                    (IQCValidator.this, ex.getMessage(), 
                     "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    class SampleTreeNode extends DefaultMutableTreeNode {
        Estimator estimator = new LeastSquaresEstimator ();

        SampleTreeNode (Sample sample) {
            // calculate fit for parent sample
            SampleData data = new SampleData 
                (sample, estimator.estimate(sample));
            setUserObject (data);

            int[] repls = sample.getReplicates();
            if (repls.length > 2) {
                for (int k = 0; k < repls.length; ++k) {
                    List<Measure> measures = sample.getMeasures(repls[k]);
                    if (measures.size() > 1) {
                        // create a dummy sample contains these measures
                        Sample s = new Sample (sample.getName()+"-"+repls[k]);
                        for (Measure m : measures) {
                            s.add(m);
                        }
                        
                        // and child replicates
                        //logger.info(s.getName()+" "+s.getReplicateCount());
                        add (new SampleTreeNode (s));
                    }
                }
            }
        }

        public SampleData getData () { 
            return (SampleData) getUserObject (); 
        }
    }

    class ResultTableModel extends AbstractTableModel {
        List<Estimator.Result> results;
        String[] columns = new String[] {
            "Save?",
            "Rank",
            "Score",
            "Selection",
            "pCLint",
            "t1/2",
            "Slope",
            "Intercept",
            "MSE",
            "r^2"
        };

        XYDataset[] datasets;
        XYAnnotation[][] annotations;

        ResultTableModel () {
        }

        public int getColumnCount () { return columns.length; }
        public String getColumnName (int col) { return columns[col]; }
        public Class getColumnClass (int col) {
            switch (col) {
            case 0: return Boolean.class; // Save
            case 1: return Integer.class; // Rank
            case 2: return Double.class; // Score
            case 3: return String.class; // Selection
            case 4: return Double.class; // CLint
            case 5: return Double.class; // t1/2
            case 6: return Double.class; // Slope
            case 7: return Double.class; // Intercept
            case 8: return Double.class; // MSE
            case 9: return Double.class; // R
            }
            return Object.class;
        }

        public int getRowCount () { 
            return results != null ? results.size() : 0;
        }

        public Object getValueAt (int row, int col) {
            Estimator.Result result = results.get(row);
            FitModel model = result.getModel();

            switch (col) {
            case 0: {
                Boolean save = savedResults.get(result);
                return save != null && save;
            }
            case 1: return result.getRank();
            case 2: return result.getScore();
            case 3: 
                { StringBuilder sb = new StringBuilder ();
                    int[] config = result.getConfig();
                    for (int i = 0; i < config.length; ++i) {
                        if (config[i] > 0) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append((i+1));
                        }
                    }
                    return sb.toString();
                }
            case 4:
                return result.getCLint();

            case 5:
                /*
                { FitModel.Variable var = model.getVariable("Slope");
                    return var != null && Math.abs(var.getValue()) > 1e-6 
                        ? -Math.log(2)/var.getValue() : null;
                }
                */
                return result.getHalflife();
            }

            FitModel.Variable var = model.getVariable(columns[col]);
            return var != null ? var.getValue() : null;
        }

        @Override
        public boolean isCellEditable (int row, int col) {
            return col == 0;
        }

        public void setValueAt (Object value, int row, int col) {
            if (col == 0) {
                logger.info("Row "+row+" save? "+value);
                Estimator.Result result = results.get(row);
                savedResults.put(result, (Boolean)value);
                saveDirty = true;
            }
            else {
                throw new IllegalArgumentException
                    ("Column "+col+" is not editable!");
            }
        }

        public void refresh () {
            fireTableDataChanged ();
        }

        public void setResults (List<Estimator.Result> results) {
            this.results = results;
            if (results != null) {
                datasets = new XYDataset[results.size()];
                annotations = new XYAnnotation[results.size()][];
            }
            else {
                datasets = null;
                annotations = null;
            }
            fireTableDataChanged ();
        }

        public void clear () {
            setResults (null);
        }

        public Estimator.Result getResult (int row) {
            return results.get(row);
        }

        public XYDataset getDataset (int row) {
            XYDataset xy = datasets[row];
            if (xy == null) {
                Estimator.Result result = results.get(row);
                FitModel model = result.getModel();

                FitModel.Variable slope = model.getVariable("Slope");
                FitModel.Variable intercept = model.getVariable("Intercept");

                xy = datasets[row] = new DefaultXYDataset ();
                double[][] data = new double[2][2];
                data[0][0] = -1*slope.getValue() + intercept.getValue(); 
                data[0][1] = 65.*slope.getValue() + intercept.getValue();
                data[1][0] = -1;
                data[1][1] = 65; 

                ((DefaultXYDataset)xy).addSeries
                    (result.getSample().getName()+"-fitted", data);
            }
            return xy;
        }

        public XYAnnotation[] getAnnotations (int row) {
            XYAnnotation[] annos = annotations[row];
            if (annos == null) {
                Estimator.Result result = results.get(row);

                List<XYAnnotation> anno = new ArrayList<XYAnnotation>();
                Measure[] measures = result.getMeasures();
                int[] config = result.getConfig();
                for (int i = 0; i < config.length; ++i) {
                    if (config[i] > 0) {
                        Measure m = measures[i];
                        double angle = -Math.PI/4.;
                        if (i == 0) {
                            angle = 0.;
                        }
                        else if (i+1 == config.length) {
                            angle = -3*Math.PI/4.;
                        }
                        Double r = m.getResponse();
                        Double t = m.getTime();
                        if (r != null && t != null) {
                            XYAnnotation a = new XYPointerAnnotation
                                (String.valueOf(i+1), Math.log(r), t, angle);
                            anno.add(a);
                        }
                    }
                }
                annotations[row] = annos = anno.toArray(new XYAnnotation[0]);
            }
            return annos;
        }
    }

    static class NumericalCellRenderer extends DefaultTableCellRenderer {
        int precisions;

        NumericalCellRenderer () {
            this (3);
        }

        NumericalCellRenderer (int precisions) {
            this.precisions = precisions;
            setHorizontalAlignment (TRAILING);
        }

        public Component getTableCellRendererComponent 
            (JTable table, Object value, boolean selected, boolean focus,
             int row, int column) {

            if (value != null) {
                double x = (Double)value;
                if (Math.abs(x) < 1e-9) {
                    value = String.format("%1$.0f", x);
                }
                else if (Math.abs(x) < 0.01) {
                    value = String.format("%1$.3e", x);
                }
                else {
                    value = String.format("%1$."+precisions+"f", x);
                }
            }
            return super.getTableCellRendererComponent
                (table, value, selected, focus, row, column);
        }
    }

    class SampleTreeCellRenderer extends DefaultTreeCellRenderer {
        SampleTreeCellRenderer () {
            setClosedIcon (TOGGLE_EXPAND);
            setOpenIcon (TOGGLE);
        }

        @Override
        public Component getTreeCellRendererComponent
            (JTree tree, Object value, boolean sel, boolean expanded, 
             boolean leaf, int row, boolean hasFocus) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode)value;
            if (node != null) {
                Object obj = node.getUserObject();
                if (obj instanceof SampleData) {
                    SampleData data = (SampleData)obj;
                    Icon icon = STATUS_GRAY;
                    for (Estimator.Result r : data.getResults()) {
                        Boolean b = savedResults.get(r);
                        if (null != b && b) {
                            icon = STATUS_GREEN;
                            break;
                        }
                    }
                    setLeafIcon (icon);
                }
            }
            return super.getTreeCellRendererComponent
                (tree, value, sel, expanded, leaf, row, hasFocus);
        }
    }

    Map<String, Estimator.Result> annotatedResults = 
        new HashMap<String, Estimator.Result>();
    Map<String, SampleData> annotatedData = new HashMap<String, SampleData>();
    Map<String,List<Estimator.Result>> controlResults =
        new HashMap<String, List<Estimator.Result>>();
    List<Estimator.Result> allAnnotatedResults =
        new ArrayList<Estimator.Result>();

    private JFileChooser chooser;
    private JTree sampleTree;
    private JTable resultTab;
    private ChartPanel chartPane1; // ln(response) vs time
    private ChartPanel chartPane2; // % response vs time
    private MViewPane mview;
    private JTextField cypConc;
    private JLabel plotHeader;
    private JComboBox unitCb;
    private JMenu datasetMenu;

    private boolean saveDirty = false;
    private Map<Estimator.Result, Boolean> savedResults = 
        new ConcurrentHashMap<Estimator.Result, Boolean>();
    private String curator = "anonymous";

    private Map<String, Molecule> molDb = 
        new ConcurrentHashMap<String, Molecule>();

    private ActionListener loadAction = new ActionListener () {
            public void actionPerformed (ActionEvent e) {
                JMenuItem item = (JMenuItem)e.getSource();
                DefaultMutableTreeNode root =
                    (DefaultMutableTreeNode) item
                    .getClientProperty("samples");
                if (confirmedSave ()) {
                    //setSampleTreeModel (item.getText(), root);
                    setSampleTreeModel ((String)item.getClientProperty
                                        ("fullname"), root);
                }
            }
        };


    public IQCValidator (String[] argv) {
        initUI ();
        if (argv != null && argv.length > 0) {
            try {
                loadSamples (false, new FileInputStream (argv[0]));
            }
            catch (IOException ex) {
                JOptionPane.showMessageDialog
                    (this, ex.getMessage(), 
                     "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    protected void initUI () {
        chooser = new JFileChooser (".");

        setDefaultCloseOperation (JFrame.EXIT_ON_CLOSE);
        setJMenuBar (createMenuBar ());

        JSplitPane split = new JSplitPane (JSplitPane.HORIZONTAL_SPLIT);
        split.setDividerSize(3);
        split.setResizeWeight(.25);
        split.setLeftComponent(createSamplePane ());
        split.setRightComponent(createContentPane ());
        
        JPanel top = new JPanel (new BorderLayout ());
        top.add(split);
        getContentPane().add(top);
        pack ();

        try {
            curator = System.getProperty("user.name");
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    protected JComponent createSamplePane () {
        sampleTree = new JTree ();
        sampleTree.setModel(new DefaultTreeModel (null));
        sampleTree.setRootVisible(false);
        sampleTree.getSelectionModel().setSelectionMode
            (TreeSelectionModel.SINGLE_TREE_SELECTION);
        sampleTree.getSelectionModel().addTreeSelectionListener(this);
        sampleTree.setCellRenderer(new SampleTreeCellRenderer ());

        JSplitPane split = new JSplitPane (JSplitPane.VERTICAL_SPLIT);
        split.setDividerSize(3);
        split.setResizeWeight(.75);
        split.setTopComponent(new JScrollPane (sampleTree));
        split.setBottomComponent(createMolPane ());

        JTabbedPane tab = new JTabbedPane ();
        tab.addTab("Samples", split);
        //tab.addTab("Stats", createStatsPane ());

        JPanel pane = new JPanel (new BorderLayout ());
        pane.add(tab);

        return pane;
    }

    protected JComponent createStatsPane () {
        JPanel pane = new JPanel ();
        return pane;
    }

    protected JComponent createMolPane () {
        JPanel pane = new JPanel (new BorderLayout ());
        mview = new MViewPane ();
        pane.add(mview);

        return mview;
    }

    protected JComponent createContentPane () {
        JPanel pane = new JPanel (new BorderLayout ());
        JSplitPane split = new JSplitPane (JSplitPane.VERTICAL_SPLIT);
        split.setDividerSize(3);
        split.setResizeWeight(.5);
        split.setTopComponent(createPlotPane ());
        split.setBottomComponent(createResultPane ());
        pane.add(split);
        return pane;
    }

    protected JComponent createPlotPane () {
        chartPane1 = new ChartPanel
            (ChartFactory.createScatterPlot
             (null, "Ln (Response)", "Time (Minute)", null, 
              PlotOrientation.HORIZONTAL, true, false, false));
        chartPane1.setBackground(Color.white);
        chartPane1.getChart().setBorderPaint(Color.white);
        chartPane1.getChart().setBackgroundPaint(Color.white);
        chartPane1.getChart().getPlot().setBackgroundAlpha(.5f);
        chartPane1.getChart().getXYPlot().setRangeGridlinesVisible(false);
        chartPane1.getChart().getXYPlot().setDomainGridlinesVisible(false);

        XYItemRenderer def = new XYLineAndShapeRenderer ();
        def.setSeriesPaint(2, Color.blue);
        
        // main renderer
        XYItemRenderer renderer1 = new  XYLineAndShapeRenderer ();
        //renderer1.setSeriesPaint(0, Color.black);
        chartPane1.getChart().getXYPlot().setRenderer(0, renderer1);
        chartPane1.getChart().getXYPlot().setRenderer(2, def); 

        XYItemRenderer fit;
        chartPane1.getChart().getXYPlot().setRenderer
            (1, fit = new  XYLineAndShapeRenderer ());// mask renderer
        fit.setSeriesPaint(0, Color.black);
        fit.setSeriesVisibleInLegend(1, false);

        chartPane2 = new ChartPanel
            (ChartFactory.createScatterPlot
             (null, "% Response", "Time (Minute)", null, 
              PlotOrientation.HORIZONTAL, true, false, false));
        chartPane2.setBackground(Color.white);
        chartPane2.getChart().setBorderPaint(Color.white);
        chartPane2.getChart().setBackgroundPaint(Color.white);
        chartPane2.getChart().getPlot().setBackgroundAlpha(.5f);
        chartPane2.getChart().getXYPlot().setRangeGridlinesVisible(false);
        chartPane2.getChart().getXYPlot().setDomainGridlinesVisible(false);
        LogarithmicAxis axis = new LogarithmicAxis ("% Response");
        axis.setRange(new Range (.1, 200));
        axis.setAutoRangeNextLogFlag(true);
        axis.setAutoRange(true);
        chartPane2.getChart().getXYPlot().setDomainAxis(axis);

        // use the same renderer as th ln(response)
        XYItemRenderer renderer2 = new  XYLineAndShapeRenderer ();
        //renderer2.setSeriesPaint(0, Color.black);
        chartPane2.getChart().getXYPlot().setRenderer(0, renderer2);
        chartPane2.getChart().getXYPlot().setRenderer(2, def);

        //chartPane2.getChart().getXYPlot().setRenderer
        //  (1, renderer = new  XYLineAndShapeRenderer ());// mask renderer
        
        JSplitPane split = new JSplitPane (JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(chartPane1);
        split.setRightComponent(chartPane2);
        split.setDividerSize(3);
        split.setResizeWeight(.5);

        JPanel pane = new JPanel (new BorderLayout (0, 5));
        pane.setBorder(BorderFactory.createCompoundBorder
                       (BorderFactory.createTitledBorder("Plots"),
                        BorderFactory.createEmptyBorder(1,1,1,1)));
        pane.add(plotHeader = new JLabel ("", JLabel.CENTER),
                 BorderLayout.NORTH);
        pane.add(split);

        return pane;
    }

    protected JComponent createResultPane () {
        JPanel pane = new JPanel (new BorderLayout (0, 2));
        pane.setBorder(BorderFactory.createCompoundBorder
                       (BorderFactory.createTitledBorder("Results"),
                        BorderFactory.createEmptyBorder(1,1,1,1)));

        Box box = Box.createHorizontalBox();
        box.add(new JLabel ("[CYP] (pmol/mL)"));
        box.add(Box.createHorizontalStrut(5));
        cypConc = new JTextField (5);
        cypConc.setText(String.valueOf(DEFAULT_CYP_CONC));
        cypConc.setToolTipText("Specify CYP concentration in pmol/mL");
        box.add(cypConc);
        ActionListener al = new ActionListener () {
                public void actionPerformed (ActionEvent e) {
                    updateCLint ();
                }
            };
        cypConc.addActionListener(al);

        JButton update = new JButton ("Update");
        update.addActionListener(al);
        update.setToolTipText("Update CLint calculation");
        box.add(Box.createHorizontalStrut(3));
        box.add(update);

        box.add(Box.createHorizontalStrut(5));
        box.add(new JLabel ("CLint unit:"));

        unitCb = new JComboBox (new String[] {"mL/min/pmol",
                                              "mL/hr/nmol"});
        unitCb.setEnabled(false);
        unitCb.addActionListener(new ActionListener () {
                public void actionPerformed (ActionEvent e) {
                    updateCLint ();
                }
            });
        box.add(Box.createHorizontalStrut(5));
        //box.add(unitCb);
        box.add(new JLabel ("<html><b>mL/min/pmol</b>"));

        JPanel bp = new JPanel (new BorderLayout ());
        bp.add(box, BorderLayout.WEST);
        JButton save = new JButton ("Save");
        save.addActionListener(new ActionListener () {
                public void actionPerformed (ActionEvent e) {
                    save ();
                }
            });
        //save.setEnabled(true);
        bp.add(save, BorderLayout.EAST);
        pane.add(bp, BorderLayout.NORTH);

        ResultTableModel rtm = new ResultTableModel ();
        resultTab = new JTable (rtm);
        resultTab.setDefaultRenderer
            (Double.class, new NumericalCellRenderer (3));
        TableRowSorter sorter = new TableRowSorter(rtm);
        sorter.toggleSortOrder(0); // 
        sorter.toggleSortOrder(0); // sort by save option desc
        resultTab.setRowSorter(sorter);
        resultTab.getSelectionModel().setSelectionMode
            (ListSelectionModel.SINGLE_SELECTION);
        resultTab.getSelectionModel().addListSelectionListener(this);

        pane.add(new JScrollPane (resultTab));

        return pane;
    }

    protected JMenuBar createMenuBar () {
        JMenuBar menubar = new JMenuBar ();
        JMenu menu;
        JMenuItem item;

        menu = menubar.add(new JMenu ("File"));
        datasetMenu = new JMenu ("Datasets");
        menu.add(datasetMenu);
        menu.addSeparator();
        
        // now load the datasets
        new LoadDatasets().execute();

        JMenu uploadMenu = new JMenu ("Upload");
        uploadMenu.setToolTipText("Upload dataset to server");  
        menu.add(uploadMenu);
        for (String enz : ENZYMES) {
            uploadMenu.add(item = new JMenuItem (enz));
            item.setActionCommand("Upload/"+enz);
            item.addActionListener(this);
        }
        menu.addSeparator();

        JMenu importMenu = new JMenu ("Import");
        menu.add(importMenu);
        item = importMenu.add(new JMenuItem ("Txt"));
        item.setToolTipText("Import Txt data file");
        item.addActionListener(this);
        item = importMenu.add(new JMenuItem ("Csv"));
        item.setToolTipText("Import Csv data file");
        item.addActionListener(this);

        item = menu.add(new JMenuItem ("Export"));
        item.setToolTipText("Export data file");
        item.addActionListener(this);

        item = menu.add(new JMenuItem ("Convert"));
        item.setToolTipText("Convert zip file to csv");
        item.addActionListener(this);

        menu.addSeparator();
        JMenu dump = new JMenu ("Dump");
        menu.add(dump);
        item = dump.add(new JMenuItem ("Annotations"));
        item.addActionListener(new ActionListener () {
                public void actionPerformed (ActionEvent e) {
                    logger.info(annotatedResults.size()
                                +" total annotated NCGC samples!");
                    try {
                        dumpAnnotations ();
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        item = dump.add(new JMenuItem ("Controls"));
        item.addActionListener(new ActionListener () {
                public void actionPerformed (ActionEvent e) {
                    logger.info(controlResults.size()
                                +" total control samples!");
                    try {
                        dumpControls ();
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    
                }
            });
        item = dump.add(new JMenuItem ("Samples"));
        item.addActionListener(new ActionListener () {
                public void actionPerformed (ActionEvent e) {
                    try {
                        dumpSamples ();
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    
                }
            });

        menu.addSeparator();
        item = menu.add(new JMenuItem ("Quit"));
        item.addActionListener(this);
        
        /*
        menu = menubar.add(new JMenu ("Options"));
        JMenu submenu = new JMenu ("Corrections");
        menu.add(submenu);
        for (String t : new String[]{"T0", "T5", "T10", "T15", "T30", "T60"}) {
            item = submenu.add(new JMenuItem (t));
            item.setToolTipText("Apply global correction to "+t+" response");
            item.addActionListener(this);
        }
        */

        return menubar;
    }

    protected void setSampleTreeModel 
        (String name, DefaultMutableTreeNode root) {
        setTitle (name);
        saveDirty = false;
        ResultTableModel model = (ResultTableModel)resultTab.getModel();
        model.clear();
        clearPlots ();
        new LoadSavedResults (name, root).execute();
        sampleTree.setModel(new DefaultTreeModel (root));
        updateCLint (root);
    }

    protected void updateCLint () {
        updateCLint ((DefaultMutableTreeNode)sampleTree.getModel().getRoot());
    }

    protected void updateCLint (DefaultMutableTreeNode root) {
        try {
            double conc = Double.parseDouble(cypConc.getText());

            CLUnit unit = CLUnit.mL_min_pmol;
            switch (unitCb.getSelectedIndex()) {
            case 0: unit = CLUnit.mL_min_pmol; break;
            case 1: unit = CLUnit.mL_hr_nmol; break;
            }

            for (Enumeration en = root.depthFirstEnumeration();
                 en.hasMoreElements(); ) {
                DefaultMutableTreeNode node = 
                    (DefaultMutableTreeNode)en.nextElement();
                Object obj = node.getUserObject();
                if (obj != null && obj instanceof SampleData) {
                    SampleData data = (SampleData)obj;
                    updateCLint (unit, conc, data.getResults());
                }
            }
            ResultTableModel model = (ResultTableModel)resultTab.getModel();
            model.refresh();
        }
        catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Bogus CYP concentration: "
                                          +cypConc.getText(), "Error",
                                          JOptionPane.ERROR_MESSAGE);
        }
    }

    protected void updateCLint (Collection<Estimator.Result> results) {
        double conc = Double.parseDouble(cypConc.getText());
        switch (unitCb.getSelectedIndex()) {
        case 0: updateCLint (CLUnit.mL_min_pmol, conc, results); break;
        case 1: updateCLint (CLUnit.mL_hr_nmol, conc, results); break;
        }
    }

    // update intrinsic clearance based on 
    protected static void updateCLint 
        (CLUnit unit, double conc, Collection<Estimator.Result> results) {
        for (Estimator.Result r : results) {
            FitModel.Variable slope = 
                r.getModel().getVariable("Slope");
            Double k = slope.getValue();
            switch (unit) {
            case mL_min_pmol:
                if (k != null && k < 0.) {
                    r.setCLint(-Math.log(-k / conc));
                }
                else {
                    r.setCLint(null);
                }
                break;
                
            case mL_hr_nmol:
                if (k != null && k < 0.) {
                    r.setCLint(Math.log((-k / conc)*60*1000));
                }
                else {
                    r.setCLint(null);
                }
                break;
            }
        }
    }


    protected Molecule getMol (String name) {
        int pos = name.indexOf('-');
        if (pos > 0) {
            name = name.substring(0, pos);
        }
        return molDb.get(name);
    }

    public void actionPerformed (ActionEvent e) {
        String cmd = e.getActionCommand();
        if (cmd.equalsIgnoreCase("txt")) {
            loadTxt ();
        }
        else if (cmd.equalsIgnoreCase("csv")) {
            loadCsv ();
        }
        else if (cmd.equalsIgnoreCase("export")) {
            export ();
        }
        else if (cmd.equalsIgnoreCase("convert")) {
            convert ();
        }
        else if (cmd.equalsIgnoreCase("quit")) {
            quit ();
        }
        else if (cmd.startsWith("Upload/")) {
            String enz = cmd.substring(7);
            logger.info("Uploading \""+enz+"\"");
            upload (enz);
        }
        else if (cmd.startsWith("T")) {
        }
    }

    protected void clearPlots () {
        XYPlot plot = chartPane1.getChart().getXYPlot();
        plot.clearAnnotations();
        plot.setDataset(0, null);
        plot.setDataset(1, null);
        plotHeader.setText(null);
        chartPane2.getChart().getXYPlot().setDataset(0, null);
    }

    public void valueChanged (TreeSelectionEvent e) {
        TreeSelectionModel model = (TreeSelectionModel)e.getSource();
        TreePath path = model.getSelectionPath();

        XYPlot plot = chartPane1.getChart().getXYPlot();
        plot.clearAnnotations();
        plot.setDataset(0, null);
        plot.setDataset(1, null);
        plotHeader.setText(null);

        ResultTableModel rtm = (ResultTableModel)resultTab.getModel();
        if (path == null) {
            return;
        }

        SampleTreeNode node = (SampleTreeNode)path.getLastPathComponent();
        logger.info("Selection "+node.getUserObject());
        sampleTree.expandPath(path);

        SampleData data = (SampleData)node.getData();
        if (data != null) {            
            rtm.setResults(data.getResults());
            Molecule mol = getMol (data.getName());
            if (mol == null) {
                // try the parent
                Object parent = node.getParent();
                if (parent != null && parent instanceof SampleTreeNode) {
                    mol = getMol (((SampleTreeNode)parent)
                                  .getData().getName());
                }
            }
            mview.setM(0, mol);
            plot.setDataset(0, data.getDataset());
            plot = chartPane2.getChart().getXYPlot();
            plot.setDataset(0, data.getRatioDataset());

            Set<String> samples = SAMPLE_NAMES.get(data.getName());
            chartPane2.getChart().getXYPlot().setDataset(2, null);
            chartPane2.getChart().getXYPlot().clearAnnotations();           
            if (samples != null) {
                logger.info("Samples: "+samples);
                for (String s : samples) {
                    SampleData d = annotatedData.get(s);
                    if (d != null) {
                        if (mol == null) {
                            mview.setM(0, getMol (s));
                        }
                        
                        logger.info("Annotated sample "
                                    +s+": "+annotatedResults.get(s));
                        /*
                        chartPane1.getChart()
                            .getXYPlot().setDataset(2, d.getDataset());
                        */
                        chartPane2.getChart()
                            .getXYPlot().setDataset(2, d.getRatioDataset());
                        Estimator.Result result = annotatedResults.get(s);
                        if (result != null) {
                            Double t12 = result.getHalflife();
                            XYAnnotation anno = new XYTextAnnotation
                                ("t1/2 = "+String.format("%1$.2f min", t12),
                                 600., 50.);
                            chartPane2.getChart()
                                .getXYPlot().addAnnotation(anno);
                        }
                        break; // just the first one 
                    }
                }
            }
            
            // isStable() must be called after getDataset()
            if (data.isStable())
                plotHeader.setText
                    ("<html>Sample <b>"+data.getName()
                     +"</b> is probably <b>stable</b>!");
        }
        else {
            rtm.clear();
            chartPane2.getChart().getXYPlot().setDataset(0, null);
            chartPane2.getChart().getXYPlot().setDataset(2, null);
        }
    }

    public void valueChanged (ListSelectionEvent e) {
        if (e.getValueIsAdjusting())
            return;
        XYPlot plot = chartPane1.getChart().getXYPlot();
        plot.clearAnnotations();

        ResultTableModel rtm = (ResultTableModel)resultTab.getModel();
        int row = resultTab.getSelectedRow();
        if (row >= 0) {
            row = resultTab.convertRowIndexToModel(row);
            logger.info("Result "+rtm.getResult(row));
            plot.setDataset(1, rtm.getDataset(row));
            for (XYAnnotation a : rtm.getAnnotations(row)) {
                plot.addAnnotation(a, true);
            }
        }
    }

    protected void loadTxt () {
        if (confirmedSave ()) {
            FileNameExtensionFilter filter = new FileNameExtensionFilter
                ("TXT file", "txt", "text");
            chooser.setFileFilter(filter);
            chooser.setDialogTitle("Load txt data file...");
            int ans = chooser.showOpenDialog(this);
            if (JFileChooser.APPROVE_OPTION == ans) {
                File file = chooser.getSelectedFile();
                new LoadFileWorker (Format.Txt, file).execute();
            }
        }
    }

    protected void loadCsv () {
        if (confirmedSave ()) {
            FileNameExtensionFilter filter = new FileNameExtensionFilter
                ("CSV file", "csv", "text");
            chooser.setFileFilter(filter);
            chooser.setDialogTitle("Load csv data file...");
            int ans = chooser.showOpenDialog(this);
            if (JFileChooser.APPROVE_OPTION == ans) {
                File file = chooser.getSelectedFile();
                new LoadFileWorker (Format.Csv, file).execute();
            }
        }
    }
    
    protected void upload (String enz) {
        FileNameExtensionFilter filter = new FileNameExtensionFilter
            ("Text data file", "txt", "text", "csv");
        chooser.setFileFilter(filter);
        chooser.setDialogTitle("Upload data file...");
        int ans = chooser.showOpenDialog(this);
        if (JFileChooser.APPROVE_OPTION == ans) {
            File file = chooser.getSelectedFile();
            new UploadFileWorker (enz, file).execute();
        }
    }

    protected void convert () {
        FileNameExtensionFilter filter = new FileNameExtensionFilter
            ("ZIP file", "zip");
        chooser.setFileFilter(filter);
        chooser.setDialogTitle("Convert data file...");
        int ans = chooser.showOpenDialog(this);
        if (JFileChooser.APPROVE_OPTION == ans) {
            File file = chooser.getSelectedFile();
            String name = file.getName();
            int pos = name.lastIndexOf('.');
            if (pos > 0) {
                name = name.substring(0, pos);
            }
            name += ".csv";
            
            new ConvertFileWorker
                (file, new File (file.getParentFile(), name)).execute();
        }       
    }

    protected void loadSamples (DefaultListModel model, 
                                boolean correction, 
                                InputStream is) throws IOException {
        TxtReader reader = new TxtReader (is);
        Estimator estimator = new LeastSquaresEstimator ();
        for (Sample s; (s = reader.read()) != null; ) {
            String name = s.getName();

            if (name.equalsIgnoreCase("albendazole") 
                || name.equalsIgnoreCase("blank"))
                ;
            else {
                if (correction) {
                    Measure m = s.get(0); // adjust T0
                    Double r = m.getResponse();
                    if (r != null) {
                        m.setResponse(r*0.75);
                    }
                }


                List<Estimator.Result> results = 
                    new ArrayList<Estimator.Result>();
                results.addAll(estimator.estimate(s));
                // filter positive slopes
                /*
                for (Estimator.Result r : estimator.estimate(s)) {
                    FitModel.Variable v = r.getModel().getVariable("Slope");
                    if (v.getValue() < 0.) {
                        results.add(r);
                    }
                }
                */

                SampleData data = new SampleData (s, results);
                model.addElement(data);
            }
        }
    }

    Set<String> samples = new TreeSet<String>();
    protected DefaultMutableTreeNode loadSampleTreeTxt
        (boolean correction, InputStream is) 
        throws IOException {

        TxtReader reader = new TxtReader (is);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode ();
        for (Sample s; (s = reader.read()) != null; ) {
            String name = s.getName();
            if (name.equalsIgnoreCase("verdanafil")) {
                s.setName("vardenafil");
            }

            if (name.equalsIgnoreCase("albendazole") 
                || name.equalsIgnoreCase("blank"))
                ;
            else {
                samples.add(name);
                if (correction) {
                    Measure m = s.get(0); // adjust T0
                    Double r = m.getResponse();
                    if (r != null) {
                        m.setResponse(r*0.75);
                    }
                }
                root.add(new SampleTreeNode (s));
            }
        }

        return root;
    }

    protected DefaultMutableTreeNode loadSampleTreeCsv
        (InputStream is) throws IOException {

        CsvReader reader = new CsvReader (is);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode ();
        for (Sample s; (s = reader.read()) != null; ) {
            if (s.size() > 2) {
                root.add(new SampleTreeNode (s));
            }
            else {
                logger.warning(s.getName()
                               +": sample contains too few samples ("
                               +s.size()+")");
            }
        }

        return root;
    }

    protected DefaultListModel loadSamples 
        (boolean correction, InputStream is) throws IOException {
        DefaultListModel model = new DefaultListModel ();
        loadSamples (model, correction, is);
        return model;
    }

    protected int countSaved () {
        int saves = 0;
        for (Map.Entry<Estimator.Result, Boolean> me : 
                 savedResults.entrySet()) {
            Boolean value = me.getValue();
            if (value != null && value)
                ++saves;
        }
        return saves;
    }

    protected Collection<Estimator.Result> getSavedResults () {
        ArrayList<Estimator.Result> saves = new ArrayList<Estimator.Result>();
        for (Map.Entry<Estimator.Result, Boolean> me : 
                 savedResults.entrySet()) {
            Boolean value = me.getValue();
            if (value != null && value)
                saves.add(me.getKey());
        }
        return saves;
    }

    protected void save () {
        Collection<Estimator.Result> saves = getSavedResults ();
        if (saves.isEmpty()) {
            JOptionPane.showMessageDialog
                (this, "There is nothing to save!",
                 "INFO", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new PersistSavedResults (getTitle ()).execute();
    }

    protected void export () {
        Collection<Estimator.Result> saves = getSavedResults ();
        logger.info("Exporting "+saves.size()+" saved results...");
        
        if (saves.isEmpty()) {
            JOptionPane.showMessageDialog
                (this, "There is nothing to export!",
                 "INFO", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        chooser.resetChoosableFileFilters();
        chooser.addChoosableFileFilter
            (new FileNameExtensionFilter("CSV file", "csv"));
        chooser.addChoosableFileFilter
            (new FileNameExtensionFilter("SDF file", "sdf", "sd"));
        chooser.setDialogTitle("Save results...");
        int ans = chooser.showSaveDialog(this);
        if (JFileChooser.APPROVE_OPTION == ans) {
            File file = chooser.getSelectedFile();
            FileNameExtensionFilter filter =
                (FileNameExtensionFilter)chooser.getFileFilter();
            
            int index = file.getName().lastIndexOf('.');
            if (index < 0) {
                file = new File (file.getParent(), file.getName()+"."
                                 +filter.getExtensions()[0]);
                                 //+".sdf");
            }
            else {
                file = new File (file.getParent(), 
                                 file.getName().substring(0, index)
                                 +filter.getExtensions()[0]);
                                 //+".sdf");
            }

            if (file.exists()) {
                ans = JOptionPane.showConfirmDialog
                    (this, "File \"" + file+"\" exist; override anyway?",
                     "WARNING", JOptionPane.WARNING_MESSAGE);
                if (ans != JOptionPane.OK_OPTION)
                    return;
            }
            
            try {
                String ext = filter.getExtensions()[0];
                if ("csv".equalsIgnoreCase(ext))
                    exportCSV (file, saves);
                else 
                    exportSDF (file, saves);
                saveDirty = false;
            }
            catch (IOException ex) {
                JOptionPane.showMessageDialog
                    (this, "Unable to save results to file "+file,
                     "ERROR", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    protected void exportCSV (File file, Collection<Estimator.Result> results) 
        throws IOException {
        logger.info("Saving results into \""+file+"\"...");
        PrintStream ps = new PrintStream (new FileOutputStream (file));

        ResultTableModel rtm = (ResultTableModel)resultTab.getModel();
        ps.println("Sample,SMILES,Score,Data Points,pCLint"
                   +",t1/2 [min],Slope,Intercept,MSE,r^2");
        for (Estimator.Result r : results) {
            String name = r.getSample().getName();
            ps.print(name+",");

            Molecule mol = getMol (name);
            ps.print((mol != null ? mol.toFormat("smiles") : "")+",");
            ps.print(String.format("%1$.3f", r.getScore())+",");

            int[] config = r.getConfig();
            Measure[] pts = r.getMeasures();
            for (int i = 0, j = 0; i < config.length && i < pts.length; ++i) 
                if (config[i] > 0 && pts[i] != null) {
                    if (j > 0) ps.print(" ");
                    ps.print("T"+pts[i].getTime().intValue());
                    ++j;
                }
            ps.print(","+r.getCLint()+",");
            FitModel model = r.getModel();
            { FitModel.Variable var = model.getVariable("Slope");
                ps.print(var != null && Math.abs(var.getValue()) > 1e-3 
                         ? -Math.log(2)/var.getValue() : "");
            }
            ps.print(","+model.getVariable("Slope").getValue());
            ps.print(","+model.getVariable("Intercept").getValue());
            ps.print(","+model.getVariable("MSE").getValue());
            ps.print(","+model.getVariable("r^2").getValue());
            ps.println();
        }
    }

    protected void exportSDF (File file, Collection<Estimator.Result> results) 
        throws IOException {
        logger.info("Saving results into \""+file+"\"...");
        PrintStream ps = new PrintStream (new FileOutputStream (file));

        String dataset = getTitle ();
        ResultTableModel rtm = (ResultTableModel)resultTab.getModel();
        for (Estimator.Result r : results) {
            String name = r.getSample().getName();
            Molecule mol = getMol (name);
            if (mol != null) {
                Molecule m = mol.cloneMolecule();
                m.setProperty("Dataset", dataset);
                m.setProperty("SMILES", m.toFormat("smiles:q"));
                m.setProperty("Score", 
                              String.format("%1$.3f", r.getScore()));

                int[] config = r.getConfig();
                Measure[] pts = r.getMeasures();
                StringBuilder sb= new StringBuilder ();
                Double r0 = null;
                for (int i = 0, j = 0; i < config.length 
                         && i < pts.length; ++i) {
                    Double t = pts[i].getTime();
                    if (t != null && t.intValue() == 0)
                        r0 = pts[i].getResponse();

                    if (config[i] > 0 && t != null) {
                        //if (j > 0) sb.append(" ");
                        //sb.append("T"+pts[i].getTime().intValue());
                        double rr = 100*pts[i].getResponse()/r0;
                        sb.append(String.format
                                  ("%1$2d %2$.1f\n", t.intValue(), rr));
                        ++j;
                    }
                }

                m.setProperty("Responses", sb.toString());
                if (r.getCLint() != null) {
                    m.setProperty("pCLint", 
                                  String.format("%1$.3f", r.getCLint()));
                }

                FitModel model = r.getModel();
                { FitModel.Variable var = model.getVariable("Slope");
                    Double t1_2 = var != null 
                        && Math.abs(var.getValue()) > 1e-6
                        ? -Math.log(2)/var.getValue() : null;
                    if (t1_2 != null) {
                        m.setProperty("t1/2", String.format("%1$.3f", t1_2));
                    }
                }
                m.setProperty("Slope", 
                              String.format("%1$.3f", model
                                            .getVariable("Slope")
                                            .getValue()));
                m.setProperty("Intercept", 
                              String.format("%1$.3f",
                                            (model.getVariable
                                             ("Intercept").getValue())));
                m.setProperty("MSE", 
                              String.format("%1$.3f",
                                            model.getVariable
                                            ("MSE").getValue()));
                m.setProperty("r^2",
                              String.format("%1$.3f",
                                            model.getVariable("r^2")
                                            .getValue()));
                ps.print(m.toFormat("sdf"));
            }
        }
    }

    protected boolean confirmedSave () {
        int saves = countSaved ();
        if (saves > 0 && saveDirty) {
            String mesg = saves == 1 
                ? "There is one saved result" 
                : ("There are "+saves+" saved results");

            int ans = JOptionPane.showConfirmDialog
                (this, mesg + "; proceed anyway?",
                 "WARNING", JOptionPane.WARNING_MESSAGE);
            return ans == JOptionPane.OK_OPTION;
        }
        return true;
    }

    protected void quit () {
        if (confirmedSave ()) 
            System.exit(0);
    }

    public static void main (final String[] argv) throws Exception {
        SwingUtilities.invokeLater(new Runnable () {
                public void run () {
                    IQCValidator iqc = new IQCValidator (argv);
                    iqc.setSize(800, 600);
                    iqc.setVisible(true);
                }
            });
    }
}
