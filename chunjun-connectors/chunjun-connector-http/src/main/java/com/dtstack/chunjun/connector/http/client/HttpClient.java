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
package com.dtstack.chunjun.connector.http.client;

import com.dtstack.chunjun.connector.http.common.ConstantValue;
import com.dtstack.chunjun.connector.http.common.HttpRestConfig;
import com.dtstack.chunjun.connector.http.common.HttpUtil;
import com.dtstack.chunjun.connector.http.common.MetaParam;
import com.dtstack.chunjun.util.ExceptionUtil;
import com.dtstack.chunjun.util.GsonUtil;

import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * httpClient
 *
 * @author by shifang@dtstack.com @Date 2020/9/25
 */
public class HttpClient {
    private static final Logger LOG = LoggerFactory.getLogger(HttpClient.class);

    private ScheduledExecutorService scheduledExecutorService;
    private transient CloseableHttpClient httpClient;
    private BlockingQueue<ResponseValue> queue;
    private static final String THREAD_NAME = "restApiReader-thread";

    protected HttpRestConfig restConfig;

    private boolean first;

    private final RestHandler restHandler;

    private int requestRetryTime;

    /** origin body */
    private final List<MetaParam> originalBodyList;

    /** origin param */
    private final List<MetaParam> originalParamList;

    /** origin header */
    private final List<MetaParam> originalHeaderList;

    private final List<MetaParam> allMetaParam = new ArrayList<>(32);

    /** current request param */
    private HttpRequestParam currentParam;

    /** last request param */
    private HttpRequestParam prevParam;

    /** last response body */
    private String prevResponse;

    private boolean reachEnd;

    private boolean running;

    public HttpClient(
            HttpRestConfig httpRestConfig,
            List<MetaParam> originalBodyList,
            List<MetaParam> originalParamList,
            List<MetaParam> originalHeaderList) {
        this.restConfig = httpRestConfig;
        this.originalHeaderList = originalHeaderList;
        this.originalBodyList = originalBodyList;
        this.originalParamList = originalParamList;
        allMetaParam.addAll(originalHeaderList);
        allMetaParam.addAll(originalBodyList);
        allMetaParam.addAll(originalParamList);

        this.queue = new LinkedBlockingQueue<>();
        this.scheduledExecutorService =
                new ScheduledThreadPoolExecutor(1, r -> new Thread(r, THREAD_NAME));
        this.httpClient = HttpUtil.getHttpsClient();
        this.restHandler = new DefaultRestHandler();

        this.prevResponse = "";
        this.first = true;
        this.currentParam = new HttpRequestParam();
        this.reachEnd = false;
        this.requestRetryTime = 2;
    }

    public void start() {
        scheduledExecutorService.scheduleWithFixedDelay(
                this::execute, 0, restConfig.getIntervalTime(), TimeUnit.MILLISECONDS);
        running = true;
    }

    public void initPosition(HttpRequestParam requestParam, String response) {
        this.prevParam = requestParam;
        this.prevResponse = response;
        this.first = false;
    }

    public void execute() {

        if (!running) {
            return;
        }

        Thread.currentThread()
                .setUncaughtExceptionHandler(
                        (t, e) ->
                                LOG.warn(
                                        "HttpClient run failed, Throwable = {}, HttpClient->{}",
                                        ExceptionUtil.getErrorMessage(e),
                                        this.toString()));

        // ????????????
        try {
            // ????????????????????????json ??????decode???json ??????????????? ????????????????????? ??????decode???text ???????????????????????????
            // ?????????????????????${response.}???????????????????????????????????????????????????responseValue??????decode?????????json??????
            Map<String, Object> responseValue = null;
            try {
                responseValue = GsonUtil.GSON.fromJson(prevResponse, GsonUtil.gsonMapTypeToken);
            } catch (Exception e) {
                if (restConfig.isJsonDecode()) {
                    throw e;
                }
            }
            currentParam =
                    restHandler.buildRequestParam(
                            originalParamList,
                            originalBodyList,
                            originalHeaderList,
                            prevParam,
                            responseValue,
                            restConfig,
                            first);
        } catch (Exception e) {
            // ???????????????????????? ????????????,??????????????? ???????????????????????????????????????????????????
            ResponseValue value =
                    new ResponseValue(-1, null, ExceptionUtil.getErrorMessage(e), null, null);
            processData(value);
            running = false;
            return;
        }

        LOG.debug("currentParam is {}", currentParam);
        doExecute(ConstantValue.REQUEST_RETRY_TIME);
        first = false;
        requestRetryTime = 3;
    }

