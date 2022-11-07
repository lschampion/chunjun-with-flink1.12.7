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
package com.dtstack.chunjun.connector.jdbc.sink;

import com.dtstack.chunjun.cdc.DdlRowData;
import com.dtstack.chunjun.cdc.DdlRowDataConvented;
import com.dtstack.chunjun.connector.jdbc.conf.JdbcConf;
import com.dtstack.chunjun.connector.jdbc.dialect.JdbcDialect;
import com.dtstack.chunjun.connector.jdbc.statement.String;
import com.dtstack.chunjun.connector.jdbc.util.JdbcUtil;
import com.dtstack.chunjun.element.ColumnRowData;
import com.dtstack.chunjun.enums.EWriteMode;
import com.dtstack.chunjun.enums.Semantic;
import com.dtstack.chunjun.sink.format.BaseRichOutputFormat;
import com.dtstack.chunjun.throwable.ChunJunRuntimeException;
import com.dtstack.chunjun.throwable.WriteRecordException;
import com.dtstack.chunjun.util.ExceptionUtil;
import com.dtstack.chunjun.util.GsonUtil;
import com.dtstack.chunjun.util.JsonUtil;

import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.util.FlinkRuntimeException;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * OutputFormat for writing data to relational database.
 *
 * <p>Company: www.dtstack.com
 *
 * @author huyifan.zju@163.com
 */
public class JdbcOutputFormat extends BaseRichOutputFormat {

    protected static final Logger LOG = LoggerFactory.getLogger(JdbcOutputFormat.class);

    protected static final long serialVersionUID = 1L;

    protected JdbcConf jdbcConf;
    protected JdbcDialect jdbcDialect;

    protected transient Connection dbConn;
    protected boolean autoCommit = true;

    protected transient PreparedStmtProxy stmtProxy;

    @Override
    public void initializeGlobal(int parallelism) {
        executeBatch(jdbcConf.getPreSql());
    }

    @Override
    public void finalizeGlobal(int parallelism) {
        executeBatch(jdbcConf.getPostSql());
    }

    @Override
    protected void openInternal(int taskNumber, int numTasks) {
        try {
            dbConn = getConnection();
            // 默认关闭事务自动提交，手动控制事务
            if (Semantic.EXACTLY_ONCE == semantic) {
                autoCommit = false;
                dbConn.setAutoCommit(autoCommit);
            }
            if (!EWriteMode.INSERT.name().equalsIgnoreCase(jdbcConf.getMode())) {
                List<java.lang.String> updateKey = jdbcConf.getUniqueKey();
                if (CollectionUtils.isEmpty(updateKey)) {
                    List<java.lang.String> tableIndex =
                            JdbcUtil.getTableUniqueIndex(
                                    jdbcConf.getSchema(), jdbcConf.getTable(), dbConn);
                    jdbcConf.setUniqueKey(tableIndex);
                    LOG.info("updateKey = {}", JsonUtil.toJson(tableIndex));
                }
            }

            buildStmtProxy();
            LOG.info("subTask[{}}] wait finished", taskNumber);
        } catch (SQLException sqe) {
            throw new IllegalArgumentException("open() failed.", sqe);
        } finally {
            JdbcUtil.commit(dbConn);
        }
    }

    public void buildStmtProxy() throws SQLException {
        java.lang.String tableInfo = jdbcConf.getTable();

        if ("*".equalsIgnoreCase(tableInfo)) {
            stmtProxy = new PreparedStmtProxy(dbConn, jdbcDialect, false);
        } else {
            String string =
                    String.prepareStatement(
                            dbConn, prepareTemplates(), this.columnNameList.toArray(new java.lang.String[0]));
            stmtProxy =
                    new PreparedStmtProxy(
                            string,
                            rowConverter,
                            dbConn,
                            jdbcConf,
                            jdbcDialect);
        }
    }

    @Override
    protected void writeSingleRecordInternal(RowData row) throws WriteRecordException {
        int index = 0;
        try {
            stmtProxy.writeSingleRecordInternal(row);
        } catch (Exception e) {
            JdbcUtil.rollBack(dbConn);
            processWriteException(e, index, row);
        }
    }

