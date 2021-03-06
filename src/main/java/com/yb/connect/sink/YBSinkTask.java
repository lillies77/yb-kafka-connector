// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

package com.yb.connect.sink;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TimeZone;
import java.util.stream.Collectors;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.data.Struct;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YugaByte DB Sink Task.
 */
public class YBSinkTask extends SinkTask {
    private static final Logger LOG = LoggerFactory.getLogger(YBSinkTask.class);
    private static final String VERSION = "1";

    private String keyspace = null;
    private String tablename = null;
    private Cluster cassandraCluster = null;
    private Session cassandraSession = null;
    private Map<String, DataType> columnNameToType = null;

    @Override
    public void start(final Map<String, String> properties) {
        this.keyspace = properties.get("yugabyte.cql.keyspace");
        if (this.keyspace == null || this.keyspace.isEmpty()) {
            throw new IllegalArgumentException("Need a valid value for 'yugabyte.cql.keyspace'.");
        }
        this.tablename = properties.get("yugabyte.cql.tablename");
        if (this.tablename == null || this.tablename.isEmpty()) {
            throw new IllegalArgumentException("Need a valid value for 'yugabyte.cql.tablename'.");
        }
        LOG.info("Start with keyspace=" + this.keyspace + ", table=" + this.tablename);
        List<ContactPoint> contactPoints =
            getContactPoints(properties.get("yugabyte.cql.contact.points"));
        this.cassandraSession = getCassandraSession(contactPoints);
        if (this.cassandraSession == null) {
            throw new IllegalArgumentException("Could not connect to 'yugabyte.cql.contact.points'"
                + contactPoints);
        }
        columnNameToType = new HashMap<String, DataType>();
    }

    @Override
    public void put(final Collection<SinkRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        LOG.info("Processing " + records.size() + " records from Kafka.");
        List<Statement> statements = getStatements(records);
        if (statements.isEmpty()) {
            LOG.info("No valid statements were received.");
            return;
        }
        statements.forEach(statement -> cassandraSession.execute(statement));
    }

    @Override
    public void stop() {
        if (cassandraSession != null) {
            cassandraSession.close();
        }
        if (cassandraCluster != null) {
            cassandraCluster.close();
        }
    }

    @Override
    public String version() {
        return VERSION;
    }

    // Helper class for host/port information to the YugaByte DB cluster.
    private class ContactPoint {
        private String host;
        private int port;

        public ContactPoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String ToString() { return host + ":" + port; }
    }

