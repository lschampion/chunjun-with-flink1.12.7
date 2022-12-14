/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.chunjun.connector.oraclelogminer.listener;

import com.dtstack.chunjun.connector.oraclelogminer.conf.LogMinerConf;
import com.dtstack.chunjun.connector.oraclelogminer.entity.QueueData;
import com.dtstack.chunjun.connector.oraclelogminer.util.SqlUtil;
import com.dtstack.chunjun.util.ExceptionUtil;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class LogMinerHelper {

    public static Logger LOG = LoggerFactory.getLogger(LogMinerHelper.class);

    private final TransactionManager transactionManager;
    private final ExecutorService connectionExecutor;
    /** ???????????????connection */
    private final LinkedList<LogMinerConnection> activeConnectionList;

    private final LogMinerConf config;
    private final String logMinerSelectSql;
    private final LogMinerListener listener;
    private final BigInteger step = new BigInteger("3000");
    private BigInteger startScn;
    private BigInteger endScn;
    // ???????????????online????????????
    private Boolean loadRedo = false;

    // ???????????????????????????
    private BigInteger currentSinkPosition;
    /** ?????????????????????connection?????? * */
    private int currentIndex;
    /** ?????????????????????connection * */
    private LogMinerConnection currentConnection;
    /** ?????????????????????connection???endScn * */
    private BigInteger currentReadEndScn;

    public LogMinerHelper(
            LogMinerListener listener, LogMinerConf logMinerConfig, BigInteger startScn) {
        this.listener = listener;
        this.transactionManager =
                new TransactionManager(
                        logMinerConfig.getTransactionCacheNumSize(),
                        logMinerConfig.getTransactionExpireTime());
        this.startScn = startScn;
        this.endScn = startScn;
        this.activeConnectionList = new LinkedList<>();
        this.config = logMinerConfig;
        this.currentIndex = 0;

        ThreadFactory namedThreadFactory =
                new ThreadFactoryBuilder()
                        .setNameFormat("LogMinerConnection-pool-%d")
                        .setUncaughtExceptionHandler(
                                (t, e) -> LOG.warn("LogMinerConnection run failed", e))
                        .build();

        connectionExecutor =
                new ThreadPoolExecutor(
                        logMinerConfig.getParallelism(),
                        logMinerConfig.getParallelism() + 2,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(1024),
                        namedThreadFactory,
                        new ThreadPoolExecutor.AbortPolicy());

        for (int i = 0; i < logMinerConfig.getParallelism(); i++) {
            LogMinerConnection logMinerConnection =
                    new LogMinerConnection(logMinerConfig, transactionManager);
            activeConnectionList.add(logMinerConnection);
            logMinerConnection.connect();
            activeConnectionList.get(0).checkPrivileges();
        }
        this.logMinerSelectSql =
                SqlUtil.buildSelectSql(
                        config.getCat(),
                        config.getListenerTables(),
                        activeConnectionList.get(0).oracleInfo.isCdbMode());
        currentConnection = null;
        currentReadEndScn = null;
    }

    /** ??????????????? ?????????????????????????????????????????????????????? */
    public void init() {
        try {
            preLoad();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public BigInteger getStartScn(BigInteger scn) {
        return activeConnectionList.get(0).getStartScn(scn);
    }

    /** ???????????????????????? ????????? ?????? currentConnection ?????????????????????connection???????????? */
    private void preLoad() throws SQLException {

        BigInteger currentMaxScn = null;
        // ?????????????????????????????????connection
        List<LogMinerConnection> needLoadList =
                activeConnectionList.stream()
                        .filter(
                                i ->
                                        i.getState().equals(LogMinerConnection.STATE.READEND)
                                                || i.getState()
                                                        .equals(
                                                                LogMinerConnection.STATE
                                                                        .INITIALIZE))
                        .collect(Collectors.toList());
        for (LogMinerConnection logMinerConnection : needLoadList) {
            logMinerConnection.checkAndResetConnection();
            if (Objects.isNull(currentMaxScn)) {
                currentMaxScn = logMinerConnection.getCurrentScn();
            }
            // currentReadEndScn???????????????????????? ??????????????????????????????????????????????????????????????????
            // ?????????????????????????????????????????????SCN????????????3000???????????????connection????????????
            if (Objects.isNull(currentConnection)
                    || currentMaxScn.subtract(this.endScn).compareTo(step) > 0) {

                // ?????????????????????????????????????????????endScn?????????????????????????????????????????????
                BigInteger currentStartScn = Objects.nonNull(this.endScn) ? this.endScn : startScn;

                // ???????????????redo?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                if (loadRedo) {
                    // ?????????1  ??????logminer??????????????????????????????????????????1  ???????????????????????????????????????
                    currentStartScn = currentSinkPosition.add(BigInteger.ONE);
                }

                Pair<BigInteger, Boolean> endScn =
                        logMinerConnection.getEndScn(currentStartScn, new ArrayList<>(32));
                logMinerConnection.startOrUpdateLogMiner(currentStartScn, endScn.getLeft());
                // ??????v$logmnr_contents ????????????????????????
                loadData(logMinerConnection, logMinerSelectSql);
                this.endScn = endScn.getLeft();
                this.loadRedo = endScn.getRight();
                if (Objects.isNull(currentConnection)) {
                    updateCurrentConnection(logMinerConnection);
                }
                // ?????????????????????redoLog??????????????????????????????
                if (endScn.getRight()) {
                    break;
                }
            } else {
                break;
            }
        }

        // ????????????currentConnection?????? ?????????????????????connection??????????????????connection??????????????????
        if (Objects.isNull(currentConnection) && CollectionUtils.isEmpty(needLoadList)) {
            LOG.info(
                    "reset activeConnectionList[0] a new connection,activeConnectionList is {}",
                    activeConnectionList);
            activeConnectionList.set(0, new LogMinerConnection(config, transactionManager));
            preLoad();
        }
        LOG.info(
                "current load scnRange startSCN:{}, endSCN:{},currentReadEndScn:{},activeConnectionList:{}",
                startScn,
                endScn,
                currentReadEndScn,
                activeConnectionList);
    }

    /** ???????????????????????????????????? */
    public void loadData(LogMinerConnection logMinerConnection, String sql) {
        connectionExecutor.submit(
                () -> {
                    try {
                        logMinerConnection.queryData(sql);
                    } catch (Exception e) {
                        // ignore
                    }
                });
    }

    /** ??????connection?????????????????? */
    public void restart(Exception e) {
        LogMinerConnection logMinerConnection = activeConnectionList.get(currentIndex);
        restart(logMinerConnection, e != null ? e : logMinerConnection.getE());
    }

    /** connection???????????? */
    public void restart(LogMinerConnection connection, Exception e) {
        LOG.info(
                "restart connection, startScn: {},endScn: {}",
                connection.startScn,
                connection.endScn);
        try {
            connection.disConnect();
            if (listener.getCurrentPosition().compareTo(connection.endScn) >= 0) {
                throw new RuntimeException(
                        "the SCN currently consumed ["
                                + listener.getCurrentPosition()
                                + "] is larger than the endScn of the restarted connection ["
                                + connection.endScn
                                + "]");
            }
            boolean isRedoChangeError =
                    e != null && ExceptionUtil.getErrorMessage(e).contains("ORA-00310");
            if (isRedoChangeError) {
                BigInteger startScn =
                        connection.startScn.compareTo(listener.getCurrentPosition()) > 0
                                ? connection.startScn
                                : listener.getCurrentPosition().add(BigInteger.ONE);
                // ?????????ora-310?????????????????????redolog????????????????????????????????????????????????????????????????????? ??????????????????????????????
                Pair<BigInteger, Boolean> endScn =
                        connection.getEndScn(startScn, new ArrayList<>(32), false);
                // ?????????redoLog???????????????????????????????????????????????????????????????endScn
                if (endScn.getLeft() == null) {
                    for (int i = 0; i < 10; i++) {
                        LOG.info("restart connection but not find archive log, waiting.....");
                        Thread.sleep(5000);
                        endScn = connection.getEndScn(startScn, new ArrayList<>(32), false);
                        if (endScn.getLeft() != null) {
                            break;
                        }
                    }
                    // ??????????????????????????????50s???????????????redolog?????? ?????????????????????redoLog
                    if (endScn.getLeft() == null) {
                        endScn = connection.getEndScn(startScn, new ArrayList<>(32));
                    }
                }

                connection.startOrUpdateLogMiner(startScn, endScn.getLeft());
            } else {
                // ?????????????????????????????? ??????????????? listener.getCurrentPosition() ??? 1
                connection.startOrUpdateLogMiner(
                        connection.startScn.compareTo(listener.getCurrentPosition()) > 0
                                ? connection.startScn
                                : listener.getCurrentPosition().add(BigInteger.ONE),
                        connection.endScn);
            }

            loadData(connection, logMinerSelectSql);
            if (isRedoChangeError) {
                // ??????connection??????????????????????????? ??????????????????
                updateCurrentConnection(connection);
                // ????????????????????????connection????????????INITIALIZE?????????????????????
                for (LogMinerConnection logMinerConnection :
                        activeConnectionList.stream()
                                .filter(i -> i != connection)
                                .collect(Collectors.toList())) {
                    logMinerConnection.disConnect();
                }
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    public boolean hasNext() throws UnsupportedEncodingException, SQLException, DecoderException {

        if (Objects.isNull(currentConnection)) {
            LogMinerConnection connection = chooseAndPreLoadConnection();
            if (Objects.isNull(connection)) {
                return false;
            }
        }

        LogMinerConnection.STATE state = currentConnection.getState();

        // ????????????connection?????????????????? ?????????false ??????????????????
        if (currentConnection.isLoading()) {
            return false;
        } else if (state.equals(LogMinerConnection.STATE.FAILED)) {
            listener.sendException(currentConnection.getE(), null);
            restart(currentConnection.getE());
            return false;
        }

        boolean hasNext = currentConnection.hasNext();

        // ??????connection???????????? ?????????null
        if (!hasNext) {
            currentConnection = null;
        }
        return hasNext;
    }

    /** ?????????????????????connection?????????????????????????????? */
    private LogMinerConnection chooseAndPreLoadConnection() throws SQLException {
        LogMinerConnection connection = chooseConnection();

        if (Objects.isNull(connection)) {
            this.endScn = currentReadEndScn;
            preLoad();
            if (Objects.isNull(connection = chooseConnection())) {
                throw new RuntimeException(
                        "has not choose connection,currentReadEndScn:["
                                + currentReadEndScn
                                + "],and connections is \n"
                                + activeConnectionList);
            }
            updateCurrentConnection(connection);
        } else {
            updateCurrentConnection(connection);
            preLoad();
        }
        return connection;
    }

    /** ??????????????????connection?????? */
    public void updateCurrentConnection(LogMinerConnection connection) {
        currentIndex = activeConnectionList.indexOf(connection);
        currentConnection = activeConnectionList.get(currentIndex);
        this.startScn = currentConnection.startScn;
        this.currentReadEndScn = currentConnection.endScn;
        LOG.info(
                "after update currentConnection,currentIndex is {}, startScnOfCurrentConnection:{}, endScnOfCurrentConnection:{}, this.startScn:{},this.endScn:{},this.currentReadEndScn:{}",
                currentIndex,
                currentConnection.startScn,
                currentConnection.endScn,
                this.startScn,
                this.endScn,
                this.currentReadEndScn);
    }

    public void stop() {
        if (null != connectionExecutor && !connectionExecutor.isShutdown()) {
            connectionExecutor.shutdown();
        }
        if (CollectionUtils.isNotEmpty(activeConnectionList)) {
            activeConnectionList.forEach(LogMinerConnection::disConnect);
        }
    }

    /** ??????connection???startScn?????????currentReadEndScn?????????connection */
    public LogMinerConnection chooseConnection() {
        if (Objects.nonNull(currentConnection)) {
            return currentConnection;
        }
        LogMinerConnection choosedConnection = null;
        List<LogMinerConnection> candidateList =
                activeConnectionList.stream()
                        .filter(i -> Objects.nonNull(i.startScn) && Objects.nonNull(i.endScn))
                        .collect(Collectors.toList());
        for (LogMinerConnection logMinerConnection : candidateList) {
            if (logMinerConnection.startScn.compareTo(currentReadEndScn) == 0
                    && !logMinerConnection.getState().equals(LogMinerConnection.STATE.INITIALIZE)) {
                choosedConnection = logMinerConnection;
                if (choosedConnection.getState().equals(LogMinerConnection.STATE.FAILED)) {
                    listener.sendException(choosedConnection.getE(), null);
                    restart(choosedConnection.getE());
                }
                break;
            }
        }
        return choosedConnection;
    }

    public QueueData getQueueData() {
        QueueData next = activeConnectionList.get(currentIndex).next();
        if (BigInteger.ZERO.compareTo(next.getScn()) != 0) {
            this.currentSinkPosition = next.getScn();
        }
        return next;
    }

    public void setStartScn(BigInteger startScn) {
        this.startScn = startScn;
        this.currentSinkPosition = this.startScn;
    }
}
