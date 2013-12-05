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


public class IQCValidator extends JFrame 
    implements ActionListener, ListSelectionListener, TreeSelectionListener {
    static final Logger logger = 
        Logger.getLogger(IQCValidator.class.getName());

    enum CLUnit {
        mL_pmol_min,
            mL_nmol_hr
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
                stable = Math.abs(maxLnRes - minLnRes) < .5;
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
        DefaultMutableTreeNode root;
        
        LoadFileWorker (File file) {
            this.file = file;
        }

        @Override
        protected Throwable doInBackground () {
            try {
                root = loadSampleTree (false, new FileInputStream (file));
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

        LoadSavedResults (String dataset, DefaultMutableTreeNode root) {
            this.dataset = dataset;
            this.root = root;
        }

        @Override
        protected Throwable doInBackground () {
            try {
                URL url = new URL 
                    ("http://tripod.nih.gov/servlet/iqc-web/annotation/"
                     +(dataset != null ? dataset : ""));

                logger.info("Retrieving saved results for \""+dataset+"\"...");
                BufferedReader br = new BufferedReader 
                    (new InputStreamReader (url.openStream()));
                
                for (String line; (line = br.readLine()) != null; ) {
                    String[] toks = line.split("\t");
                    if (toks.length == 3) {
                        saves.put(toks[0], Integer.parseInt(toks[1]) == 1);
                    }
                    System.out.println(line);
                }
                br.close();
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

    class UploadFileWorker extends SwingWorker<Throwable, Void> {
        File file;
        String status;

        UploadFileWorker (File file) {
            this.file = file;
        }

        @Override
        protected Throwable doInBackground () {
            try {
                HttpClient client = new DefaultHttpClient();
                HttpPost post = new HttpPost
                    ("http://tripod.nih.gov/servlet/iqc-web/datasets/");

                FileBody uploadFilePart = new FileBody (file);
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
                        (IQCValidator.this, "Successfully upload dataset\n"
                         +file+"\n"+status, 
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
                URL url = new URL
                    ("http://tripod.nih.gov/servlet/iqc-web/datasets/");
                BufferedReader br = new BufferedReader 
                    (new InputStreamReader (url.openStream()));
                for (String name; (name = br.readLine()) != null; ) {
                    JMenuItem item = createMenuItem (name);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            catch (Exception ex) {
                return ex;
            }
            return null;
        }

        JMenuItem createMenuItem (String name) throws Exception {
            logger.info("## loading "+name+"...");
            URL url = new URL
                ("http://tripod.nih.gov/servlet/iqc-web/datasets/"+name);

            boolean correction = 
                name.equals("SP118414_20130816_CYP34A_Stab_Data_Final.txt")
                || name.equals("SP118414_20130830_CYP3A4_Stability_Data.txt");
            
            JMenuItem item = null;
            int pos = name.lastIndexOf('.');
            if (pos > 0) {
                String ext = name.substring(pos);
                if (ext.equals(".txt") || ext.equals(".TXT")) {
                    item = new JMenuItem (name);
                    item.addActionListener(loadAction);
                    item.putClientProperty
                        ("samples", loadSampleTree 
                         (correction, url.openStream()));
                }
                else if (ext.equals(".sdf")) {
                    MolImporter mi = new MolImporter (url.openStream());
                    for (Molecule m; (m = mi.read()) != null; ) {
                        molDb.put(m.getName(), m);
                    }
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
            try {
                URL url = new URL
                    ("http://tripod.nih.gov/servlet/iqc-web/annotation/"
                     +dataset);
                URLConnection con = url.openConnection();
                con.setDoOutput(true);
                con.setDoInput(true);

                PrintStream ps = new PrintStream (con.getOutputStream());
                for (Map.Entry<Estimator.Result, Boolean> me : 
                         savedResults.entrySet()) {
                    Estimator.Result r = me.getKey();
                    ps.println(r.getId()+"|"+me.getValue());
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
            "Score",
            "Selection",
            "CLint",
            "t1/2",
            "Slope",
            "Intercept",
            "MSE",
            "R^2"
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
            case 1: return Double.class; // Score
            case 2: return String.class; // Selection
            case 3: return Double.class; // CLint
            case 4: return Double.class; // t1/2
            case 5: return Double.class; // Slope
            case 6: return Double.class; // Intercept
            case 7: return Double.class; // MSE
            case 8: return Double.class; // R
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
            case 1: return result.getScore();
            case 2: 
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
            case 3:
                return result.getCLint();

            case 4:
                { FitModel.Variable var = model.getVariable("Slope");
                    return var != null && Math.abs(var.getValue()) > 1e-6 
                        ? -Math.log(2)/var.getValue() : null;
                }
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
                        if (null != savedResults.get(r)) {
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

    private Map<String, Molecule> molDb = 
        new ConcurrentHashMap<String, Molecule>();

    private ActionListener loadAction = new ActionListener () {
            public void actionPerformed (ActionEvent e) {
                JMenuItem item = (JMenuItem)e.getSource();
                DefaultMutableTreeNode root =
                    (DefaultMutableTreeNode) item
                    .getClientProperty("samples");
                if (confirmedSave ()) {
                    setSampleTreeModel (item.getText(), root);
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

	// main renderer
	XYItemRenderer renderer1 = new  XYLineAndShapeRenderer ();
        //renderer1.setSeriesPaint(0, Color.black);
	chartPane1.getChart().getXYPlot().setRenderer(0, renderer1); 

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
        box.add(new JLabel ("CLint unit"));

        unitCb = new JComboBox (new String[] {"mL/pmol/min",
                                              "mL/nmol/hr"});
        unitCb.addActionListener(new ActionListener () {
                public void actionPerformed (ActionEvent e) {
                    updateCLint ();
                }
            });
        box.add(Box.createHorizontalStrut(5));
        box.add(unitCb);

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
        resultTab.setRowSorter(new TableRowSorter(rtm));
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

        item = menu.add(new JMenuItem ("Upload"));
        item.setToolTipText("Upload dataset to server");
        item.addActionListener(this);
        menu.addSeparator();

        item = menu.add(new JMenuItem ("Import"));
        item.setToolTipText("Import data file");
        item.addActionListener(this);

        item = menu.add(new JMenuItem ("Export"));
        item.setToolTipText("Export saved results to data file");
        item.addActionListener(this);

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

            CLUnit unit = CLUnit.mL_pmol_min;
            switch (unitCb.getSelectedIndex()) {
            case 0: unit = CLUnit.mL_pmol_min; break;
            case 1: unit = CLUnit.mL_nmol_hr; break;
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
        case 0: updateCLint (CLUnit.mL_pmol_min, conc, results); break;
        case 1: updateCLint (CLUnit.mL_nmol_hr, conc, results); break;
        }
    }

    // update intrinsic clearance based on 
    protected static void updateCLint 
        (CLUnit unit, double conc, Collection<Estimator.Result> results) {
        for (Estimator.Result r : results) {
            FitModel.Variable slope = 
                r.getModel().getVariable("Slope");
            switch (unit) {
            case mL_pmol_min:
                r.setCLint(-slope.getValue() / conc);
                break;
                
            case mL_nmol_hr:
                r.setCLint((-slope.getValue() / conc)*60*1000);
                break;
            }
        }
    }

    public void actionPerformed (ActionEvent e) {
        String cmd = e.getActionCommand();
        if (cmd.equalsIgnoreCase("import")) {
            load ();
        }
        else if (cmd.equalsIgnoreCase("export")) {
            export ();
        }
        else if (cmd.equalsIgnoreCase("quit")) {
            quit ();
        }
        else if (cmd.equalsIgnoreCase("upload")) {
            upload ();
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
            Molecule mol = molDb.get(data.getName());
            if (mol == null) {
                // try the parent
                Object parent = node.getParent();
                if (parent != null && parent instanceof SampleTreeNode) {
                    mol = molDb.get(((SampleTreeNode)parent)
                                    .getData().getName());
                }
            }
            mview.setM(0, mol);
            plot.setDataset(0, data.getDataset());
            
            plot = chartPane2.getChart().getXYPlot();
            plot.setDataset(0, data.getRatioDataset());

            // isStable() must be called after getDataset()
            if (data.isStable())
                plotHeader.setText
                    ("<html>Sample <b>"+data.getName()
                     +"</b> is probably <b>stable</b>!");
        }
        else {
            rtm.clear();
            chartPane2.getChart().getXYPlot().setDataset(0, null);
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

    protected void load () {
        if (confirmedSave ()) {
            FileNameExtensionFilter filter = new FileNameExtensionFilter
                ("TXT file", "txt", "text");
            chooser.setFileFilter(filter);
            chooser.setDialogTitle("Load txt data file...");
            int ans = chooser.showOpenDialog(this);
            if (JFileChooser.APPROVE_OPTION == ans) {
                File file = chooser.getSelectedFile();
                new LoadFileWorker (file).execute();
            }
        }
    }

    protected void upload () {
        FileNameExtensionFilter filter = new FileNameExtensionFilter
            ("TXT file", "txt", "text");
        chooser.setFileFilter(filter);
        chooser.setDialogTitle("Upload txt data file...");
        int ans = chooser.showOpenDialog(this);
        if (JFileChooser.APPROVE_OPTION == ans) {
            File file = chooser.getSelectedFile();
            new UploadFileWorker (file).execute();
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

    protected DefaultMutableTreeNode loadSampleTree 
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
        if (saves.isEmpty()) {
            JOptionPane.showMessageDialog
                (this, "There is nothing to export!",
                 "INFO", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        FileNameExtensionFilter filter = new FileNameExtensionFilter
            ("CSV file", "csv");
        chooser.setFileFilter(filter);
        chooser.setDialogTitle("Save results...");
        int ans = chooser.showSaveDialog(this);
        if (JFileChooser.APPROVE_OPTION == ans) {
            File file = chooser.getSelectedFile();

            int index = file.getName().lastIndexOf('.');
            if (index < 0) {
                file = new File (file.getParent(), file.getName()+".csv");
            }
            else {
                file = new File (file.getParent(), 
                                 file.getName().substring(0, index)+".csv");
            }

            if (file.exists()) {
                ans = JOptionPane.showConfirmDialog
                    (this, "File \"" + file+"\" exist; override anyway?",
                     "WARNING", JOptionPane.WARNING_MESSAGE);
                if (ans != JOptionPane.OK_OPTION)
                    return;
            }
            
            try {
                exportCSV (file, saves);
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
        ps.println("Sample,SMILES,Score,Data Points,CLint ["
                   +unitCb.getSelectedItem()+"]"
                   +",t1/2 [min],Slope,Intercept,MSE,R^2");
        for (Estimator.Result r : results) {
            String name = r.getSample().getName();
            ps.print(name+",");
            Molecule mol = molDb.get(name);
            if (mol == null) {
                int pos = name.indexOf('-');
                if (pos > 0) {
                    name = name.substring(0, pos);
                    mol = molDb.get(name);
                }
            }
            ps.print((mol != null ? mol.toFormat("smiles") : "")+",");
            ps.print(String.format("%1$.3f", r.getScore())+",");

            int[] config = r.getConfig();
            Measure[] pts = r.getMeasures();
            for (int i = 0, j = 0; i < config.length; ++i) 
                if (config[i] > 0) {
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
            ps.print(","+model.getVariable("R^2").getValue());
            ps.println();
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
