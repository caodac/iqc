package tripod.iqc.service;

import java.util.logging.Logger;
import java.util.logging.Level;
import javax.sql.DataSource;
import java.sql.*;

import tripod.iqc.core.*;

public class PersistenceManager {
    private static final Logger logger = 
        Logger.getLogger(PersistenceManager.class.getName());

    private DataSource datasource;

    public PersistenceManager (DataSource datasource) {
        this.datasource = datasource;
    }
}
