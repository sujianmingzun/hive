/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hcatalog.hbase;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hive.hbase.HBaseSerDe;
import org.apache.hadoop.hive.metastore.HiveMetaHook;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.apache.hadoop.hive.ql.security.authorization.HiveAuthorizationProvider;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.OutputFormat;
import org.apache.hadoop.util.StringUtils;
import org.apache.hcatalog.common.HCatConstants;
import org.apache.hcatalog.common.HCatUtil;
import org.apache.hcatalog.data.schema.HCatSchema;
import org.apache.hcatalog.hbase.snapshot.RevisionManager;
import org.apache.hcatalog.hbase.snapshot.Transaction;
import org.apache.hcatalog.hbase.snapshot.ZKBasedRevisionManager;
import org.apache.hcatalog.mapreduce.HCatOutputFormat;
import org.apache.hcatalog.mapreduce.HCatTableInfo;
import org.apache.hcatalog.mapreduce.InputJobInfo;
import org.apache.hcatalog.mapreduce.OutputJobInfo;
import org.apache.hcatalog.mapreduce.HCatStorageHandler;
import org.apache.thrift.TBase;
import org.apache.zookeeper.ZooKeeper;

import com.facebook.fb303.FacebookBase;

/**
 * This class HBaseHCatStorageHandler provides functionality to create HBase
 * tables through HCatalog. The implementation is very similar to the
 * HiveHBaseStorageHandler, with more details to suit HCatalog.
 */
//TODO remove serializable when HCATALOG-282 is fixed
public class HBaseHCatStorageHandler extends HCatStorageHandler implements HiveMetaHook, Serializable {

    public final static String DEFAULT_PREFIX = "default.";
    private final static String PROPERTY_INT_OUTPUT_LOCATION = "hcat.hbase.mapreduce.intermediateOutputLocation";

    private transient Configuration      hbaseConf;
    private transient HBaseAdmin         admin;

    @Override
    public void configureInputJobProperties(TableDesc tableDesc, Map<String, String> jobProperties) {
        // Populate jobProperties with input table name, table columns, RM snapshot,
        // hbase-default.xml and hbase-site.xml
        Map<String, String> tableJobProperties = tableDesc.getJobProperties();
        String jobString = tableJobProperties.get(HCatConstants.HCAT_KEY_JOB_INFO);
        try {
            InputJobInfo inputJobInfo = (InputJobInfo) HCatUtil.deserialize(jobString);
            HCatTableInfo tableInfo = inputJobInfo.getTableInfo();
            String qualifiedTableName = HBaseHCatStorageHandler.getFullyQualifiedName(tableInfo);
            jobProperties.put(TableInputFormat.INPUT_TABLE, qualifiedTableName);

            Configuration jobConf = getConf();
            String outputSchema = jobConf.get(HCatConstants.HCAT_KEY_OUTPUT_SCHEMA);
            jobProperties.put(TableInputFormat.SCAN_COLUMNS, getScanColumns(tableInfo, outputSchema));

            String serSnapshot = (String) inputJobInfo.getProperties().get(
                    HBaseConstants.PROPERTY_TABLE_SNAPSHOT_KEY);
            if (serSnapshot == null) {
                Configuration conf = addHbaseResources(jobConf);
                HCatTableSnapshot snapshot = HBaseRevisionManagerUtil.createSnapshot(conf,
                        qualifiedTableName, tableInfo);
                jobProperties.put(HBaseConstants.PROPERTY_TABLE_SNAPSHOT_KEY,
                        HCatUtil.serialize(snapshot));
            }

            addHbaseResources(jobConf, jobProperties);

        } catch (IOException e) {
            throw new IllegalStateException("Error while configuring job properties", e);
        }
    }

