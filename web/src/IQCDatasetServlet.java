
import java.io.*;
import java.net.*;
import java.util.*;
import java.security.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.*;
import javax.servlet.http.*;
import java.sql.*;

import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.*;
import org.apache.commons.fileupload.*;


public class IQCDatasetServlet extends HttpServlet {
    static final Logger logger = 
        Logger.getLogger(IQCDatasetServlet.class.getName());

    static {
        try {
            //Class.forName("oracle.jdbc.driver.OracleDriver");
            Class.forName("com.mysql.jdbc.Driver");
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    File datasetDir;
    ServletContext context;
    String iqcJdbc;
    String base;
    
    @Override
    public void init (ServletConfig config) throws ServletException {
        context = config.getServletContext();
        try {
            String param = context.getInitParameter("dataset-dir");
            if (param == null) {
                throw new ServletException
                    ("No dataset-dir parameter defined!");
            }

            datasetDir = new File (param);
            if (!datasetDir.exists()) {
                datasetDir.mkdir();
            }
            base = datasetDir.getCanonicalPath();
            logger.info("## dataset-dir: "+param+" => base: "+base);        
            
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

    Connection getConnection () throws SQLException {
        return DriverManager.getConnection(iqcJdbc);
    }
    
    @Override
    public void destroy () {
        logger.info("shutting down "+context.getServletContextName()+"....");
    }

    @Override
    public void doPost (HttpServletRequest req, HttpServletResponse res)
        throws ServletException, IOException {
        PrintWriter pw = res.getWriter();
        boolean multipart = ServletFileUpload.isMultipartContent(req);
        if (!multipart) {
            throw new ServletException ("Not a multipart request");
        }
        
        ServletFileUpload upload = new ServletFileUpload();
        try {
            Object[] out = null;
            FileItemIterator iter = upload.getItemIterator(req);
            while (iter.hasNext()) {
                FileItemStream item = iter.next();
                String name = item.getFieldName();
                if (item.isFormField()) {
                    String field = item.getFieldName();
                    String value = Streams.asString(item.openStream());
                    logger.info("Form field \""+field+"\" with "
                                +"value \""+value+"\" detected!");
                }
                else {
                    logger.info
                        ("File field " + name + " with file name "
                         + item.getName() + " detected.");
                    // Process the input stream
                    out = saveFile (item);
                    logger.info("Successfully uploaded file \""
                                +item.getName()+"\"");
                }
            }
        
            if (out != null) {
                pw.println(out[2]+"\t"+out[1]);
            }
        }
        catch (Exception ex) {
            ex.printStackTrace(pw);
        }
    }

    Object[] saveFile (FileItemStream item) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA1");

        String name = item.getName();
        String[] toks = name.split("/");
        File dir = datasetDir;
        if (toks.length > 1) {
            for (int i = 0; i < toks.length-1; ++i) {
                dir = new File (dir, toks[i]);
                dir.mkdirs();
            }
            name = toks[toks.length-1];
        }
        
        File file = new File (dir, name);
        byte[] buf = new byte[1024];
        InputStream is = item.openStream();
        DigestOutputStream dos = 
            new DigestOutputStream (new FileOutputStream (file), md);
        long size  = 0;
        for (int nb; (nb = is.read(buf, 0, buf.length)) > 0; ) {
            dos.write(buf, 0, nb);
            size += nb;
        }
        dos.close();

        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < digest.length; ++i)
            sb.append(String.format("%1$02x", digest[i] & 0xff));
        logger.info("### "+item.getName()+" ==>> "+file);

        return new Object[]{file.getName(), size, sb.toString()};
    }

    @Override
    public void doGet (HttpServletRequest req, HttpServletResponse res)
        throws ServletException, IOException {

        String info = req.getPathInfo();
        logger.info(req.getContextPath()+": "+info);
        if (info == null || info.length() <= 1) {
            PrintWriter pw = res.getWriter();
            try {
                listDatasets (pw);
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
            return;
        }
        
        String name = info.substring(1);
        File file = new File (datasetDir, name);
        if (file.exists()) {
            InputStream is = new FileInputStream (file);
            OutputStream out = res.getOutputStream();
            byte[] buf = new byte[1024];
            for (int nb; (nb = is.read(buf, 0, buf.length)) > 0; ) {
                out.write(buf, 0, nb);
            }
            is.close();
            out.close();
        }
        else {
            res.getWriter().println("Dataset \""+name+"\" not found!");
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Override
    public void doDelete (HttpServletRequest req, HttpServletResponse res)
        throws ServletException, IOException {

        String info = req.getPathInfo();
        logger.info(req.getContextPath()+": "+info);
        if (info == null || info.length() <= 1) 
            return;

        //String[] args = info.split("/");
        String name = info.substring(1);
        File file = new File (datasetDir, name);
        if (file.exists()) {
            file.delete();
            res.getWriter().println(name+" deleted!");
        }
        else {
            res.getWriter().println("Dataset \""+name+"\" not found!");
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    void listDatasets (PrintWriter pw) throws Exception {
        Connection con = getConnection ();
        try {
            PreparedStatement pstm = con.prepareStatement
                ("select count(*) from iqc_validator_annotation where "
                 +"dataset = ?");

            for (File f : getFilesRecursive (datasetDir)) {
                String name = f.getCanonicalPath();
                int pos = name.indexOf(base);
                if (pos >= 0) {
                    name = name.substring(base.length()+1);
                }
                pstm.setString(1, name);
                ResultSet rset = pstm.executeQuery();
                if (rset.next()) {
                    pw.println(name+"\t"+rset.getInt(1));
                }
                else {
                    pw.println(name);
                }
                rset.close();
            }
            pstm.close();
        }
        finally {
            con.close();
        }
    }

    static List<File> getFilesRecursive (File file) {
        List<File> files = new ArrayList<File>();
        getFilesRecursive (files, file);
        Collections.sort(files, new Comparator<File> () {
                public int compare (File f1, File f2) {
                    long d = f2.lastModified() - f1.lastModified();
                    if (d < 0l) return -1;
                    if (d > 0l) return 1;
                    return f1.compareTo(f2);
                }
            });
        return files;
    }

    static void getFilesRecursive (List<File> files, File file) {
        if (file.isFile()) {
            files.add(file);
        }
        else {
            for (File f : file.listFiles()) {
                getFilesRecursive (files, f);
            }
        }
    }
}
