/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingjdbc.dbtest.init;

import com.google.common.base.Joiner;
import io.shardingjdbc.core.constant.DatabaseType;
import io.shardingjdbc.dbtest.IntegrateTestEnvironment;
import io.shardingjdbc.dbtest.common.DatabaseEnvironment;
import io.shardingjdbc.dbtest.config.AnalyzeDatabase;
import io.shardingjdbc.dbtest.config.AnalyzeSql;
import io.shardingjdbc.test.sql.SQLCasesLoader;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.lang3.StringUtils;
import org.h2.tools.RunScript;
import org.xml.sax.SAXException;

import javax.sql.DataSource;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertNotNull;

public final class DatabaseEnvironmentManager {
    
    private static final String DATABASE_INITIALIZATION_RESOURCES_PATH = "integrate/dbtest/%s/database.xml";
    
    private static final Set<String> DATABASES = new HashSet<>();
    
    /**
     * Add database.
     *
     * @param database database name
     */
    public static void addDatabase(final String database) {
        DATABASES.add(database);
    }
    
    /**
     * Create database.
     * 
     * @throws JAXBException jaxb exception
     * @throws SQLException sql exception
     * @throws IOException io exception
     */
    public static void createDatabase() throws JAXBException, SQLException, IOException {
        for (String database : DATABASES) {
            URL databaseInitializationResources = DatabaseEnvironmentManager.class.getClassLoader().getResource(String.format(DATABASE_INITIALIZATION_RESOURCES_PATH, database));
            assertNotNull(databaseInitializationResources);
            DatabaseInitialization databaseInitialization = unmarshal(databaseInitializationResources.getFile());
            for (DatabaseType each : IntegrateTestEnvironment.getInstance().getDatabaseTypes()) {
                if (each.equals(DatabaseType.H2)) {
                    continue;
                }
                try (
                        Connection conn = initialConnection(null, each);
                        StringReader stringReader = new StringReader(Joiner.on("\n").skipNulls().join(generateCreateDatabaseSQLs(each, databaseInitialization.getDatabases())))) {
                    ResultSet resultSet = RunScript.execute(conn, stringReader);
                    resultSet.close();
                }
            }
        }
    }
    
    private static DatabaseInitialization unmarshal(final String databaseInitializationFilePath) throws IOException, JAXBException {
        Unmarshaller unmarshal = JAXBContext.newInstance(DatabaseInitialization.class).createUnmarshaller();
        try (FileReader reader = new FileReader(databaseInitializationFilePath)) {
            return (DatabaseInitialization) unmarshal.unmarshal(reader);
        }
    }
    
    private static Collection<String> generateCreateDatabaseSQLs(final DatabaseType databaseType, final List<String> databases) {
        String baseSQL = DatabaseType.Oracle == databaseType ? "CREATE SCHEMA %s;" : "CREATE DATABASE %s;";
        Collection<String> result = new LinkedList<>();
        for (String each : databases) {
            result.add(String.format(baseSQL, each));
        }
        return result;
    }
    
    private static Connection initialConnection(final String dbName, final DatabaseType type) throws SQLException {
        return buildDataSource(dbName, type).getConnection();
    }
    
    public static DataSource buildDataSource(final String dbName, final DatabaseType type) {
        BasicDataSource result = new BasicDataSource();
        DatabaseEnvironment dbEnv = new DatabaseEnvironment(type);
        result.setDriverClassName(dbEnv.getDriverClassName());
        result.setUrl(dbEnv.getURL(dbName));
        result.setUsername(dbEnv.getUsername());
        result.setPassword(dbEnv.getPassword());
        result.setMaxTotal(1);
        if (DatabaseType.Oracle == type) {
            result.setConnectionInitSqls(Collections.singleton("ALTER SESSION SET CURRENT_SCHEMA = " + dbName));
        }
        return result;
    }
    