    @Override
    public void configureOutputJobProperties(TableDesc tableDesc, Map<String, String> jobProperties) {
        // Populate jobProperties with output table name, hbase-default.xml, hbase-site.xml, OutputJobInfo
        // Populate RM transaction in OutputJobInfo
        // In case of bulk mode, populate intermediate output location
        Map<String, String> tableJobProperties = tableDesc.getJobProperties();
        String jobString = tableJobProperties.get(HCatConstants.HCAT_KEY_OUTPUT_INFO);
        try {
            OutputJobInfo outputJobInfo = (OutputJobInfo) HCatUtil.deserialize(jobString);
            HCatTableInfo tableInfo = outputJobInfo.getTableInfo();
            String qualifiedTableName = HBaseHCatStorageHandler.getFullyQualifiedName(tableInfo);
            jobProperties.put(HBaseConstants.PROPERTY_OUTPUT_TABLE_NAME_KEY, qualifiedTableName);

            Configuration jobConf = getConf();
            String txnString = outputJobInfo.getProperties().getProperty(
                    HBaseConstants.PROPERTY_WRITE_TXN_KEY);
            if (txnString == null) {
                Configuration conf = addHbaseResources(jobConf);
                Transaction txn = HBaseRevisionManagerUtil.beginWriteTransaction(qualifiedTableName, tableInfo, conf);
                outputJobInfo.getProperties().setProperty(HBaseConstants.PROPERTY_WRITE_TXN_KEY,
                        HCatUtil.serialize(txn));

                if (isBulkMode(outputJobInfo) && !(outputJobInfo.getProperties()
                                .containsKey(PROPERTY_INT_OUTPUT_LOCATION))) {
                    String tableLocation = tableInfo.getTableLocation();
                    String location = new Path(tableLocation, "REVISION_" + txn.getRevisionNumber())
                            .toString();
                    outputJobInfo.getProperties().setProperty(PROPERTY_INT_OUTPUT_LOCATION,
                            location);
                    // We are writing out an intermediate sequenceFile hence
                    // location is not passed in OutputJobInfo.getLocation()
                    // TODO replace this with a mapreduce constant when available
                    jobProperties.put("mapred.output.dir", location);
                }
            }

            jobProperties
                    .put(HCatConstants.HCAT_KEY_OUTPUT_INFO, HCatUtil.serialize(outputJobInfo));
            addHbaseResources(jobConf, jobProperties);
            addOutputDependencyJars(jobConf);
            jobProperties.put("tmpjars", jobConf.get("tmpjars"));

        } catch (IOException e) {
            throw new IllegalStateException("Error while configuring job properties", e);
        }
    }

    /*
    * @return instance of HiveAuthorizationProvider
    *
    * @throws HiveException
    *
    * @see org.apache.hcatalog.storagehandler.HCatStorageHandler#
    * getAuthorizationProvider()
    */
    @Override
    public HiveAuthorizationProvider getAuthorizationProvider()
            throws HiveException {

        HBaseAuthorizationProvider hbaseAuth = new HBaseAuthorizationProvider();
        hbaseAuth.init(getConf());
        return hbaseAuth;
    }

    /*
     * @param table
     *
     * @throws MetaException
     *
     * @see org.apache.hcatalog.storagehandler.HCatStorageHandler
     * #commitCreateTable(org.apache.hadoop.hive.metastore.api.Table)
     */
    @Override
    public void commitCreateTable(Table table) throws MetaException {
    }

    /*
     * @param instance of table
     *
     * @param deleteData
     *
     * @throws MetaException
     *
     * @see org.apache.hcatalog.storagehandler.HCatStorageHandler
     * #commitDropTable(org.apache.hadoop.hive.metastore.api.Table, boolean)
     */
    @Override
    public void commitDropTable(Table tbl, boolean deleteData)
            throws MetaException {
        checkDeleteTable(tbl);

    }

