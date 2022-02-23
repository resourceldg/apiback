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
package org.thingsboard.server.queue.memory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.thingsboard.server.queue.TbQueueMsg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class InMemoryStorageTest {

    InMemoryStorage storage = InMemoryStorage.getInstance();

    @Before
    public void setUp() {
        storage.cleanup();
    }

    @After
    public void tearDown() {
        storage.cleanup();
    }

    @Test
    public void givenStorage_whenGetLagTotal_thenReturnInteger() throws InterruptedException {
        assertThat(storage.getLagTotal()).isEqualTo(0);
        storage.put("main", mock(TbQueueMsg.class));
        assertThat(storage.getLagTotal()).isEqualTo(1);
        storage.put("main", mock(TbQueueMsg.class));
        assertThat(storage.getLagTotal()).isEqualTo(2);
        storage.put("hp", mock(TbQueueMsg.class));
        assertThat(storage.getLagTotal()).isEqualTo(3);
        storage.get("main");
        assertThat(storage.getLagTotal()).isEqualTo(1);
        storage.cleanup();
        assertThat(storage.getLagTotal()).isEqualTo(0);
    }
}