    /**
     * drop the database table.
     */
    public static synchronized void dropDatabase() {
        Connection conn = null;
        ResultSet resultSet = null;
        StringReader sr = null;
        try {
            for (String database : DATABASES) {
                String sql = getDropTableSql(DatabaseType.H2, AnalyzeDatabase.analyze(DatabaseEnvironmentManager.class.getClassLoader()
                        .getResource("integrate/dbtest").getPath() + "/" + database + "/database.xml"));
                for (DatabaseType each : IntegrateTestEnvironment.getInstance().getDatabaseTypes()) {
                    if (each.equals(DatabaseType.H2)) {
                        continue;
                    }
                    if (DatabaseType.Oracle.equals(each)) {
                        String oracleSql = getDropTableSql(DatabaseType.Oracle, AnalyzeDatabase.analyze(DatabaseEnvironmentManager.class.getClassLoader()
                                .getResource("integrate/dbtest").getPath() + "/" + database + "/database.xml"));
                        sr = new StringReader(oracleSql);
                        conn = initialConnection(null, each);
                        
                        resultSet = RunScript.execute(conn, sr);
                        
                    } else {
                        sr = new StringReader(sql);
                        conn = initialConnection(null, each);
                        
                        resultSet = RunScript.execute(conn, sr);
                    }
                }
            }
        } catch (SQLException | ParserConfigurationException | IOException | XPathExpressionException | SAXException e) {
            e.printStackTrace();
        } finally {
            if (sr != null) {
                sr.close();
            }
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    /**
     * Initialize the database table.
     */
    public static synchronized void createTable() {
        for (DatabaseType each : IntegrateTestEnvironment.getInstance().getDatabaseTypes()) {
            createSchema(each);
        }
    }
    
    private static void createSchema(final DatabaseType dbType) {
        createShardingSchema(dbType);
    }
    
    private static void createShardingSchema(final DatabaseType dbType) {
        Connection conn = null;
        ResultSet resultSet = null;
        StringReader sr = null;
        try {
            for (String each : DATABASES) {
                List<String> databases = AnalyzeDatabase.analyze(DatabaseEnvironmentManager.class.getClassLoader()
                        .getResource("integrate/dbtest").getPath() + "/" + each + "/database.xml");
                for (String database : databases) {
                    conn = initialConnection(database, dbType);
                    List<String> tableSqlIds = AnalyzeSql.analyze(DatabaseEnvironmentManager.class.getClassLoader()
                            .getResource("integrate/dbtest").getPath() + "/" + each + "/table/create-table.xml");
                    List<String> tableSqls = new ArrayList<>();
                    for (String tableSqlId : tableSqlIds) {
                        tableSqls.add(SQLCasesLoader.getInstance().getSchemaSQLCaseMap(tableSqlId));
                    }
                    sr = new StringReader(StringUtils.join(tableSqls, ";\n"));
                    resultSet = RunScript.execute(conn, sr);
                }
            }
        } catch (final SQLException | ParserConfigurationException | IOException | XPathExpressionException | SAXException ex) {
            ex.printStackTrace();
        } finally {
            if (sr != null) {
                sr.close();
            }
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    /**
     * @param dbType
     */
    public static void dropTable(final DatabaseType dbType, final String sqlId, final String dbname) {
        dropShardingSchema(dbType, sqlId, dbname);
    }
    
    /**
     * @param dbType
     */
    public static void createTable(final DatabaseType dbType, final String sqlId, final String dbname) {
        createShardingSchema(dbType, sqlId, dbname);
    }
    
    private static void createShardingSchema(final DatabaseType dbType, final String sqlId, final String dbname) {
        Connection conn = null;
        ResultSet resultSet = null;
        StringReader sr = null;
        try {
            
            for (String each : DATABASES) {
                if (dbname != null) {
                    if (!each.equals(dbname)) {
                        continue;
                    }
                }
                List<String> databases = AnalyzeDatabase.analyze(DatabaseEnvironmentManager.class.getClassLoader()
                        .getResource("integrate/dbtest").getPath() + "/" + each + "/database.xml");
                for (String database : databases) {
                    conn = initialConnection(database, dbType);
                    List<String> tableSqls = new ArrayList<>();
                    tableSqls.add(SQLCasesLoader.getInstance().getSchemaSQLCaseMap(sqlId));
                    sr = new StringReader(StringUtils.join(tableSqls, ";\n"));
                    resultSet = RunScript.execute(conn, sr);
                }
            }
        } catch (final SQLException | ParserConfigurationException | IOException | XPathExpressionException | SAXException ex) {
            ex.printStackTrace();
        } finally {
            if (sr != null) {
                sr.close();
            }
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    private static void dropShardingSchema(final DatabaseType dbType, final String sqlId, final String dbname) {
        Connection conn = null;
        ResultSet resultSet = null;
        StringReader sr = null;
        try {
            for (String each : DATABASES) {
                if (dbname != null) {
                    if (!each.equals(dbname)) {
                        continue;
                    }
                }
                List<String> databases = AnalyzeDatabase.analyze(DatabaseEnvironmentManager.class.getClassLoader()
                        .getResource("integrate/dbtest").getPath() + "/" + each + "/database.xml");
                for (String database : databases) {
                    conn = initialConnection(database, dbType);
                    List<String> tableSqls = new ArrayList<>();
                    tableSqls.add(SQLCasesLoader.getInstance().getSchemaSQLCaseMap(sqlId));
                    
                    sr = new StringReader(StringUtils.join(tableSqls, ";\n"));
                    
                    resultSet = RunScript.execute(conn, sr);
                }
            }
            
        } catch (SQLException | ParserConfigurationException | IOException | XPathExpressionException | SAXException e) {
            // The table may not exist at the time of deletion（删除时可能表不存在）
            //e.printStackTrace();
        } finally {
            if (sr != null) {
                sr.close();
            }
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    private static String getDropTableSql(final DatabaseType databaseType, final List<String> databases) {
        String basesql = "DROP DATABASE ";
        if (DatabaseType.Oracle == databaseType) {
            basesql = "DROP SCHEMA ";
        }
        List<String> sqls = new ArrayList<>();
        for (String database : databases) {
            sqls.add(basesql + database + ";");
        }
        return StringUtils.join(sqls, "\n");
    }
    
    /**
     * Get the database type enumeration.
     *
     * @param typeStrs String database type
     * @return database enumeration
     */
    public static List<DatabaseType> getDatabaseTypes(final String typeStrs) {
        if (StringUtils.isBlank(typeStrs)) {
            return Arrays.asList(DatabaseType.values());
        }
        String[] types = StringUtils.split(typeStrs, ",");
        List<DatabaseType> result = new ArrayList<>();
        for (String eachType : types) {
            DatabaseType[] databaseTypeSrcs = DatabaseType.values();
            for (DatabaseType each : databaseTypeSrcs) {
                if (eachType.equalsIgnoreCase(each.name())) {
                    result.add(each);
                }
            }
        }
        return result;
    }
}
