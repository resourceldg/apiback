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
package org.thingsboard.server.transport.mqtt.rpc;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.thingsboard.server.common.data.TransportPayloadType;
import org.thingsboard.server.common.data.device.profile.MqttTopics;

@Slf4j
public abstract class AbstractMqttServerSideRpcJsonIntegrationTest extends AbstractMqttServerSideRpcIntegrationTest {

    @Before
    public void beforeTest() throws Exception {
        processBeforeTest("RPC test device", "RPC test gateway", TransportPayloadType.JSON, null, null);
    }

    @After
    public void afterTest() throws Exception {
        super.processAfterTest();
    }

    @Test
    public void testServerMqttOneWayRpc() throws Exception {
        processOneWayRpcTest(MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC);
    }

    @Test
    public void testServerMqttOneWayRpcOnShortTopic() throws Exception {
        processOneWayRpcTest(MqttTopics.DEVICE_RPC_REQUESTS_SUB_SHORT_TOPIC);
    }

    @Test
    public void testServerMqttOneWayRpcOnShortJsonTopic() throws Exception {
        processOneWayRpcTest(MqttTopics.DEVICE_RPC_REQUESTS_SUB_SHORT_JSON_TOPIC);
    }

    @Test
    public void testServerMqttTwoWayRpc() throws Exception {
        processJsonTwoWayRpcTest(MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC);
    }

    @Test
    public void testServerMqttTwoWayRpcOnShortTopic() throws Exception {
        processJsonTwoWayRpcTest(MqttTopics.DEVICE_RPC_REQUESTS_SUB_SHORT_TOPIC);
    }

    @Test
    public void testServerMqttTwoWayRpcOnShortJsonTopic() throws Exception {
        processJsonTwoWayRpcTest(MqttTopics.DEVICE_RPC_REQUESTS_SUB_SHORT_JSON_TOPIC);
    }

    @Test
    public void testGatewayServerMqttOneWayRpc() throws Exception {
        processJsonOneWayRpcTestGateway("Gateway Device OneWay RPC Json");
    }

    @Test
    public void testGatewayServerMqttTwoWayRpc() throws Exception {
        processJsonTwoWayRpcTestGateway("Gateway Device TwoWay RPC Json");
    }

    protected void processJsonOneWayRpcTestGateway(String deviceName) throws Exception {
        MqttAsyncClient client = getMqttAsyncClient(gatewayAccessToken);
        String payload = "{\"device\": \"" + deviceName + "\", \"type\": \"" + TransportPayloadType.JSON.name() + "\"}";
        byte[] payloadBytes = payload.getBytes();
        validateOneWayRpcGatewayResponse(deviceName, client, payloadBytes);
    }

}
