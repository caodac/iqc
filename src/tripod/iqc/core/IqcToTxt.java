package tripod.iqc.core;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import java.net.URI;
import java.net.URL;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.apache.poi.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.poifs.filesystem.*;

public class IqcToTxt implements Comparator<IqcToTxt.Sample> {
    static final Logger logger = Logger.getLogger(IqcToTxt.class.getName());

    static Pattern MINREGEX =
        Pattern.compile("([\\d+]+)\\smin(\\s\\(([\\d])\\))?");

    static class Sample {
        String name;
        int min;
        Object value;
        Object standard;

        Sample (String name, int min, Object value, Object standard) {
            this.name = name;
            this.min = min;
            this.value = value;
            this.standard = standard;
        }
    }

    Map<String, List<Sample>> samples = new TreeMap<String, List<Sample>>();

    public int compare (Sample s1, Sample s2) {
        int d = s1.name.compareTo(s2.name);
        if (d == 0) {
            d = s1.min - s2.min;
        }
        
        return d;
    }

    public IqcToTxt () {
    }

    public IqcToTxt (ZipFile zf) throws Exception {
        parse (zf);
    }

    void parseXlsx (String name, InputStream is) throws Exception {
        Workbook wb = new XSSFWorkbook (is);
        Sheet sheet = wb.getSheetAt(0);
        parseSheet (name, sheet);
    }

    void parseSheet (String name, Sheet sheet) throws Exception {
        int min = -1, repl = 1;
        Matcher m = MINREGEX.matcher(name);
        if (m.find()) {
            min = Integer.parseInt(m.group(1));
            String g = m.group(3);
            if (g != null) {
                repl = Integer.parseInt(g);
            }
        }
        name = name.toLowerCase();
        //System.out.println("++ "+name+" min: "+min+" repl: "+repl);
        
        java.util.Iterator<Row> rowIter = sheet.rowIterator();
        int nrows = 0;
        Object[] header = null;
        Object standard = null;

        while (rowIter.hasNext()) {
            Row row = rowIter.next();
            //System.err.println("[Row "+row.getRowNum()+"]");
            
            java.util.Iterator<Cell> cellIter = row.cellIterator();
            List r = new ArrayList ();

            boolean matched = false;
            for (int col = 0; cellIter.hasNext(); ++col) {
                Cell c = cellIter.next();
                
                Object value = null;
                switch (c.getCellType()) {
                case Cell.CELL_TYPE_BOOLEAN:
                    r.add(value = c.getBooleanCellValue());
                    break;
                    
                case Cell.CELL_TYPE_NUMERIC:
                    r.add(value = c.getNumericCellValue());
                    break;
                    
                case Cell.CELL_TYPE_STRING:
                    { String s = c.getStringCellValue();
                        r.add(value = s);
                        if (col == 0) {
                            int pos = name.indexOf(s.toLowerCase());
                            if (pos >= 0) {
                                matched = true;
                            }
                        }
                    }
                    break;
                        
                case Cell.CELL_TYPE_BLANK:
                case Cell.CELL_TYPE_FORMULA:
                default:
                    r.add(null);
                    break;
                }
                /*
                logger.info("cell("+c.getRowIndex()+","
                            +c.getColumnIndex()+")="+"\""+value+"\"");
                */
            }
            
            if (nrows == 0) {
                /*
                System.err.print("Header:");
                for (int i = 0; i < r.size(); ++i) {
                    System.err.print(" "+i+"["+r.get(i)+"]");
                }
                System.err.println();
                */
                header = r.toArray(new Object[0]);
            }
            else if (matched) {
                Object col3 = r.get(3);
                Object col4 = r.get(4);
                //System.out.println(r.get(0)+"\t"+col3+"\t"+col4);
                if ("Linear".equals(col4)) {
                    if (standard == null) {
                        logger.log(Level.SEVERE, "No standard found!");
                    }
                    else if (col3 instanceof Number || "N/F".equals(col3)) {
                        String s = r.get(0)+"-"+repl;
                        List<Sample> list = samples.get(s);
                        if (list == null) {
                            samples.put(s, list = new ArrayList<Sample>());
                        }

                        list.add(new Sample (s, min, col3, standard));
                    }
                }
                else { // assume standard
                    //logger.info("## standard "+r.get(0)+": "+col4);
                    if (!(col4 instanceof Double)) {
                        logger.warning(name+": standard "+r.get(0)
                                       +" response is "+col4);
                    }
                    standard = col4;
                }
            }
            
            ++nrows;
        }
    }

    public void parse (ZipFile zf) throws Exception {
        samples.clear();
        for (Enumeration<? extends ZipEntry> en = zf.entries();
             en.hasMoreElements();) {
            ZipEntry ze = en.nextElement();
            try {
                String name = ze.getName();
                if (name.indexOf(".xlsx") > 0) {
                    //logger.info(name);
                    parseXlsx (name, zf.getInputStream(ze));
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public void write (OutputStream os) throws IOException {
        PrintStream ps = new PrintStream (os);
        ps.println("Sample,T0,T0-S,T5,T5-S,T10,T10-S,T15,T15-S,T30,T30-S,T60,T60-S");
        for (List<Sample> list : samples.values()) {
            Collections.sort(list, this);
            Iterator<Sample> it = list.iterator();
            Map<Integer, Sample> response = new HashMap<Integer, Sample>();
            for (int c = 0; it.hasNext();++c) {
                Sample s = it.next();
                if (c == 0)
                    ps.print(s.name);
                response.put(s.min, s);
            }
            write (ps, response, 0);
            write (ps, response, 5);
            write (ps, response, 10);
            write (ps, response, 15);
            write (ps, response, 30);
            write (ps, response, 60);
            ps.println();
        }
    }

    static void write (PrintStream ps, Map<Integer, Sample> response, int min) {
        Sample s = response.get(min);
        if (s != null) {
            ps.print(","+s.value+","+s.standard);
        }
        else {
            ps.print(",,");
        }
    }
    
    public static void main (String[] argv) throws Exception {
        if (argv.length == 0) {
            System.err.println("Usage: IqcToTxt ZIPS...");
            System.exit(1);
        }

        for (String a : argv) {
            try {
                ZipFile zf = new ZipFile (a);
                IqcToTxt txt = new IqcToTxt (zf);
                txt.write(System.out);
            }
            catch (Exception ex) {
                logger.warning(a+": "+ex.getMessage());
            }
        }
    }
}
