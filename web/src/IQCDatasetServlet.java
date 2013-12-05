
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

    File datasetDir;
    ServletContext context;

    @Override
    public void init (ServletConfig config) throws ServletException {
        context = config.getServletContext();
        try {
            String param = context.getInitParameter("dataset-dir");
            if (param == null) {
                throw new ServletException
                    ("No dataset-dir parameter defined!");
            }

            logger.info("## dataset-dir: "+param);
            datasetDir = new File (param);
            if (!datasetDir.exists()) {
                datasetDir.mkdir();
            }
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Can't initialize servlet!", ex);
	    throw new ServletException (ex);
        }
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

	File file = new File (datasetDir, item.getName());
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

	return new Object[]{file.getName(), size, sb.toString()};
    }

    @Override
    public void doGet (HttpServletRequest req, HttpServletResponse res)
	throws ServletException, IOException {

        String info = req.getPathInfo();
        logger.info(req.getContextPath()+": "+info);
        if (info == null || info.length() <= 1) {
            PrintWriter pw = res.getWriter();
            for (String f : datasetDir.list()) {
                pw.println(f);
            }
            return;
        }
        
        String[] args = info.split("/");
        File file = new File (datasetDir, args[1]);
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
            res.getWriter().println("Dataset \""+args[1]+"\" not found!");
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

        String[] args = info.split("/");
        File file = new File (datasetDir, args[1]);
        if (file.exists()) {
            file.delete();
            res.getWriter().println(args[1]+" deleted!");
        }
        else {
            res.getWriter().println("Dataset \""+args[1]+"\" not found!");
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