    @Override
    protected java.lang.String recordConvertDetailErrorMessage(int pos, Object row) {
        return "\nJdbcOutputFormat ["
                + jobName
                + "] writeRecord error: when converting field["
                + pos
                + "] in Row("
                + row
                + ")";
    }

    @Override
    protected void writeMultipleRecordsInternal() throws Exception {
        try {
            for (RowData row : rows) {
                stmtProxy.convertToExternal(row);
                stmtProxy.addBatch();
                lastRow = row;
            }
            stmtProxy.executeBatch();
            // 开启了cp，但是并没有使用2pc方式让下游数据可见
            if (Semantic.EXACTLY_ONCE == semantic) {
                rowsOfCurrentTransaction += rows.size();
            }
        } catch (Exception e) {
            LOG.warn(
                    "write Multiple Records error, start to rollback connection, row size = {}, first row = {}",
                    rows.size(),
                    rows.size() > 0 ? GsonUtil.GSON.toJson(rows.get(0)) : "null",
                    e);
            JdbcUtil.rollBack(dbConn);
            throw e;
        } finally {
            // 执行完后清空batch
            stmtProxy.clearBatch();
        }
    }

    @Override
    public synchronized void writeRecord(RowData rowData) {
        checkConnValid();
        super.writeRecord(rowData);
    }

    public void checkConnValid() {
        try {
            LOG.debug("check db connection valid..");
            if (!dbConn.isValid(10)) {
                if (Semantic.EXACTLY_ONCE == semantic) {
                    throw new FlinkRuntimeException(
                            "jdbc connection is valid!work's semantic is ExactlyOnce.To prevent data loss,we don't try to reopen the connection");
                }
                LOG.info("db connection reconnect..");
                dbConn = getConnection();
                stmtProxy.reOpen(dbConn);
            }
        } catch (Exception e) {
            throw new ChunJunRuntimeException("failed to check jdbcConnection valid", e);
        }
    }

    @Override
    public void preCommit() throws Exception {
        if (jdbcConf.getRestoreColumnIndex() > -1) {
            Object state;
            if (lastRow instanceof GenericRowData) {
                state = ((GenericRowData) lastRow).getField(jdbcConf.getRestoreColumnIndex());
            } else if (lastRow instanceof ColumnRowData) {
                state =
                        ((ColumnRowData) lastRow)
                                .getField(jdbcConf.getRestoreColumnIndex())
                                .asString();
            } else {
                LOG.warn("can't get [{}] from lastRow:{}", jdbcConf.getRestoreColumn(), lastRow);
                state = null;
            }
            formatState.setState(state);
        }

        if (rows != null && rows.size() > 0) {
            super.writeRecordInternal();
        } else {
            stmtProxy.executeBatch();
        }
    }

    @Override
    public void commit(long checkpointId) throws Exception {
        doCommit();
    }

    @Override
    public void rollback(long checkpointId) throws Exception {
        dbConn.rollback();
    }

    public void doCommit() throws SQLException {
        try {
            if (!autoCommit) {
                dbConn.commit();
            }
            snapshotWriteCounter.add(rowsOfCurrentTransaction);
            rowsOfCurrentTransaction = 0;
            stmtProxy.clearStatementCache();
        } catch (Exception e) {
            dbConn.rollback();
            throw e;
        }
    }