    /*
     * @param instance of table
     *
     * @throws MetaException
     *
     * @see org.apache.hcatalog.storagehandler.HCatStorageHandler
     * #preCreateTable(org.apache.hadoop.hive.metastore.api.Table)
     */
    @Override
    public void preCreateTable(Table tbl) throws MetaException {
        boolean isExternal = MetaStoreUtils.isExternalTable(tbl);

        hbaseConf = getConf();

        if (tbl.getSd().getLocation() != null) {
            throw new MetaException("LOCATION may not be specified for HBase.");
        }

        try {
            String tableName = getHBaseTableName(tbl);
            String hbaseColumnsMapping = tbl.getParameters().get(
                    HBaseSerDe.HBASE_COLUMNS_MAPPING);

            tbl.putToParameters(HBaseConstants.PROPERTY_COLUMN_MAPPING_KEY,
                    hbaseColumnsMapping);

            if (hbaseColumnsMapping == null) {
                throw new MetaException(
                        "No hbase.columns.mapping defined in table"
                                + " properties.");
            }

            List<String> hbaseColumnFamilies = new ArrayList<String>();
            List<String> hbaseColumnQualifiers = new ArrayList<String>();
            List<byte[]> hbaseColumnFamiliesBytes = new ArrayList<byte[]>();
            int iKey = HBaseUtil.parseColumnMapping(hbaseColumnsMapping,
                    hbaseColumnFamilies, hbaseColumnFamiliesBytes,
                    hbaseColumnQualifiers, null);

            HTableDescriptor tableDesc;
            Set<String> uniqueColumnFamilies = new HashSet<String>();
            if (!getHBaseAdmin().tableExists(tableName)) {
                // if it is not an external table then create one
                if (!isExternal) {
                    // Create the column descriptors
                    tableDesc = new HTableDescriptor(tableName);
                    uniqueColumnFamilies.addAll(hbaseColumnFamilies);
                    uniqueColumnFamilies.remove(hbaseColumnFamilies.get(iKey));

                    for (String columnFamily : uniqueColumnFamilies) {
                        HColumnDescriptor familyDesc = new HColumnDescriptor(Bytes
                                .toBytes(columnFamily));
                        familyDesc.setMaxVersions(Integer.MAX_VALUE);
                        tableDesc.addFamily(familyDesc);
                    }

                    getHBaseAdmin().createTable(tableDesc);
                } else {
                    // an external table
                    throw new MetaException("HBase table " + tableName
                            + " doesn't exist while the table is "
                            + "declared as an external table.");
                }

            } else {
                if (!isExternal) {
                    throw new MetaException("Table " + tableName
                            + " already exists within HBase."
                            + " Use CREATE EXTERNAL TABLE instead to"
                            + " register it in HCatalog.");
                }
                // make sure the schema mapping is right
                tableDesc = getHBaseAdmin().getTableDescriptor(
                        Bytes.toBytes(tableName));

                for (int i = 0; i < hbaseColumnFamilies.size(); i++) {
                    if (i == iKey) {
                        continue;
                    }

                    if (!tableDesc.hasFamily(hbaseColumnFamiliesBytes.get(i))) {
                        throw new MetaException("Column Family "
                                + hbaseColumnFamilies.get(i)
                                + " is not defined in hbase table " + tableName);
                    }
                }
            }

            // ensure the table is online
            new HTable(hbaseConf, tableDesc.getName());

            //Set up znodes in revision manager.
            RevisionManager rm = HBaseRevisionManagerUtil.getOpenedRevisionManager(hbaseConf);
            if (rm instanceof ZKBasedRevisionManager) {
                ZKBasedRevisionManager zkRM = (ZKBasedRevisionManager) rm;
                zkRM.setUpZNodes(tableName, new ArrayList<String>(
                        uniqueColumnFamilies));
            }

        } catch (MasterNotRunningException mnre) {
            throw new MetaException(StringUtils.stringifyException(mnre));
        } catch (IOException ie) {
            throw new MetaException(StringUtils.stringifyException(ie));
        } catch (IllegalArgumentException iae) {
            throw new MetaException(StringUtils.stringifyException(iae));
        }

    }

    /*
     * @param table
     *
     * @throws MetaException
     *
     * @see org.apache.hcatalog.storagehandler.HCatStorageHandler
     * #preDropTable(org.apache.hadoop.hive.metastore.api.Table)
     */
    @Override
    public void preDropTable(Table table) throws MetaException {
    }

    /*
     * @param table
     *
     * @throws MetaException
     *
     * @see org.apache.hcatalog.storagehandler.HCatStorageHandler
     * #rollbackCreateTable(org.apache.hadoop.hive.metastore.api.Table)
     */
    @Override
    public void rollbackCreateTable(Table table) throws MetaException {
        checkDeleteTable(table);
    }

