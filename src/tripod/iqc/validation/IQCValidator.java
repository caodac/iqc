package tripod.iqc.validator;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;

import javax.swing.*;
import javax.swing.table.*;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.ListSelectionEvent;
import java.awt.Color;
import java.awt.event.*;
import java.awt.BorderLayout;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.jfree.chart.*;
import org.jfree.chart.plot.*;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.DefaultXYDataset;
import org.jfree.chart.renderer.xy.*;
import org.jfree.chart.annotations.*;

import tripod.iqc.core.*;


public class IQCValidator extends JFrame 
    implements ActionListener, ListSelectionListener {
    static final Logger logger = 
        Logger.getLogger(IQCValidator.class.getName());


    static class SampleData {
        Sample sample;
        List<Estimator.Result> results;
        DefaultXYDataset dataset;

        SampleData (Sample sample, List<Estimator.Result> results) {
            this.sample = sample;
            this.results = results;
        }

        public int size () { return results.size(); }
        public Estimator.Result getResult (int i) { return results.get(i); }
        public List<Estimator.Result> getResults () { return results; }

        public XYDataset getDataset () {
            if (dataset == null) {
                dataset = new DefaultXYDataset ();

                List<Measure> measures = new ArrayList<Measure>();
                for (Measure m : sample.getMeasures()) {
                    Double r = m.getResponse();
                    Double t = m.getTime();
                    if (m.getBlank() || r == null || t == null) {
                        // skip this 
                    }
                    else {
                        measures.add(m);
                    }
                }
                
                double[][] data = new double[2][measures.size()];
                for (int i = 0; i < measures.size(); ++i) {
                    Double r = measures.get(i).getResponse();
                    Double t = measures.get(i).getTime();
                    data[0][i] = Math.log(r);
                    data[1][i] = t;
                }
                dataset.addSeries(sample.getName(), data);
            }
            return dataset;
        }

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
                TxtReader reader = new TxtReader (new FileInputStream (file));
                Estimator estimator = new LeastSquaresEstimator ();
                for (Sample s; (s = reader.read()) != null; ) {
                    String name = s.getName();
                    if (name.equalsIgnoreCase("albendazole") 
                        || name.equalsIgnoreCase("blank"))
                        ;
                    else {
                        SampleData data = new SampleData 
                            (s, estimator.estimate(s));
                        model.addElement(data);
                    }
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
            "Rank",
            "Comments",
            "Selection",
            "Slope",
            "Intercept",
            "MSE",
            "R"
        };

        XYDataset[] datasets;
        XYAnnotation[][] annotations;

        ResultTableModel () {
        }

        public int getColumnCount () { return columns.length; }
        public String getColumnName (int col) { return columns[col]; }
        public Class getColumnClass (int col) {
            switch (col) {
            case 0: return Integer.class; // Rank
            case 1: return String.class; // Comments
            case 2: return String.class; // Selection
            case 3: return Double.class; // Slope
            case 4: return Double.class; // Intercept
            case 5: return Double.class; // MSE
            case 6: return Double.class; // R
            }
            return Object.class;
        }
                
        public int getRowCount () { 
            return results != null ? results.size() : 0;
        }

        public Object getValueAt (int row, int col) {
            Estimator.Result result = results.get(row);
            switch (col) {
            case 0: return result.getRank(); 
            case 1: return result.getComments(); 
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
            }

            FitModel model = result.getModel();
            FitModel.Variable var = model.getVariable(columns[col]);
            return var != null ? var.getValue() : null;
        }

        @Override
        public boolean isCellEditable (int row, int col) {
            return col == 0 || col == 1;
        }

        public void setValueAt (Object value, int row, int col) {
            if (col == 0) {
                try {
                    int rank = Integer.parseInt(value.toString());
                    if (rank < 1 || rank > 5) {
                        throw new IllegalArgumentException
                            ("Invalid rank "+rank+"; please specify rank "
                             +"within the range [1,5]!");
                    }

                    Estimator.Result result = results.get(row);
                    result.setRank(rank);
                }
                catch (NumberFormatException ex) {
                    throw new IllegalArgumentException ("Bogus rank "+value);
                }
            }
            else if (col == 1) {
                Estimator.Result result = results.get(row);
                result.setComments(value != null ? value.toString() : null);
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

            fireTableDataChanged ();
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
                data[0][0] = -5*slope.getValue() + intercept.getValue(); 
                data[0][1] = 65.*slope.getValue() + intercept.getValue();
                data[1][0] = -5;
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
                        XYAnnotation a = new XYPointerAnnotation
                            (String.valueOf(i+1),  
                             Math.log(m.getResponse()), m.getTime(), angle);
                        anno.add(a);
                    }
                }
                annotations[row] = annos = anno.toArray(new XYAnnotation[0]);
            }
            return annos;
        }
    }

    private JFileChooser chooser;
    private JList sampleList;
    private JTable resultTab;
    private ChartPanel chartPane;

    public IQCValidator (String[] argv) {
        initUI ();
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
        JPanel pane = new JPanel (new BorderLayout ());
        pane.setBorder(BorderFactory.createCompoundBorder
                       (BorderFactory.createTitledBorder("Samples"),
                        BorderFactory.createEmptyBorder(1,1,1,1)));

        pane.add(new JScrollPane (sampleList = new JList ()));
        sampleList.getSelectionModel().setSelectionMode
            (ListSelectionModel.SINGLE_SELECTION);
        sampleList.getSelectionModel().addListSelectionListener(this);

        return pane;
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
        JPanel pane = new JPanel (new BorderLayout ());
        pane.setBorder(BorderFactory.createCompoundBorder
                       (BorderFactory.createTitledBorder("Plot"),
                        BorderFactory.createEmptyBorder(1,1,1,1)));

        chartPane = new ChartPanel
            (ChartFactory.createScatterPlot
             (null, "Ln (Response)", "Time (Minute)", null, 
              PlotOrientation.HORIZONTAL, true, false, false));
        chartPane.setBackground(Color.white);
	chartPane.getChart().setBorderPaint(Color.white);
	chartPane.getChart().setBackgroundPaint(Color.white);
	chartPane.getChart().getPlot().setBackgroundAlpha(.5f);
	chartPane.getChart().getXYPlot().setRangeGridlinesVisible(false);
	//cp.getChart().getXYPlot().setDomainCrosshairVisible(false);
	chartPane.getChart().getXYPlot().setDomainGridlinesVisible(false);

	// main renderer
	XYItemRenderer renderer = new  XYLineAndShapeRenderer ();
        renderer.setSeriesPaint(0, Color.black);
	chartPane.getChart().getXYPlot().setRenderer(0, renderer); 
	chartPane.getChart().getXYPlot().setRenderer
	    (1, renderer = new  XYLineAndShapeRenderer ());// mask renderer
        renderer.setSeriesPaint(0, Color.red);
        renderer.setSeriesPaint(1, Color.blue);
        renderer.setSeriesVisibleInLegend(1, false);

        pane.add(chartPane);

        return pane;
    }

    protected JComponent createResultPane () {
        JPanel pane = new JPanel (new BorderLayout ());
        pane.setBorder(BorderFactory.createCompoundBorder
                       (BorderFactory.createTitledBorder("Results"),
                        BorderFactory.createEmptyBorder(1,1,1,1)));

        resultTab = new JTable (new ResultTableModel ());
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
        item = menu.add(new JMenuItem ("Load"));
        item.setToolTipText("Load data file");
        item.addActionListener(this);

        menu.addSeparator();
        item = menu.add(new JMenuItem ("Quit"));
        item.addActionListener(this);

        return menubar;
    }

    public void actionPerformed (ActionEvent e) {
        String cmd = e.getActionCommand();
        if (cmd.equalsIgnoreCase("load")) {
            load ();
        }
        else if (cmd.equalsIgnoreCase("quit")) {
            quit ();
        }
    }

    public void valueChanged (ListSelectionEvent e) {
        if (e.getValueIsAdjusting())
            return;

        ResultTableModel rtm = (ResultTableModel)resultTab.getModel();
        Object source = e.getSource();
        XYPlot plot = chartPane.getChart().getXYPlot();
        plot.clearAnnotations();

        if (source == sampleList.getSelectionModel()) {
            SampleData data = (SampleData)sampleList.getSelectedValue();
            rtm.setResults(data.getResults());
            plot.setDataset(0, data.getDataset());
            plot.setDataset(1, null);
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
