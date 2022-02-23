/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
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
 */
package org.thingsboard.server.controller;

import com.google.common.util.concurrent.FutureCallback;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.DeviceTypeFilter;
import org.thingsboard.server.common.data.query.EntityCountQuery;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataPageLink;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.EntityKeyValueType;
import org.thingsboard.server.common.data.query.FilterPredicateValue;
import org.thingsboard.server.common.data.query.KeyFilter;
import org.thingsboard.server.common.data.query.NumericFilterPredicate;
import org.thingsboard.server.common.data.query.TsValue;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.service.subscription.TbAttributeSubscriptionScope;
import org.thingsboard.server.service.telemetry.TelemetrySubscriptionService;
import org.thingsboard.server.service.telemetry.cmd.TelemetryPluginCmdsWrapper;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityCountCmd;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityCountUpdate;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityDataCmd;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityDataUpdate;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityHistoryCmd;
import org.thingsboard.server.service.telemetry.cmd.v2.LatestValueCmd;
import org.thingsboard.server.service.telemetry.cmd.v2.TimeSeriesCmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
public abstract class BaseWebsocketApiTest extends AbstractWebsocketTest {

    private Tenant savedTenant;
    private User tenantAdmin;
    private TbTestWebSocketClient wsClient;

    @Autowired
    private TelemetrySubscriptionService tsService;

    @Before
    public void beforeTest() throws Exception {
        loginSysAdmin();

        Tenant tenant = new Tenant();
        tenant.setTitle("My tenant");
        savedTenant = doPost("/api/tenant", tenant, Tenant.class);
        Assert.assertNotNull(savedTenant);

        tenantAdmin = new User();
        tenantAdmin.setAuthority(Authority.TENANT_ADMIN);
        tenantAdmin.setTenantId(savedTenant.getId());
        tenantAdmin.setEmail("tenant2@thingsboard.org");
        tenantAdmin.setFirstName("Joe");
        tenantAdmin.setLastName("Downs");

        tenantAdmin = createUserAndLogin(tenantAdmin, "testPassword1");

        wsClient = buildAndConnectWebSocketClient();
    }

    @After
    public void afterTest() throws Exception {
        wsClient.close();

        loginSysAdmin();

        doDelete("/api/tenant/" + savedTenant.getId().getId().toString())
                .andExpect(status().isOk());
    }

    @Test
    public void testEntityDataHistoryWsCmd() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        long now = System.currentTimeMillis();

