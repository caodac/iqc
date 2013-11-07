package tripod.iqc.validator;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.text.DecimalFormat;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.ListSelectionEvent;
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

import tripod.iqc.core.*;


public class IQCValidator extends JFrame 
    implements ActionListener, ListSelectionListener {
    static final Logger logger = 
        Logger.getLogger(IQCValidator.class.getName());

    enum CLUnit {
        mL_pmol_min,
            mL_nmol_hr
            }

    static double DEFAULT_CYP_CONC = 29.03;

    static class SampleData {
        Sample sample;
        List<Estimator.Result> results;
        DefaultXYDataset dataset;
        DefaultXYDataset ratioDS; // ratio dataset

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
            if (dataset == null || ratioDS == null) {
                dataset = new DefaultXYDataset ();
                ratioDS = new DefaultXYDataset ();

                for (int k = 0; k < sample.getReplicateCount(); ++k) {

                    List<Measure> measures = new ArrayList<Measure>();
                    Double r0 = null;
                    for (Measure m : sample.getMeasures(k)) {
                        Double r = m.getResponse();
                        Double t = m.getTime();
                        if (m.getBlank() || r == null || t == null) {
                            // skip this 
                        }
                        else {
                            if (t.equals(0.)) {
                                r0 = r;
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
                            data[0][i] = Math.log(r); // y
                            data[1][i] = t; // x

                            if (ratio != null) {
                                ratio[0][i] = 100*(r/r0); // y
                                ratio[1][i] = t; // x
                            }
                        }
                        dataset.addSeries(sample.getName()+"-"+k, data);
                        ratioDS.addSeries(sample.getName()+"-"+k, 
                                          ratio != null ? ratio : new double[2][0]);
                    }
                }
            }
        }

        public String getName () { return sample.getName(); }
        public String toString () { 
            return sample.getName();
        }
    }

    class LoadFileWorker extends SwingWorker<Throwable, Sample> {
        File file;
        DefaultListModel model = new DefaultListModel ();
        
        LoadFileWorker (File file) {
            this.file = file;
        }

        @Override
        protected Throwable doInBackground () {
            try {
                loadSamples (model, false, new FileInputStream (file));
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
                    IQCValidator.this.setTitle(file.getName());
                    sampleList.setModel(model);
                }
            }
            catch (Exception ex) {
            }
        }
    }

    class ResultTableModel extends AbstractTableModel {
        List<Estimator.Result> results;
        String[] columns = new String[] {
            "Save?",
            "Selection",
            "CLint",
            "t1/2",
            "Slope",
            "Intercept",
            "MSE",
            "R"
        };

        XYDataset[] datasets;
        XYAnnotation[][] annotations;
        boolean[] save;
        double cypConc = DEFAULT_CYP_CONC;
        CLUnit clunit = CLUnit.mL_pmol_min;

        ResultTableModel () {
        }

        public int getColumnCount () { return columns.length; }
        public String getColumnName (int col) { return columns[col]; }
        public Class getColumnClass (int col) {
            switch (col) {
            case 0: return Boolean.class; // Save
            case 1: return String.class; // Selection
            case 2: return Double.class; // CLint
            case 3: return Double.class; // t1/2
            case 4: return Double.class; // Slope
            case 5: return Double.class; // Intercept
            case 6: return Double.class; // MSE
            case 7: return Double.class; // R
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
            case 0: return save[row]; 
            case 1: 
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
            case 2:
                return result.getCLint();

            case 3:
                { FitModel.Variable var = model.getVariable("Slope");
                    return var != null && Math.abs(var.getValue()) > 1e-3 
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
                save[row] = value != null ? (Boolean)value : false;
            }
            else {
                throw new IllegalArgumentException
                    ("Column "+col+" is not editable!");
            }
        }

        public void setResults (List<Estimator.Result> results) {
            this.results = results;
            datasets = new XYDataset[results.size()];
            annotations = new XYAnnotation[results.size()][];
            save = new boolean[results.size()];
            updateCLint ();
            fireTableDataChanged ();
        }

        void updateCLint () {
            if (results != null) {
                for (Estimator.Result r : results) {
                    FitModel.Variable slope = 
                        r.getModel().getVariable("Slope");
                    switch (clunit) {
                    case mL_pmol_min:
                        r.setCLint(-slope.getValue() / cypConc);
                        break;

                    case mL_nmol_hr:
                        r.setCLint((-slope.getValue() / cypConc)*60*1000);
                        break;
                    }
                }
                fireTableDataChanged ();
            }
        }

        public void setCLUnit (CLUnit unit) {
            if (clunit != unit) {
                this.clunit = unit;
                updateCLint ();
            }
        }
        public CLUnit getCLUnit () { return clunit; }

        // update CLint
        public void setCYPConc (double conc) {
            this.cypConc = conc; // unit is pmol/mL, we convert to pmol/L
            updateCLint ();
        }

        public double getCYPConc () { return cypConc; }

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
                if (Math.abs(x) < 0.01) {
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

    private JFileChooser chooser;
    private JList sampleList;
    private JTable resultTab;
    private ChartPanel chartPane1; // ln(response) vs time
    private ChartPanel chartPane2; // % response vs time
    private MViewPane mview;
    private JTextField cypConc;

    private Map<String, Molecule> molDb = new HashMap<String, Molecule>();

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
        FileNameExtensionFilter filter = new FileNameExtensionFilter
            ("TXT file", "txt", "text");
        chooser = new JFileChooser (".");
        chooser.setFileFilter(filter);

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
        sampleList = new JList ();
        sampleList.getSelectionModel().setSelectionMode
            (ListSelectionModel.SINGLE_SELECTION);
        sampleList.getSelectionModel().addListSelectionListener(this);

        JSplitPane split = new JSplitPane (JSplitPane.VERTICAL_SPLIT);
        split.setDividerSize(3);
        split.setResizeWeight(.75);
        split.setTopComponent(new JScrollPane (sampleList));
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

        JPanel pane = new JPanel (new BorderLayout ());
        pane.setBorder(BorderFactory.createCompoundBorder
                       (BorderFactory.createTitledBorder("Plots"),
                        BorderFactory.createEmptyBorder(1,1,1,1)));
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
        JComboBox unitCb = new JComboBox (new String[] {"mL/pmol/min",
                                                        "mL/nmol/hr"});
        unitCb.addActionListener(new ActionListener () {
                public void actionPerformed (ActionEvent e) {
                    ResultTableModel rtm = 
                        (ResultTableModel)resultTab.getModel();
                    JComboBox cb = (JComboBox)e.getSource();
                    switch (cb.getSelectedIndex()) {
                    case 0: rtm.setCLUnit(CLUnit.mL_pmol_min); break;
                    case 1: rtm.setCLUnit(CLUnit.mL_nmol_hr); break;
                    }
                }
            });
        box.add(Box.createHorizontalStrut(5));
        box.add(unitCb);

        JPanel bp = new JPanel (new BorderLayout ());
        bp.add(box, BorderLayout.WEST);
        JButton save = new JButton ("Save");
        save.setEnabled(false);
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

        try {
            ActionListener loadAction = new ActionListener () {
                    public void actionPerformed (ActionEvent e) {
                        DefaultListModel model = (DefaultListModel)
                            ((JComponent)e.getSource())
                            .getClientProperty("samples");
                        sampleList.setModel(model);
                    }
                };
            JMenu sub = new JMenu ("Datasets");
            ZipInputStream zis = new ZipInputStream 
                (getClass().getResourceAsStream("/data.zip"));

            for (ZipEntry ze; (ze = zis.getNextEntry()) != null; ) {
                String name = ze.getName();
                int pos = name.lastIndexOf('.');
                if (pos > 0) {
                    String ext = name.substring(pos);
                    if (ext.equals(".txt") || ext.equals(".TXT")) {
                        item = sub.add(new JMenuItem (ze.getName()));
                        item.addActionListener(loadAction);
                        item.putClientProperty
                            ("samples", loadSamples (true, zis));
                    }
                    else if (ext.equals(".sdf")) {
                        MolImporter mi = new MolImporter (zis);
                        for (Molecule m; (m = mi.read()) != null; ) {
                            molDb.put(m.getName(), m);
                        }
                        logger.info("loaded "+molDb.size()
                                    +" structures from "+name);
                    }
                }
            }
            zis.close();

            menu.add(sub);
            menu.addSeparator();
        }
        catch (Exception ex) {
            ex.printStackTrace();
            logger.warning("No /data.zip file found!");
        }

        item = menu.add(new JMenuItem ("Import"));
        item.setToolTipText("Import data file");
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

    // update intrinsic clearance based on 
    protected void updateCLint () {
        ResultTableModel rtm = (ResultTableModel)resultTab.getModel();
        try {
            double conc = Double.parseDouble(cypConc.getText());
            rtm.setCYPConc(conc);
        }
        catch (NumberFormatException ex) {
            // revert back 
            cypConc.setText(String.valueOf(rtm.getCYPConc()));
            JOptionPane.showMessageDialog(this, "Bogus CYP concentration: "
                                          +cypConc.getText(), "Error",
                                          JOptionPane.ERROR_MESSAGE);
        }
    }

    public void actionPerformed (ActionEvent e) {
        String cmd = e.getActionCommand();
        if (cmd.equalsIgnoreCase("import")) {
            load ();
        }
        else if (cmd.equalsIgnoreCase("quit")) {
            quit ();
        }
        else if (cmd.startsWith("T")) {
            ListModel model = sampleList.getModel();
            if (model.getSize() == 0) {
                JOptionPane.showMessageDialog
                    (this, "No data loaded!", "Info", 
                     JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            for (int i = 0; i < model.getSize(); ++i) {
                SampleData data = (SampleData)model.getElementAt(i);
                
            }
        }
    }

    public void valueChanged (ListSelectionEvent e) {
        if (e.getValueIsAdjusting())
            return;

        ResultTableModel rtm = (ResultTableModel)resultTab.getModel();
        Object source = e.getSource();
        XYPlot plot = chartPane1.getChart().getXYPlot();
        plot.clearAnnotations();

        if (source == sampleList.getSelectionModel()) {
            plot.setDataset(0, null);
            plot.setDataset(1, null);

            SampleData data = (SampleData)sampleList.getSelectedValue();
            if (data != null) {
                rtm.setResults(data.getResults());
                Molecule mol = molDb.get(data.getName());
                mview.setM(0, mol);
                plot.setDataset(0, data.getDataset());

                plot = chartPane2.getChart().getXYPlot();
                plot.setDataset(0, data.getRatioDataset());
            }
            else {
                chartPane2.getChart().getXYPlot().setDataset(0, null);
            }
        }
        else if (source == resultTab.getSelectionModel()) {
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
    }

    protected void load () {
        chooser.setDialogTitle("Load txt data file...");
        int ans = chooser.showOpenDialog(this);
        if (JFileChooser.APPROVE_OPTION == ans) {
            File file = chooser.getSelectedFile();
            new LoadFileWorker (file).execute();
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

    protected DefaultListModel loadSamples 
        (boolean correction, InputStream is) throws IOException {
        DefaultListModel model = new DefaultListModel ();
        loadSamples (model, correction, is);
        return model;
    }

    protected void quit () {
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