    /**
     * 执行pre、post SQL
     *
     * @param sqlList
     */
    protected void executeBatch(List<java.lang.String> sqlList) {
        if (CollectionUtils.isNotEmpty(sqlList)) {
            try (Connection conn = getConnection();
                    Statement stmt = conn.createStatement()) {
                for (java.lang.String sql : sqlList) {
                    // 兼容多条SQL写在同一行的情况
                    java.lang.String[] strings = sql.split(";");
                    for (java.lang.String s : strings) {
                        if (StringUtils.isNotBlank(s)) {
                            LOG.info("add sql to batch, sql = {}", s);
                            stmt.addBatch(s);
                        }
                    }
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                throw new RuntimeException(
                        "execute sql failed, sqlList = " + GsonUtil.GSON.toJson(sqlList), e);
            }
        }
    }

    protected java.lang.String prepareTemplates() {
        java.lang.String singleSql;
        if (EWriteMode.INSERT.name().equalsIgnoreCase(jdbcConf.getMode())) {
            singleSql =
                    jdbcDialect.getInsertIntoStatement(
                            jdbcConf.getSchema(),
                            jdbcConf.getTable(),
                            columnNameList.toArray(new java.lang.String[0]));
        } else if (EWriteMode.REPLACE.name().equalsIgnoreCase(jdbcConf.getMode())) {
            singleSql =
                    jdbcDialect
                            .getReplaceStatement(
                                    jdbcConf.getSchema(),
                                    jdbcConf.getTable(),
                                    columnNameList.toArray(new java.lang.String[0]))
                            .get();
        } else if (EWriteMode.UPDATE.name().equalsIgnoreCase(jdbcConf.getMode())) {
            singleSql =
                    jdbcDialect
                            .getUpsertStatement(
                                    jdbcConf.getSchema(),
                                    jdbcConf.getTable(),
                                    columnNameList.toArray(new java.lang.String[0]),
                                    jdbcConf.getUniqueKey().toArray(new java.lang.String[0]),
                                    jdbcConf.isAllReplace())
                            .get();
        } else {
            throw new IllegalArgumentException("Unknown write mode:" + jdbcConf.getMode());
        }

        LOG.info("write sql:{}", singleSql);
        return singleSql;
    }

    protected void processWriteException(Exception e, int index, RowData row)
            throws WriteRecordException {
        if (e instanceof SQLException) {
            if (e.getMessage().contains("No operations allowed")) {
                throw new RuntimeException("Connection maybe closed", e);
            }
        }

        if (index < row.getArity()) {
            java.lang.String message = recordConvertDetailErrorMessage(index, row);
            throw new WriteRecordException(message, e, index, row);
        }
        throw new WriteRecordException(e.getMessage(), e);
    }

    @Override
    public void closeInternal() {
        snapshotWriteCounter.add(rowsOfCurrentTransaction);
        try {
            if (stmtProxy != null) {
                stmtProxy.close();
            }
        } catch (SQLException e) {
            LOG.error(ExceptionUtil.getErrorMessage(e));
        }
        JdbcUtil.closeDbResources(null, null, dbConn, true);
    }

    @Override
    protected void executeDdlRwoData(DdlRowData ddlRowData) throws Exception {
        if (ddlRowData instanceof DdlRowDataConvented
                && !((DdlRowDataConvented) ddlRowData).conventSuccessful()) {
            return;
        }
        Statement statement = dbConn.createStatement();
        statement.execute(ddlRowData.getSql());
    }

    /**
     * write all data and commit transaction before execute ddl sql
     *
     * @param ddlRowData
     * @throws Exception
     */
    @Override
    protected void preExecuteDdlRwoData(DdlRowData ddlRowData) throws Exception {
        while (this.rows.size() > 0) {
            this.writeRecordInternal();
        }
        doCommit();
    }

    /**
     * 获取数据库连接，用于子类覆盖
     *
     * @return connection
     */
    protected Connection getConnection() throws SQLException {
        return JdbcUtil.getConnection(jdbcConf, jdbcDialect);
    }

    public JdbcConf getJdbcConf() {
        return jdbcConf;
    }

    public void setJdbcConf(JdbcConf jdbcConf) {
        this.jdbcConf = jdbcConf;
    }

    public void setJdbcDialect(JdbcDialect jdbcDialect) {
        this.jdbcDialect = jdbcDialect;
    }

    public void setColumnNameList(List<java.lang.String> columnNameList) {
        this.columnNameList = columnNameList;
    }

    public void setColumnTypeList(List<java.lang.String> columnTypeList) {
        this.columnTypeList = columnTypeList;
    }
}
