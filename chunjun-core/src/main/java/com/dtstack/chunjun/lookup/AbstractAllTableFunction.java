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

package com.dtstack.chunjun.lookup;

import com.dtstack.chunjun.converter.AbstractRowConverter;
import com.dtstack.chunjun.factory.ChunJunThreadFactory;
import com.dtstack.chunjun.lookup.conf.LookupConf;

import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.types.RowKind;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * @author chuixue
 * @create 2021-04-09 14:30
 * @description
 */
public abstract class AbstractAllTableFunction extends TableFunction<RowData> {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractAllTableFunction.class);
    /** ?????????join??????????????? */
    protected final String[] keyNames;
    /** ?????? */
    protected AtomicReference<Object> cacheRef = new AtomicReference<>();
    /** ???????????? */
    private ScheduledExecutorService es;
    /** ???????????? */
    protected final LookupConf lookupConf;
    /** ???????????? */
    protected final String[] fieldsName;
    /** ????????????????????? */
    protected final AbstractRowConverter rowConverter;

    public AbstractAllTableFunction(
            String[] fieldNames,
            String[] keyNames,
            LookupConf lookupConf,
            AbstractRowConverter rowConverter) {
        this.keyNames = keyNames;
        this.lookupConf = lookupConf;
        this.fieldsName = fieldNames;
        this.rowConverter = rowConverter;
    }

    /** ????????????????????????????????? */
    protected void initCache() {
        Map<String, List<Map<String, Object>>> newCache = Maps.newConcurrentMap();
        cacheRef.set(newCache);
        loadData(newCache);
    }

    /** ?????????????????????????????? */
    protected void reloadCache() {
        // reload cacheRef and replace to old cacheRef
        Map<String, List<Map<String, Object>>> newCache = Maps.newConcurrentMap();
        try {
            loadData(newCache);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        cacheRef.set(newCache);
        LOG.info(
                "----- " + lookupConf.getTableName() + ": all cacheRef reload end:{}",
                LocalDateTime.now());
    }

    /**
     * ?????????????????????
     *
     * @param cacheRef
     */
    protected abstract void loadData(Object cacheRef);

    @Override
    public void open(FunctionContext context) throws Exception {
        super.open(context);
        initCache();
        LOG.info("----- all cacheRef init end-----");

        // start reload cache thread
        es = new ScheduledThreadPoolExecutor(1, new ChunJunThreadFactory("cache-all-reload"));
        es.scheduleAtFixedRate(
                this::reloadCache,
                lookupConf.getPeriod(),
                lookupConf.getPeriod(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * ??????????????????
     *
     * @param oneRow ????????????
     * @param tmpCache ???????????????<key ,list<value>>
     */
    protected void buildCache(
            Map<String, Object> oneRow, Map<String, List<Map<String, Object>>> tmpCache) {

        String cacheKey =
                new ArrayList<>(Arrays.asList(keyNames))
                        .stream()
                                .map(oneRow::get)
                                .map(String::valueOf)
                                .collect(Collectors.joining("_"));

        tmpCache.computeIfAbsent(cacheKey, key -> Lists.newArrayList()).add(oneRow);
    }

    /**
     * ?????????????????????????????????
     *
     * @param keys ??????join key??????
     */
    public void eval(Object... keys) {
        String cacheKey = Arrays.stream(keys).map(String::valueOf).collect(Collectors.joining("_"));
        List<Map<String, Object>> cacheList =
                ((Map<String, List<Map<String, Object>>>) (cacheRef.get())).get(cacheKey);
        // ????????????????????????(???/???)??????flink?????????????????????
        if (!CollectionUtils.isEmpty(cacheList)) {
            cacheList.forEach(one -> collect(fillData(one)));
        }
    }

    public RowData fillData(Object sideInput) {
        Map<String, Object> cacheInfo = (Map<String, Object>) sideInput;
        GenericRowData row = new GenericRowData(fieldsName.length);
        for (int i = 0; i < fieldsName.length; i++) {
            row.setField(i, cacheInfo.get(fieldsName[i]));
        }
        row.setRowKind(RowKind.INSERT);
        return row;
    }

    /** ???????????? */
    @Override
    public void close() throws Exception {
        if (null != es && !es.isShutdown()) {
            es.shutdown();
        }
    }
}