    public void doExecute(int retryTime) {

        // ?????????????????? ?????????????????????
        if (retryTime < 0) {
            processData(
                    new ResponseValue(
                            -1,
                            null,
                            "the maximum number of retries has been reached???task closed??? httpClient value is "
                                    + this.toString(),
                            null,
                            null));
            running = false;
            return;
        }

        // ????????????
        String responseValue = null;
        try {

            HttpUriRequest request =
                    HttpUtil.getRequest(
                            restConfig.getRequestMode(),
                            currentParam.getBody(),
                            currentParam.getParam(),
                            currentParam.getHeader(),
                            restConfig.getUrl());
            CloseableHttpResponse httpResponse = httpClient.execute(request);
            if (httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                LOG.warn(
                        "httpStatus is {} and is not 200 ,try retry",
                        httpResponse.getStatusLine().getStatusCode());
                doExecute(--requestRetryTime);
                return;
            }

            responseValue = EntityUtils.toString(httpResponse.getEntity());
        } catch (Throwable e) {
            // ???????????????????????????????????? ?????????????????????????????????????????????????????????????????????
            LOG.warn(
                    "httpClient value is {}, error info is {}",
                    this.toString(),
                    ExceptionUtil.getErrorMessage(e));
            doExecute(--requestRetryTime);
            return;
        }

        // ????????????
        try {
            // ?????????????????????????????????????????????????????? ????????????????????????????????????????????????????????????????????????????????????
            Strategy strategy =
                    restHandler.chooseStrategy(
                            restConfig.getStrategy(),
                            restConfig.isJsonDecode()
                                    ? GsonUtil.GSON.fromJson(
                                            responseValue, GsonUtil.gsonMapTypeToken)
                                    : null,
                            restConfig,
                            HttpRequestParam.copy(currentParam),
                            allMetaParam);

            if (strategy != null) {
                // ?????????????????????
                switch (strategy.getHandle()) {
                    case ConstantValue.STRATEGY_RETRY:
                        doExecute(--retryTime);
                        return;
                    case ConstantValue.STRATEGY_STOP:
                        reachEnd = true;
                        running = false;
                        break;
                    default:
                        break;
                }
            }

            ResponseValue value =
                    restHandler.buildResponseValue(
                            restConfig.getDecode(),
                            responseValue,
                            restConfig.getFields(),
                            HttpRequestParam.copy(currentParam));
            if (reachEnd) {
                // ???????????????  ????????????format ?????????
                if (value.isNormal()) {
                    value.setStatus(0);
                    // ?????????????????????????????????
                    value.setErrorMsg(strategy.toString());
                }
            }
            processData(value);

            prevParam = currentParam;
            prevResponse = responseValue;
        } catch (Throwable e) {
            // ????????????????????? ??????????????????
            LOG.warn(
                    "httpClient value is {},responseValue is {}, error info is {}",
                    this.toString(),
                    responseValue,
                    ExceptionUtil.getErrorMessage(e));
            processData(
                    new ResponseValue(
                            -1,
                            null,
                            "prevResponse value is "
                                    + prevResponse
                                    + " exception "
                                    + ExceptionUtil.getErrorMessage(e),
                            null,
                            null));
            running = false;
        }
    }

    public void processData(ResponseValue value) {
        try {
            queue.put(value);
        } catch (InterruptedException e1) {
            LOG.warn(
                    "put value error,value is {},currentParam is {} ,errorInfo is {}",
                    value,
                    currentParam,
                    ExceptionUtil.getErrorMessage(e1));
        }
    }

    public ResponseValue takeEvent() {
        ResponseValue responseValue = null;
        try {
            responseValue = queue.poll();
        } catch (Exception e) {
            LOG.error("takeEvent interrupted error:{}", ExceptionUtil.getErrorMessage(e));
        }

        return responseValue;
    }

    public void close() {
        try {
            HttpUtil.closeClient(httpClient);
            scheduledExecutorService.shutdown();
        } catch (Exception e) {
            LOG.warn("close resource error,msg is " + ExceptionUtil.getErrorMessage(e));
        }
    }

    @Override
    public String toString() {
        return "HttpClient{"
                + ", restConfig="
                + restConfig
                + ", first="
                + first
                + ", requestRetryTime="
                + requestRetryTime
                + ", originalBodyList="
                + originalBodyList
                + ", originalParamList="
                + originalParamList
                + ", originalHeaderList="
                + originalHeaderList
                + ", currentParam="
                + currentParam
                + ", prevParam="
                + prevParam
                + ", prevResponse='"
                + prevResponse
                + '\''
                + ", reachEnd="
                + reachEnd
                + ", running="
                + running
                + '}';
    }
}