    /*
     * @param table
     *
     * @throws MetaException
     *
     * @see org.apache.hcatalog.storagehandler.HCatStorageHandler
     * #rollbackDropTable(org.apache.hadoop.hive.metastore.api.Table)
     */
    @Override
    public void rollbackDropTable(Table table) throws MetaException {
    }

    /*
     * @return instance of HiveMetaHook
     *
     * @see org.apache.hcatalog.storagehandler.HCatStorageHandler#getMetaHook()
     */
    @Override
    public HiveMetaHook getMetaHook() {
        return this;
    }

    private HBaseAdmin getHBaseAdmin() throws MetaException {
        try {
            if (admin == null) {
                admin = new HBaseAdmin(this.getConf());
            }
            return admin;
        } catch (MasterNotRunningException mnre) {
            throw new MetaException(StringUtils.stringifyException(mnre));
        } catch (ZooKeeperConnectionException zkce) {
            throw new MetaException(StringUtils.stringifyException(zkce));
        }
    }

    private String getHBaseTableName(Table tbl) {
        String tableName = tbl.getParameters().get(HBaseSerDe.HBASE_TABLE_NAME);
        if (tableName == null) {
            tableName = tbl.getSd().getSerdeInfo().getParameters()
                    .get(HBaseSerDe.HBASE_TABLE_NAME);
        }
        if (tableName == null) {
            if (tbl.getDbName().equals(MetaStoreUtils.DEFAULT_DATABASE_NAME)) {
                tableName = tbl.getTableName();
            } else {
                tableName = tbl.getDbName() + "." + tbl.getTableName();
            }
        }
        return tableName;
    }

    @Override
    public Class<? extends InputFormat> getInputFormatClass() {
        return HBaseInputFormat.class;
    }

    @Override
    public Class<? extends OutputFormat> getOutputFormatClass() {
        return HBaseBaseOutputFormat.class;
    }

    /*
    * @return subclass of SerDe
    *
    * @throws UnsupportedOperationException
    *
    * @see
    * org.apache.hcatalog.storagehandler.HCatStorageHandler#getSerDeClass()
    */
    @Override
    public Class<? extends SerDe> getSerDeClass()
            throws UnsupportedOperationException {
        return HBaseSerDe.class;
    }

    @Override
    public Configuration getConf() {

        if (hbaseConf == null) {
            hbaseConf = HBaseConfiguration.create();
        }
        return hbaseConf;
    }

    @Override
    public void setConf(Configuration conf) {
        hbaseConf = HBaseConfiguration.create(conf);
    }

    private void checkDeleteTable(Table table) throws MetaException {
        boolean isExternal = MetaStoreUtils.isExternalTable(table);
        String tableName = getHBaseTableName(table);
        RevisionManager rm = null;
        try {
            if (!isExternal && getHBaseAdmin().tableExists(tableName)) {
                // we have created an HBase table, so we delete it to roll back;
                if (getHBaseAdmin().isTableEnabled(tableName)) {
                    getHBaseAdmin().disableTable(tableName);
                }
                getHBaseAdmin().deleteTable(tableName);

              //Set up znodes in revision manager.
                rm = HBaseRevisionManagerUtil.getOpenedRevisionManager(hbaseConf);
                if (rm instanceof ZKBasedRevisionManager) {
                    ZKBasedRevisionManager zkRM = (ZKBasedRevisionManager) rm;
                    zkRM.deleteZNodes(tableName);
                }
            }
        } catch (IOException ie) {
            throw new MetaException(StringUtils.stringifyException(ie));
        } finally {
            HBaseRevisionManagerUtil.closeRevisionManagerQuietly(rm);
        }
    }

    static String getFullyQualifiedName(HCatTableInfo tableInfo){
        String qualifiedName;
        String databaseName = tableInfo.getDatabaseName();
        String tableName = tableInfo.getTableName();

        if ((databaseName == null) || (databaseName.equals(MetaStoreUtils.DEFAULT_DATABASE_NAME))) {
            qualifiedName = tableName;
        } else {
            qualifiedName = databaseName + "." + tableName;
        }

        return qualifiedName;
    }