        DeviceTypeFilter dtf = new DeviceTypeFilter();
        dtf.setDeviceNameFilter("D");
        dtf.setDeviceType("default");
        EntityDataQuery edq = new EntityDataQuery(dtf,
                new EntityDataPageLink(1, 0, null, null),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        EntityHistoryCmd historyCmd = new EntityHistoryCmd();
        historyCmd.setKeys(Arrays.asList("temperature"));
        historyCmd.setAgg(Aggregation.NONE);
        historyCmd.setLimit(1000);
        historyCmd.setStartTs(now - TimeUnit.HOURS.toMillis(1));
        historyCmd.setEndTs(now);
        EntityDataCmd cmd = new EntityDataCmd(1, edq, historyCmd, null, null);

        TelemetryPluginCmdsWrapper wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        String msg = wsClient.waitForReply();
        EntityDataUpdate update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        PageData<EntityData> pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        Assert.assertEquals(0, pageData.getData().get(0).getTimeseries().get("temperature").length);

        TsKvEntry dataPoint1 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(1), new LongDataEntry("temperature", 42L));
        TsKvEntry dataPoint2 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(2), new LongDataEntry("temperature", 42L));
        TsKvEntry dataPoint3 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(3), new LongDataEntry("temperature", 42L));
        List<TsKvEntry> tsData = Arrays.asList(dataPoint1, dataPoint2, dataPoint3);

        sendTelemetry(device, tsData);
        Thread.sleep(100);

        wsClient.send(mapper.writeValueAsString(wrapper));
        msg = wsClient.waitForReply();
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        List<EntityData> dataList = update.getUpdate();
        Assert.assertNotNull(dataList);
        Assert.assertEquals(1, dataList.size());
        Assert.assertEquals(device.getId(), dataList.get(0).getEntityId());
        TsValue[] tsArray = dataList.get(0).getTimeseries().get("temperature");
        Assert.assertEquals(3, tsArray.length);
        Assert.assertEquals(new TsValue(dataPoint1.getTs(), dataPoint1.getValueAsString()), tsArray[0]);
        Assert.assertEquals(new TsValue(dataPoint2.getTs(), dataPoint2.getValueAsString()), tsArray[1]);
        Assert.assertEquals(new TsValue(dataPoint3.getTs(), dataPoint3.getValueAsString()), tsArray[2]);
    }

    @Test
    public void testEntityDataTimeSeriesWsCmd() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        long now = System.currentTimeMillis();

        DeviceTypeFilter dtf = new DeviceTypeFilter();
        dtf.setDeviceNameFilter("D");
        dtf.setDeviceType("default");
        EntityDataQuery edq = new EntityDataQuery(dtf, new EntityDataPageLink(1, 0, null, null),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        EntityDataCmd cmd = new EntityDataCmd(1, edq, null, null, null);

        TelemetryPluginCmdsWrapper wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        String msg = wsClient.waitForReply();
        EntityDataUpdate update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        PageData<EntityData> pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());

        TimeSeriesCmd tsCmd = new TimeSeriesCmd();
        tsCmd.setKeys(Arrays.asList("temperature"));
        tsCmd.setAgg(Aggregation.NONE);
        tsCmd.setLimit(1000);
        tsCmd.setStartTs(now - TimeUnit.HOURS.toMillis(1));
        tsCmd.setTimeWindow(TimeUnit.HOURS.toMillis(1));

        TsKvEntry dataPoint1 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(1), new LongDataEntry("temperature", 42L));
        TsKvEntry dataPoint2 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(2), new LongDataEntry("temperature", 43L));
        TsKvEntry dataPoint3 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(3), new LongDataEntry("temperature", 44L));
        List<TsKvEntry> tsData = Arrays.asList(dataPoint1, dataPoint2, dataPoint3);

        sendTelemetry(device, tsData);
        Thread.sleep(100);

        cmd = new EntityDataCmd(1, null, null, null, tsCmd);
        wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));
        wsClient.send(mapper.writeValueAsString(wrapper));
        msg = wsClient.waitForReply();
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        List<EntityData> listData = update.getUpdate();
        Assert.assertNotNull(listData);
        Assert.assertEquals(1, listData.size());
        Assert.assertEquals(device.getId(), listData.get(0).getEntityId());
        TsValue[] tsArray = listData.get(0).getTimeseries().get("temperature");
        Assert.assertEquals(3, tsArray.length);
        Assert.assertEquals(new TsValue(dataPoint1.getTs(), dataPoint1.getValueAsString()), tsArray[0]);
        Assert.assertEquals(new TsValue(dataPoint2.getTs(), dataPoint2.getValueAsString()), tsArray[1]);
        Assert.assertEquals(new TsValue(dataPoint3.getTs(), dataPoint3.getValueAsString()), tsArray[2]);

        now = System.currentTimeMillis();
        TsKvEntry dataPoint4 = new BasicTsKvEntry(now, new LongDataEntry("temperature", 45L));
        wsClient.registerWaitForUpdate();
        Thread.sleep(100);
        sendTelemetry(device, Arrays.asList(dataPoint4));
        msg = wsClient.waitForUpdate();

        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        List<EntityData> eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getTimeseries());
        TsValue[] tsValues = eData.get(0).getTimeseries().get("temperature");
        Assert.assertNotNull(tsValues);
        Assert.assertEquals(new TsValue(dataPoint4.getTs(), dataPoint4.getValueAsString()), tsValues[0]);
    }

    @Test
    public void testEntityCountWsCmd() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        AttributeKvEntry dataPoint1 = new BaseAttributeKvEntry(System.currentTimeMillis(), new LongDataEntry("temperature", 42L));
        sendAttributes(device, TbAttributeSubscriptionScope.SERVER_SCOPE, Collections.singletonList(dataPoint1));

        DeviceTypeFilter dtf1 = new DeviceTypeFilter();
        dtf1.setDeviceNameFilter("D");
        dtf1.setDeviceType("default");
        EntityCountQuery edq1 = new EntityCountQuery(dtf1, Collections.emptyList());

        EntityCountCmd cmd1 = new EntityCountCmd(1, edq1);

        TelemetryPluginCmdsWrapper wrapper1 = new TelemetryPluginCmdsWrapper();
        wrapper1.setEntityCountCmds(Collections.singletonList(cmd1));

        wsClient.send(mapper.writeValueAsString(wrapper1));
        String msg1 = wsClient.waitForReply();
        EntityCountUpdate update1 = mapper.readValue(msg1, EntityCountUpdate.class);
        Assert.assertEquals(1, update1.getCmdId());
        Assert.assertEquals(1, update1.getCount());

        DeviceTypeFilter dtf2 = new DeviceTypeFilter();
        dtf2.setDeviceNameFilter("D");
        dtf2.setDeviceType("non-existing-device-type");
        EntityCountQuery edq2 = new EntityCountQuery(dtf2, Collections.emptyList());

        EntityCountCmd cmd2 = new EntityCountCmd(2, edq2);

        TelemetryPluginCmdsWrapper wrapper2 = new TelemetryPluginCmdsWrapper();
        wrapper2.setEntityCountCmds(Collections.singletonList(cmd2));
        wsClient.send(mapper.writeValueAsString(wrapper2));

        String msg2 = wsClient.waitForReply();
        EntityCountUpdate update2 = mapper.readValue(msg2, EntityCountUpdate.class);
        Assert.assertEquals(2, update2.getCmdId());
        Assert.assertEquals(0, update2.getCount());

        KeyFilter highTemperatureFilter = new KeyFilter();
        highTemperatureFilter.setKey(new EntityKey(EntityKeyType.ATTRIBUTE, "temperature"));
        NumericFilterPredicate predicate = new NumericFilterPredicate();
        predicate.setValue(FilterPredicateValue.fromDouble(40));
        predicate.setOperation(NumericFilterPredicate.NumericOperation.GREATER);
        highTemperatureFilter.setPredicate(predicate);
        highTemperatureFilter.setValueType(EntityKeyValueType.NUMERIC);

        DeviceTypeFilter dtf3 = new DeviceTypeFilter();
        dtf3.setDeviceNameFilter("D");
        dtf3.setDeviceType("default");
        EntityCountQuery edq3 = new EntityCountQuery(dtf3, Collections.singletonList(highTemperatureFilter));

        EntityCountCmd cmd3 = new EntityCountCmd(3, edq3);

        TelemetryPluginCmdsWrapper wrapper3 = new TelemetryPluginCmdsWrapper();
        wrapper3.setEntityCountCmds(Collections.singletonList(cmd3));
        wsClient.send(mapper.writeValueAsString(wrapper3));

        String msg3 = wsClient.waitForReply();
        EntityCountUpdate update3 = mapper.readValue(msg3, EntityCountUpdate.class);
        Assert.assertEquals(3, update3.getCmdId());
        Assert.assertEquals(1, update3.getCount());

        KeyFilter highTemperatureFilter2 = new KeyFilter();
        highTemperatureFilter2.setKey(new EntityKey(EntityKeyType.ATTRIBUTE, "temperature"));
        NumericFilterPredicate predicate2 = new NumericFilterPredicate();
        predicate2.setValue(FilterPredicateValue.fromDouble(50));
        predicate2.setOperation(NumericFilterPredicate.NumericOperation.GREATER);
        highTemperatureFilter2.setPredicate(predicate2);
        highTemperatureFilter2.setValueType(EntityKeyValueType.NUMERIC);

        DeviceTypeFilter dtf4 = new DeviceTypeFilter();
        dtf4.setDeviceNameFilter("D");
        dtf4.setDeviceType("default");
        EntityCountQuery edq4 = new EntityCountQuery(dtf4, Collections.singletonList(highTemperatureFilter2));

        EntityCountCmd cmd4 = new EntityCountCmd(4, edq4);

        TelemetryPluginCmdsWrapper wrapper4 = new TelemetryPluginCmdsWrapper();
        wrapper4.setEntityCountCmds(Collections.singletonList(cmd4));
        wsClient.send(mapper.writeValueAsString(wrapper4));

        String msg4 = wsClient.waitForReply();
        EntityCountUpdate update4 = mapper.readValue(msg4, EntityCountUpdate.class);
        Assert.assertEquals(4, update4.getCmdId());
        Assert.assertEquals(0, update4.getCount());
    }

    @Test
    public void testEntityDataLatestWidgetFlow() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        long now = System.currentTimeMillis();

        DeviceTypeFilter dtf = new DeviceTypeFilter();
        dtf.setDeviceNameFilter("D");
        dtf.setDeviceType("default");
        EntityDataQuery edq = new EntityDataQuery(dtf, new EntityDataPageLink(1, 0, null, null), Collections.emptyList(),
                Collections.singletonList(new EntityKey(EntityKeyType.TIME_SERIES, "temperature")), Collections.emptyList());

        EntityDataCmd cmd = new EntityDataCmd(1, edq, null, null, null);

        TelemetryPluginCmdsWrapper wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        String msg = wsClient.waitForReply();
        EntityDataUpdate update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        PageData<EntityData> pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        Assert.assertNotNull(pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature"));
        Assert.assertEquals(0, pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature").getTs());
        Assert.assertEquals("", pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature").getValue());

        TsKvEntry dataPoint1 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(1), new LongDataEntry("temperature", 42L));
        List<TsKvEntry> tsData = Arrays.asList(dataPoint1);
        sendTelemetry(device, tsData);

        Thread.sleep(100);

        LatestValueCmd latestCmd = new LatestValueCmd();
        latestCmd.setKeys(Collections.singletonList(new EntityKey(EntityKeyType.TIME_SERIES, "temperature")));
        cmd = new EntityDataCmd(1, null, null, latestCmd, null);
        wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        msg = wsClient.waitForReply();
        update = mapper.readValue(msg, EntityDataUpdate.class);

        Assert.assertEquals(1, update.getCmdId());

        List<EntityData> listData = update.getUpdate();
        Assert.assertNotNull(listData);
        Assert.assertEquals(1, listData.size());
        Assert.assertEquals(device.getId(), listData.get(0).getEntityId());
        Assert.assertNotNull(listData.get(0).getLatest().get(EntityKeyType.TIME_SERIES));
        TsValue tsValue = listData.get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature");
        Assert.assertEquals(new TsValue(dataPoint1.getTs(), dataPoint1.getValueAsString()), tsValue);

        now = System.currentTimeMillis();
        TsKvEntry dataPoint2 = new BasicTsKvEntry(now, new LongDataEntry("temperature", 52L));

        wsClient.registerWaitForUpdate();
        sendTelemetry(device, Arrays.asList(dataPoint2));
        msg = wsClient.waitForUpdate();

        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        List<EntityData> eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getLatest().get(EntityKeyType.TIME_SERIES));
        tsValue = eData.get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature");
        Assert.assertEquals(new TsValue(dataPoint2.getTs(), dataPoint2.getValueAsString()), tsValue);

        //Sending update from the past, while latest value has new timestamp;
        wsClient.registerWaitForUpdate();
        sendTelemetry(device, Arrays.asList(dataPoint1));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        Assert.assertNull(msg);

        //Sending duplicate update again
        wsClient.registerWaitForUpdate();
        sendTelemetry(device, Arrays.asList(dataPoint2));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        Assert.assertNull(msg);
    }

    @Test
    public void testEntityDataLatestTsWsCmd() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        long now = System.currentTimeMillis();

        DeviceTypeFilter dtf = new DeviceTypeFilter();
        dtf.setDeviceNameFilter("D");
        dtf.setDeviceType("default");
        EntityDataQuery edq = new EntityDataQuery(dtf, new EntityDataPageLink(1, 0, null, null), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LatestValueCmd latestCmd = new LatestValueCmd();
        latestCmd.setKeys(Collections.singletonList(new EntityKey(EntityKeyType.TIME_SERIES, "temperature")));
        EntityDataCmd cmd = new EntityDataCmd(1, edq, null, latestCmd, null);

        TelemetryPluginCmdsWrapper wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        String msg = wsClient.waitForReply();
        EntityDataUpdate update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        PageData<EntityData> pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        Assert.assertNotNull(pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature"));
        Assert.assertEquals(0, pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature").getTs());
        Assert.assertEquals("", pageData.getData().get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature").getValue());

        TsKvEntry dataPoint1 = new BasicTsKvEntry(now - TimeUnit.MINUTES.toMillis(1), new LongDataEntry("temperature", 42L));
        List<TsKvEntry> tsData = Arrays.asList(dataPoint1);
        sendTelemetry(device, tsData);

        Thread.sleep(100);

        cmd = new EntityDataCmd(1, edq, null, latestCmd, null);
        wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        msg = wsClient.waitForReply();
        update = mapper.readValue(msg, EntityDataUpdate.class);

        Assert.assertEquals(1, update.getCmdId());

        List<EntityData> listData = update.getUpdate();
        Assert.assertNotNull(listData);
        Assert.assertEquals(1, listData.size());
        Assert.assertEquals(device.getId(), listData.get(0).getEntityId());
        Assert.assertNotNull(listData.get(0).getLatest().get(EntityKeyType.TIME_SERIES));
        TsValue tsValue = listData.get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature");
        Assert.assertEquals(new TsValue(dataPoint1.getTs(), dataPoint1.getValueAsString()), tsValue);

        now = System.currentTimeMillis();
        TsKvEntry dataPoint2 = new BasicTsKvEntry(now, new LongDataEntry("temperature", 52L));

        wsClient.registerWaitForUpdate();
        sendTelemetry(device, Arrays.asList(dataPoint2));
        msg = wsClient.waitForUpdate();

        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        List<EntityData> eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getLatest().get(EntityKeyType.TIME_SERIES));
        tsValue = eData.get(0).getLatest().get(EntityKeyType.TIME_SERIES).get("temperature");
        Assert.assertEquals(new TsValue(dataPoint2.getTs(), dataPoint2.getValueAsString()), tsValue);

        //Sending update from the past, while latest value has new timestamp;
        wsClient.registerWaitForUpdate();
        sendTelemetry(device, Arrays.asList(dataPoint1));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        Assert.assertNull(msg);

        //Sending duplicate update again
        wsClient.registerWaitForUpdate();
        sendTelemetry(device, Arrays.asList(dataPoint2));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        Assert.assertNull(msg);
    }

    @Test
    public void testEntityDataLatestAttrWsCmd() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        long now = System.currentTimeMillis();

        DeviceTypeFilter dtf = new DeviceTypeFilter();
        dtf.setDeviceNameFilter("D");
        dtf.setDeviceType("default");
        EntityDataQuery edq = new EntityDataQuery(dtf, new EntityDataPageLink(1, 0, null, null),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LatestValueCmd latestCmd = new LatestValueCmd();
        latestCmd.setKeys(Collections.singletonList(new EntityKey(EntityKeyType.SERVER_ATTRIBUTE, "serverAttributeKey")));
        EntityDataCmd cmd = new EntityDataCmd(1, edq, null, latestCmd, null);

        TelemetryPluginCmdsWrapper wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        String msg = wsClient.waitForReply();
        Assert.assertNotNull(msg);
        EntityDataUpdate update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        PageData<EntityData> pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        Assert.assertNotNull(pageData.getData().get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey"));
        Assert.assertEquals(0, pageData.getData().get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey").getTs());
        Assert.assertEquals("", pageData.getData().get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey").getValue());


        wsClient.registerWaitForUpdate();
        Thread.sleep(500);

        AttributeKvEntry dataPoint1 = new BaseAttributeKvEntry(now - TimeUnit.MINUTES.toMillis(1), new LongDataEntry("serverAttributeKey", 42L));
        List<AttributeKvEntry> tsData = Arrays.asList(dataPoint1);
        sendAttributes(device, TbAttributeSubscriptionScope.SERVER_SCOPE, tsData);

        msg = wsClient.waitForUpdate();
        Assert.assertNotNull(msg);
        update = mapper.readValue(msg, EntityDataUpdate.class);

        Assert.assertEquals(1, update.getCmdId());
        List<EntityData> listData = update.getUpdate();
        Assert.assertNotNull(listData);
        Assert.assertEquals(1, listData.size());
        Assert.assertEquals(device.getId(), listData.get(0).getEntityId());
        Assert.assertNotNull(listData.get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE));
        TsValue tsValue = listData.get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey");
        Assert.assertEquals(new TsValue(dataPoint1.getLastUpdateTs(), dataPoint1.getValueAsString()), tsValue);

        now = System.currentTimeMillis();
        AttributeKvEntry dataPoint2 = new BaseAttributeKvEntry(now, new LongDataEntry("serverAttributeKey", 52L));

        wsClient.registerWaitForUpdate();
        Thread.sleep(500);
        sendAttributes(device, TbAttributeSubscriptionScope.SERVER_SCOPE, Arrays.asList(dataPoint2));
        msg = wsClient.waitForUpdate();
        Assert.assertNotNull(msg);

        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        List<EntityData> eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE));
        tsValue = eData.get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey");
        Assert.assertEquals(new TsValue(dataPoint2.getLastUpdateTs(), dataPoint2.getValueAsString()), tsValue);

        //Sending update from the past, while latest value has new timestamp;
        wsClient.registerWaitForUpdate();
        Thread.sleep(500);
        sendAttributes(device, TbAttributeSubscriptionScope.SERVER_SCOPE, Arrays.asList(dataPoint1));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        Assert.assertNull(msg);

        //Sending duplicate update again
        wsClient.registerWaitForUpdate();
        Thread.sleep(500);
        sendAttributes(device, TbAttributeSubscriptionScope.SERVER_SCOPE, Arrays.asList(dataPoint2));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        Assert.assertNull(msg);
    }

    @Test
    public void testEntityDataLatestAttrTypesWsCmd() throws Exception {
        Device device = new Device();
        device.setName("Device");
        device.setType("default");
        device.setLabel("testLabel" + (int) (Math.random() * 1000));
        device = doPost("/api/device", device, Device.class);

        long now = System.currentTimeMillis();

        DeviceTypeFilter dtf = new DeviceTypeFilter();
        dtf.setDeviceNameFilter("D");
        dtf.setDeviceType("default");
        EntityDataQuery edq = new EntityDataQuery(dtf, new EntityDataPageLink(1, 0, null, null),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        LatestValueCmd latestCmd = new LatestValueCmd();
        List<EntityKey> keys = new ArrayList<>();
        keys.add(new EntityKey(EntityKeyType.SERVER_ATTRIBUTE, "serverAttributeKey"));
        keys.add(new EntityKey(EntityKeyType.CLIENT_ATTRIBUTE, "clientAttributeKey"));
        keys.add(new EntityKey(EntityKeyType.SHARED_ATTRIBUTE, "sharedAttributeKey"));
        keys.add(new EntityKey(EntityKeyType.ATTRIBUTE, "anyAttributeKey"));
        latestCmd.setKeys(keys);
        EntityDataCmd cmd = new EntityDataCmd(1, edq, null, latestCmd, null);

        TelemetryPluginCmdsWrapper wrapper = new TelemetryPluginCmdsWrapper();
        wrapper.setEntityDataCmds(Collections.singletonList(cmd));

        wsClient.send(mapper.writeValueAsString(wrapper));
        String msg = wsClient.waitForReply();
        EntityDataUpdate update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        PageData<EntityData> pageData = update.getData();
        Assert.assertNotNull(pageData);
        Assert.assertEquals(1, pageData.getData().size());
        Assert.assertEquals(device.getId(), pageData.getData().get(0).getEntityId());
        Assert.assertNotNull(pageData.getData().get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey"));
        Assert.assertEquals(0, pageData.getData().get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey").getTs());
        Assert.assertEquals("", pageData.getData().get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey").getValue());
        Assert.assertNotNull(pageData.getData().get(0).getLatest().get(EntityKeyType.CLIENT_ATTRIBUTE).get("clientAttributeKey"));
        Assert.assertEquals(0, pageData.getData().get(0).getLatest().get(EntityKeyType.CLIENT_ATTRIBUTE).get("clientAttributeKey").getTs());
        Assert.assertEquals("", pageData.getData().get(0).getLatest().get(EntityKeyType.CLIENT_ATTRIBUTE).get("clientAttributeKey").getValue());
        Assert.assertNotNull(pageData.getData().get(0).getLatest().get(EntityKeyType.SHARED_ATTRIBUTE).get("sharedAttributeKey"));
        Assert.assertEquals(0, pageData.getData().get(0).getLatest().get(EntityKeyType.SHARED_ATTRIBUTE).get("sharedAttributeKey").getTs());
        Assert.assertEquals("", pageData.getData().get(0).getLatest().get(EntityKeyType.SHARED_ATTRIBUTE).get("sharedAttributeKey").getValue());
        Assert.assertNotNull(pageData.getData().get(0).getLatest().get(EntityKeyType.ATTRIBUTE).get("anyAttributeKey"));
        Assert.assertEquals(0, pageData.getData().get(0).getLatest().get(EntityKeyType.ATTRIBUTE).get("anyAttributeKey").getTs());
        Assert.assertEquals("", pageData.getData().get(0).getLatest().get(EntityKeyType.ATTRIBUTE).get("anyAttributeKey").getValue());

        wsClient.registerWaitForUpdate();
        AttributeKvEntry dataPoint1 = new BaseAttributeKvEntry(now - TimeUnit.MINUTES.toMillis(1), new LongDataEntry("serverAttributeKey", 42L));
        List<AttributeKvEntry> tsData = Arrays.asList(dataPoint1);
        Thread.sleep(100);

        sendAttributes(device, TbAttributeSubscriptionScope.SERVER_SCOPE, tsData);

        msg = wsClient.waitForUpdate();
        Assert.assertNotNull(msg);
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        List<EntityData> eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE));
        TsValue attrValue = eData.get(0).getLatest().get(EntityKeyType.SERVER_ATTRIBUTE).get("serverAttributeKey");
        Assert.assertEquals(new TsValue(dataPoint1.getLastUpdateTs(), dataPoint1.getValueAsString()), attrValue);

        //Sending update from the past, while latest value has new timestamp;
        wsClient.registerWaitForUpdate();
        sendAttributes(device, TbAttributeSubscriptionScope.SHARED_SCOPE, Arrays.asList(dataPoint1));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        Assert.assertNull(msg);

        //Sending duplicate update again
        wsClient.registerWaitForUpdate();
        sendAttributes(device, TbAttributeSubscriptionScope.CLIENT_SCOPE, Arrays.asList(dataPoint1));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        Assert.assertNull(msg);

        //Sending update from the past, while latest value has new timestamp;
        wsClient.registerWaitForUpdate();
        AttributeKvEntry dataPoint2 = new BaseAttributeKvEntry(now, new LongDataEntry("sharedAttributeKey", 42L));
        sendAttributes(device, TbAttributeSubscriptionScope.SHARED_SCOPE, Arrays.asList(dataPoint2));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getLatest().get(EntityKeyType.SHARED_ATTRIBUTE));
        attrValue = eData.get(0).getLatest().get(EntityKeyType.SHARED_ATTRIBUTE).get("sharedAttributeKey");
        Assert.assertEquals(new TsValue(dataPoint2.getLastUpdateTs(), dataPoint2.getValueAsString()), attrValue);

        wsClient.registerWaitForUpdate();
        AttributeKvEntry dataPoint3 = new BaseAttributeKvEntry(now, new LongDataEntry("clientAttributeKey", 42L));
        sendAttributes(device, TbAttributeSubscriptionScope.CLIENT_SCOPE, Arrays.asList(dataPoint3));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getLatest().get(EntityKeyType.CLIENT_ATTRIBUTE));
        attrValue = eData.get(0).getLatest().get(EntityKeyType.CLIENT_ATTRIBUTE).get("clientAttributeKey");
        Assert.assertEquals(new TsValue(dataPoint3.getLastUpdateTs(), dataPoint3.getValueAsString()), attrValue);

        wsClient.registerWaitForUpdate();
        AttributeKvEntry dataPoint4 = new BaseAttributeKvEntry(now, new LongDataEntry("anyAttributeKey", 42L));
        sendAttributes(device, TbAttributeSubscriptionScope.CLIENT_SCOPE, Arrays.asList(dataPoint4));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getLatest().get(EntityKeyType.ATTRIBUTE));
        attrValue = eData.get(0).getLatest().get(EntityKeyType.ATTRIBUTE).get("anyAttributeKey");
        Assert.assertEquals(new TsValue(dataPoint4.getLastUpdateTs(), dataPoint4.getValueAsString()), attrValue);

        wsClient.registerWaitForUpdate();
        AttributeKvEntry dataPoint5 = new BaseAttributeKvEntry(now, new LongDataEntry("anyAttributeKey", 43L));
        sendAttributes(device, TbAttributeSubscriptionScope.SERVER_SCOPE, Arrays.asList(dataPoint5));
        msg = wsClient.waitForUpdate(TimeUnit.SECONDS.toMillis(1));
        update = mapper.readValue(msg, EntityDataUpdate.class);
        Assert.assertEquals(1, update.getCmdId());
        eData = update.getUpdate();
        Assert.assertNotNull(eData);
        Assert.assertEquals(1, eData.size());
        Assert.assertEquals(device.getId(), eData.get(0).getEntityId());
        Assert.assertNotNull(eData.get(0).getLatest().get(EntityKeyType.ATTRIBUTE));
        attrValue = eData.get(0).getLatest().get(EntityKeyType.ATTRIBUTE).get("anyAttributeKey");
        Assert.assertEquals(new TsValue(dataPoint5.getLastUpdateTs(), dataPoint5.getValueAsString()), attrValue);
    }

    private void sendTelemetry(Device device, List<TsKvEntry> tsData) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        tsService.saveAndNotify(device.getTenantId(), null, device.getId(), tsData, 0, new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                latch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                latch.countDown();
            }
        });
        latch.await(3, TimeUnit.SECONDS);
    }

    private void sendAttributes(Device device, TbAttributeSubscriptionScope scope, List<AttributeKvEntry> attrData) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        tsService.saveAndNotify(device.getTenantId(), device.getId(), scope.name(), attrData, new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                latch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                latch.countDown();
            }
        });
        latch.await(3, TimeUnit.SECONDS);
    }
}
