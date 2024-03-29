
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.*;
import javax.servlet.http.*;
import java.sql.*;


public class IQCAnnotationServlet extends HttpServlet {
    static final Logger logger = 
        Logger.getLogger(IQCAnnotationServlet.class.getName());

    static {
        try {
            //Class.forName("oracle.jdbc.driver.OracleDriver");
            Class.forName("com.mysql.jdbc.Driver");
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    String iqcJdbc;
    ServletContext context;

    Connection getConnection () throws SQLException {
        return DriverManager.getConnection(iqcJdbc);
    }

    @Override
    public void init (ServletConfig config) throws ServletException {
        context = config.getServletContext();
        try {
            iqcJdbc = context.getInitParameter("jdbc-iqc");
            if (iqcJdbc == null) {
                throw new ServletException
                    ("No jdbc-iqc parameter defined");
            }

            logger.info("## jdbc-iqc: "+iqcJdbc);
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Can't initialize servlet!", ex);
            throw new ServletException (ex);
        }
    }

    protected String[] getArgs (HttpServletRequest req) {
        String info = req.getPathInfo();
        logger.info(req.getContextPath()+": "+info);
        if (info == null || info.length() == 0) {
            return null;
        }

        return info.split("/");
    }

    protected boolean checkCredential (String cred) {
        if (cred == null || cred.length() == 0)
            return false;
        return true;
    }

    @Override
    public void destroy () {
        logger.info("shutting down "+context.getServletContextName()+"....");
    }

    @Override
    public void doPost (HttpServletRequest req, HttpServletResponse res)
        throws ServletException, IOException {
        PrintWriter pw = res.getWriter();

        //String[] args = getArgs (req);
        String info = req.getPathInfo();
        if (info == null || info.length() == 0) {
            pw.println("** Error: You don't have permission to POST here!");
            return;
        }
        String dataset = info.substring(1);

        String addr = req.getHeader("X-Real-IP");
        if (addr == null)
            addr = req.getRemoteAddr();
        logger.info(addr+": dataset="+dataset);

        Connection con = null;
        try {
            con = getConnection ();
            PreparedStatement pstm = con.prepareStatement
                ("insert into iqc_validator_annotation"
                 +"(dataset,sample,save,comments,curator) "
                 +"values(?,?,?,?,?)");
            BufferedReader br = new BufferedReader
                (new InputStreamReader (req.getInputStream()));
            int lines = 0, rows = 0;
            for (String line; (line = br.readLine()) != null; ++lines) {
                String[] fields = line.split("[\\|\t]");
                logger.info(addr+":"+lines+":"+fields.length+":"+line);
                if (fields.length == 3) {
                    try {
                        pstm.setString(1, dataset); // dataset
                        pstm.setString(2, fields[0]); // sample
                        boolean save = Boolean.parseBoolean(fields[1]);
                        pstm.setInt(3, save ? 1 : 0);
                        pstm.setString(4, addr);
                        pstm.setString(5, fields[2]); // curator
                        if (pstm.executeUpdate() > 0) {
                            ++rows;
                        }
                    }
                    catch (Exception ex) {
                        logger.log(Level.SEVERE, 
                                   lines+": Can't process input: "+line, ex);
                    }
                }
            }
            br.close();
            logger.info(addr+": "+rows+" row(s) inserted!");
            pw.println(dataset+" "+rows);
        }
        catch (SQLException ex) {
            ex.printStackTrace(pw);
        }
        finally {
            if (con != null) {
                try { con.close(); }
                catch (Exception ex) { ex.printStackTrace(); }
            }
        }
    }

    @Override
    public void doDelete (HttpServletRequest req, HttpServletResponse res)
        throws ServletException, IOException {
        PrintWriter pw = res.getWriter();

        //String[] args = getArgs (req);
        String info = req.getPathInfo();
        if (info == null || info.length() == 0) {
            pw.println("** Error: You don't have permission to DELETE here!");
            return;
        }

        String addr = req.getHeader("X-Real-IP");
        if (addr == null)
            addr = req.getRemoteAddr();

        Connection con = null;
        try {
            con = getConnection ();
            PreparedStatement pstm = con.prepareStatement
                ("delete from iqc_validator_annotation where anno_id = ?");
            BufferedReader br = new BufferedReader
                (new InputStreamReader (req.getInputStream()));
            int lines = 0, rows = 0;
            for (String line; (line = br.readLine()) != null; ++lines) {
                logger.info(addr+":"+lines+":"+line);
                try {
                    long id = Long.parseLong(line);
                    pstm.setLong(1, id);
                    if (pstm.executeUpdate() > 0) {
                        pw.println(line+": OK");
                        ++rows;
                    }
                }
                catch (SQLException ex) {
                    pw.println(line+": "+ex.getMessage());
                }
            }
            pw.println(rows+" row(s) deleted!");
        }
        catch (SQLException ex) {
            ex.printStackTrace(pw);
        }
        finally {
            if (con != null) {
                try { con.close(); }
                catch (Exception ex) { ex.printStackTrace(); }
            }
        }
    }

    @Override
    public void doGet (HttpServletRequest req, HttpServletResponse res)
        throws ServletException, IOException {
        PrintWriter pw = res.getWriter();

        //String[] args = getArgs (req);
        String info = req.getPathInfo();
        Connection con = null;
        try {
            con = getConnection ();
            if (info != null && info.length() > 0) {
                PreparedStatement pstm;
                int pos = info.indexOf('*');
                if (pos > 0) {
                    pstm = con.prepareStatement
                        ("select * from iqc_validator_annotation "
                         +"where dataset like '"+info.substring(1, pos)+"%' "
                         +"order by anno_id desc, sample");
                }
                else {
                    pstm = con.prepareStatement
                        ("select * from iqc_validator_annotation "
                         +"where dataset = ? "
                         +"order by anno_id desc, sample");
                    pstm.setString(1, info.substring(1));
                }
                
                //pstm.setString(2, "148.168.40.121");
                ResultSet rset = pstm.executeQuery();
                Map<String, Set<String>> datasets = new HashMap<>();
                while (rset.next()) {
                    String c = rset.getString("curator");
                    String s = rset.getString("sample");
                    String d = rset.getString("dataset");

                    Set<String> samples = datasets.get(d);
                    if (samples == null)
                        datasets.put(d, samples = new HashSet<>());
                    
                    String t = s.substring(0, s.indexOf('['));
                    if (!samples.contains(t)) {
                        pw.print(s+"\t"+
                                 rset.getInt("save")+"\t"+
                                 rset.getLong("anno_id")+"\t"+
                                 c);
                        if (pos > 0)
                            pw.print("\t"+d);
                        
                        pw.println();
                        samples.add(t);
                    }
                }
                rset.close();
                pstm.close();
            }
            else {
                Statement stm = con.createStatement();
                ResultSet rset = stm.executeQuery
                    ("select * from iqc_validator_annotation "
                     //+"where comments = '148.168.40.121' "
                     +"order by dataset, sample, annotation_time");
                while (rset.next()) {
                    pw.println(rset.getString("dataset")+"\t"+
                               rset.getString("sample")+"\t"+
                               rset.getInt("save")+"\t"+
                               rset.getLong("anno_id"));
                }
                rset.close();
                stm.close();
            }
        }
        catch (SQLException ex) {
            ex.printStackTrace(pw);
        }
        finally {
            if (con != null) {
                try { con.close(); }  
                catch (Exception ex) { ex.printStackTrace(); }
            }
        }
    }
}