    /**
     * Helper method for users to add the required depedency jars to distributed cache.
     * @param conf
     * @throws IOException
     */
    private void addOutputDependencyJars(Configuration conf) throws IOException {
        TableMapReduceUtil.addDependencyJars(conf,
                //hadoop-core
                Writable.class,
                //ZK
                ZooKeeper.class,
                //HBase
                HTable.class,
                //Hive
                HiveException.class,
                //HCatalog jar
                HCatOutputFormat.class,
                //hive hbase storage handler jar
                HBaseSerDe.class,
                //hive jar
                Table.class,
                //libthrift jar
                TBase.class,
                //hbase jar
                Bytes.class,
                //thrift-fb303 .jar
                FacebookBase.class);
    }

    /**
     * Utility method to get a new Configuration with hbase-default.xml and hbase-site.xml added
     * @param jobConf existing configuration
     * @return a new Configuration with hbase-default.xml and hbase-site.xml added
     */
    private Configuration addHbaseResources(Configuration jobConf) {
        Configuration conf = new Configuration(jobConf);
        HBaseConfiguration.addHbaseResources(conf);
        return conf;
    }

    /**
     * Utility method to add hbase-default.xml and hbase-site.xml properties to a new map
     * if they are not already present in the jobConf.
     * @param jobConf Job configuration
     * @param newJobProperties  Map to which new properties should be added
     */
    private void addHbaseResources(Configuration jobConf,
            Map<String, String> newJobProperties) {
        Configuration conf = new Configuration(false);
        HBaseConfiguration.addHbaseResources(conf);
        for (Entry<String, String> entry : conf) {
            if (jobConf.get(entry.getKey()) == null)
                newJobProperties.put(entry.getKey(), entry.getValue());
        }
    }

    public static boolean isBulkMode(OutputJobInfo outputJobInfo) {
        //Default is false
        String bulkMode = outputJobInfo.getTableInfo().getStorerInfo().getProperties()
                .getProperty(HBaseConstants.PROPERTY_OSD_BULK_MODE_KEY,
                        "false");
        return "true".equals(bulkMode);
    }

    private String getScanColumns(HCatTableInfo tableInfo, String outputColSchema) throws IOException {
        StringBuilder builder = new StringBuilder();
        String hbaseColumnMapping = tableInfo.getStorerInfo().getProperties()
                .getProperty(HBaseConstants.PROPERTY_COLUMN_MAPPING_KEY);
        if (outputColSchema == null) {
            String[] splits = hbaseColumnMapping.split("[,]");
            for (int i = 0; i < splits.length; i++) {
                if (!splits[i].equals(HBaseSerDe.HBASE_KEY_COL))
                    builder.append(splits[i]).append(" ");
            }
        } else {
            HCatSchema outputSchema = (HCatSchema) HCatUtil.deserialize(outputColSchema);
            HCatSchema tableSchema = tableInfo.getDataColumns();
            List<String> outputFieldNames = outputSchema.getFieldNames();
            List<Integer> outputColumnMapping = new ArrayList<Integer>();
            for(String fieldName: outputFieldNames){
                int position = tableSchema.getPosition(fieldName);
                outputColumnMapping.add(position);
            }
            List<String> columnFamilies = new ArrayList<String>();
            List<String> columnQualifiers = new ArrayList<String>();
            HBaseUtil.parseColumnMapping(hbaseColumnMapping, columnFamilies, null,
                    columnQualifiers, null);
            for (int i = 0; i < outputColumnMapping.size(); i++) {
                int cfIndex = outputColumnMapping.get(i);
                String cf = columnFamilies.get(cfIndex);
                // We skip the key column.
                if (cf.equals(HBaseSerDe.HBASE_KEY_COL) == false) {
                    String qualifier = columnQualifiers.get(i);
                    builder.append(cf);
                    builder.append(":");
                    if (qualifier != null) {
                        builder.append(qualifier);
                    }
                    builder.append(" ");
                }
            }
        }
        //Remove the extra space delimiter
        builder.deleteCharAt(builder.length() - 1);
        return builder.toString();
    }

}