    private ContactPoint fromHostPort(String hostPort) {
        String[] parts = hostPort.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid host:port format " + hostPort);
        }
        return new ContactPoint(parts[0], Integer.parseInt(parts[1]));
    }

    private List<ContactPoint> getContactPoints(String csvHostPorts) {
        if (csvHostPorts == null) {
            throw new IllegalArgumentException("Invalid null cql contact point list");
        }
        List<ContactPoint> contactPoints = new ArrayList<ContactPoint>();
        String[] hostPorts = csvHostPorts.split(",");
        for (String hostPort : hostPorts) {
            contactPoints.add(fromHostPort(hostPort));
        }
        return contactPoints;
    }

    private Session getCassandraSession(List<ContactPoint> contactPoints) {
        if (cassandraSession == null) {
            createCassandraClient(contactPoints);
        }
        return cassandraSession;
    }

    /**
     * Create a Cassandra client and session to YugaByte DB's CQL contact points.
     */
    private synchronized void createCassandraClient(List<ContactPoint> contactPoints) {
        if (cassandraCluster == null) {
            Cluster.Builder builder = Cluster.builder();
            Integer port = null;
            for (ContactPoint cp : contactPoints) {
               if (port == null) {
                    port = cp.getPort();
                    builder.withPort(port);
                } else if (port != cp.getPort()) {
                    throw new IllegalArgumentException("Using multiple CQL ports is not supported.");
                }
                builder.addContactPoint(cp.getHost());
            }
            LOG.info("Connecting to nodes: " + builder.getContactPoints().stream()
                     .map(it -> it.toString()).collect(Collectors.joining(",")));
            cassandraCluster = builder.build();
        }
        if (cassandraSession == null) {
            cassandraSession = cassandraCluster.connect();
            LOG.info("Connected to cluster: " + cassandraCluster.getClusterName());
        }
    }

    // Used to store schema information from the table.
    private class ColumnInfo {
        private String name;
        private DataType dataType;

        ColumnInfo(String colname, DataType datatype) {
            name = colname;
            dataType = datatype;
        }
    }

    private String generateInsert(List<ColumnInfo> cols) {
        String insert = "INSERT INTO " + keyspace + "." + tablename + "(" + cols.get(0).name;
        String comma = ",";
        String quesMarks = "?";
        for (int i = 1; i < cols.size(); i++) {
            insert = insert + comma + cols.get(i).name;
            quesMarks = quesMarks + comma + "?";
        }
        insert = insert + ") VALUES (" + quesMarks + ")";
        return insert;
    }

    private List<ColumnInfo> getColsFromTable() {
        KeyspaceMetadata km = cassandraSession.getCluster().getMetadata().getKeyspace(keyspace);
        if (null == km) {
            throw new IllegalArgumentException("Keyspace " + keyspace + " not found.");
        }
        TableMetadata tm = km.getTable(tablename);
        if (null == tm) {
            throw new IllegalArgumentException("Table " + tablename + " not found.");
        }
        List<ColumnInfo> cols = new ArrayList<ColumnInfo>();
        for (ColumnMetadata cm : tm.getColumns()) {
            cols.add(new ColumnInfo(cm.getName(), cm.getType()));
            columnNameToType.put(cm.getName(), cm.getType());
            LOG.info("Add column " + cm.getName() + " of type " + cm.getType());
        }
        return cols;
    }

    // Helper function to parse a string into a Date type
    private Date getTimeStamp(String val) {
        Calendar cal = new GregorianCalendar();
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        cal.setTimeInMillis(0);
        String errMsg = "Invalid timestamp format for " + val +
                        ". Expect 'yyyy-mm-dd hh:mm:ss'.";
        Date date = null;
        // TODO: Allow more patterns.
        String pattern = "yyyy-MM-dd HH:mm:ss";
        try {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
            date = simpleDateFormat.parse(val);
        } catch (ParseException pe) {
            throw new IllegalArgumentException(errMsg + "Error: " + pe);
        }
        return date;
    }

    private BoundStatement bindByColumnType(BoundStatement statement, String colName,
                                            DataType.Name typeName,  Object value) {
        LOG.info("Bind '" + colName + "' of type " + typeName);
        switch (typeName) {
            case INT:
                if (!(value instanceof Integer)) {
                    throw new IllegalArgumentException("Value should be of type Integer.");
                }
                statement = statement.setInt(colName, (Integer)value);
                break;
            case BIGINT:
                statement = statement.setLong(colName, (Long)value);
                break;
            case FLOAT:
                statement = statement.setFloat(colName, (Float)value);
                break;
            case DOUBLE:
                statement = statement.setDouble(colName, (Double)value);
                break;
            case VARCHAR:
            case TEXT:
                statement = statement.setString(colName, (String)value);
                break;
            case BOOLEAN:
                statement = statement.setBool(colName, (Boolean)value);
                break;
            case BLOB:
                ByteBuffer bb = null;
                if (value instanceof ByteBuffer) {
                    bb = (ByteBuffer)value;
                } else {
                    bb = ByteBuffer.wrap((byte[])value);
                }
                statement = statement.setBytes(colName, bb);
                break;
            case TIMESTAMP:
                if (value instanceof String) {
                    statement = statement.setTimestamp(colName, getTimeStamp((String) value));
                } else if (value instanceof Integer || value instanceof Long) {
                    Calendar cal = new GregorianCalendar();
                    Long val = (Long) value;
                    cal.setTimeInMillis(val);
                    statement = statement.setTimestamp(colName, cal.getTime());
                } else {
                    String errMsg = "Timestamp can be set as string or integer or long only.";
                    LOG.info(errMsg);
                    throw new IllegalArgumentException(errMsg);
                }
                break;
            default:
                String errMsg = "Column type " + typeName + " for " + colName +
                                " not supported yet.";
                LOG.error(errMsg);
                throw new IllegalArgumentException(errMsg);
        }
        return statement;
    }

    private BoundStatement bindByType(BoundStatement statement, Schema schema, Object value,
                                      DataType colType) {
        switch (schema.type()) {
          case INT8:
            statement = statement.setInt(schema.name(), (Byte)value);
            break;
          case INT16:
            statement = statement.setShort(schema.name(), (Short)value);
            break;
          case INT32:
            statement = statement.setInt(schema.name(), (Integer)value);
            break;
          case INT64:
            statement = statement.setLong(schema.name(), (Long)value);
            break;
          case FLOAT32:
            statement = statement.setFloat(schema.name(), (Float)value);
            break;
          case FLOAT64:
            statement = statement.setDouble(schema.name(), (Double)value);
            break;
          case BOOLEAN:
            statement = statement.setBool(schema.name(), (Boolean)value);
            break;
          case STRING:
            // TODO: Check the table type and convert types like timestamp.
            // if (colType == TIMESTAMP) { } else {
            statement = statement.setString(schema.name(), (String)value);
            break;
          case BYTES:
            ByteBuffer bb = null;
            if (value instanceof ByteBuffer) {
                bb = (ByteBuffer)value;
            } else {
                bb = ByteBuffer.wrap((byte[])value);
            }
            statement = statement.setBytes(schema.name(), bb);
            break;
          default:
            String errMsg = "Schema type " + schema.type() + " for " + schema.name() +
                            " not supported yet.";
            LOG.error(errMsg);
            throw new IllegalArgumentException(errMsg);
        }
        return statement;
    }

    // Helper function to sanitize the record value json/map keys and also return a column name
    // mapping table (lower case) names to value keys.
    private Map<String, String> sanitizeAndGetColumns(Map<String, Object> valueMap) {
        if (valueMap.keySet().size() > columnNameToType.keySet().size()) {
            String errMsg = "Sink record json cannot have more columns than the table.";
            LOG.error(errMsg + ". Record has " + valueMap.keySet() + ", table has " +
                      columnNameToType.keySet());
            throw new IllegalArgumentException(errMsg);
        }

        Map<String, String> lowerToOrig = new HashMap<String, String>();
        // TODO: This will error out when two columns have the same name, just capitalization
        // difference.
        for (String key : valueMap.keySet()) {
            if (lowerToOrig.get(key.toLowerCase()) != null) {
                throw new IllegalArgumentException("Column name " + key + " with different " +
                    " capitalization already present " + lowerToOrig.get(key.toLowerCase()));
            }
            lowerToOrig.put(key.toLowerCase(), key);
        }

        if (!columnNameToType.keySet().containsAll(lowerToOrig.keySet())) {
            String errMsg = "Sink record json cannot have more columns than the table.";
            LOG.error(errMsg + " Record columns are " + lowerToOrig.keySet() +
                       ", table columns are " + columnNameToType.keySet());
            throw new IllegalArgumentException(errMsg);
        }

        return lowerToOrig;
    }

    private BoundStatement bindFields(PreparedStatement statement,
                                      final SinkRecord record) {
        BoundStatement bound = statement.bind();
        Map<String, Object> valueMap = ((HashMap<String, Object>) record.value());
        Map<String, String> lowerToOrig = sanitizeAndGetColumns(valueMap);

        for (final String colName : columnNameToType.keySet()) {
            Object value = valueMap.get(lowerToOrig.get(colName));
            if (value == null) {
                LOG.info("Entry for table column '" + colName + "' not found in sink record.");
                bound = bound.setToNull(colName);
            } else {
                bound = bindByColumnType(bound, colName, columnNameToType.get(colName).getName(),
                                         value);
            }
        }

        return bound;
    }

    // Helper class to track per field in the value maps schema and value.
    class SchemaAndValue {
        private Schema schema;
        private Object value;

        public SchemaAndValue(Schema schema, Object value) {
            this.schema = schema;
            this.value = value;
        }
    }

    private BoundStatement bindFields(PreparedStatement statement,
                                      final Map<String, SchemaAndValue> allFields) {
        BoundStatement bound = statement.bind();
        for (final String colName : columnNameToType.keySet()) {
            final SchemaAndValue sav = allFields.get(colName);
            if (sav == null) {
                LOG.info("Entry for table column " + colName + " not found in sink record.");
                bound = bound.setToNull(colName);
            } else {
                bound = bindByType(bound, sav.schema, sav.value, columnNameToType.get(colName));
            }
        }
        return bound;
    }

    private List<Statement> getStatements(final Collection<SinkRecord> records) {
        List<Statement> boundStatements = new ArrayList<Statement>();
        String insert = generateInsert(getColsFromTable());
        PreparedStatement preparedStatement = cassandraSession.prepare(insert);
        LOG.info("Insert " + insert);
        for (SinkRecord record : records) {
            LOG.info("Prepare " + record + " Key/Schema=" + record.key() + "/" + record.keySchema()
                     + " Value/Schema=" + record.value() + "/" + record.valueSchema());
            if (record.value() == null) {
                // Ignoring record if it has a null value (say, a newline from user).
                continue;
            }
            if (record.valueSchema() == null) {
                // Use the table schema if user has not provided one with the record.
                boundStatements.add(bindFields(preparedStatement, record));
                continue;
            }
            if (record.valueSchema().type() != Schema.Type.MAP) {
                throw new IllegalArgumentException("Invalid schema for value " +
                    record.valueSchema().type() + " expected a map.");
            }
            final Map<String, SchemaAndValue> allFields = new HashMap<String, SchemaAndValue>();
            for (Field field : record.valueSchema().fields()) {
                allFields.put(field.name(),
                    new SchemaAndValue(field.schema(), ((Struct) record.value()).get(field)));
                LOG.info("Added field " + field.name() + " of type "+ field.schema().type());
            }
            boundStatements.add(bindFields(preparedStatement, allFields));
        }
        return boundStatements;
    }
}
