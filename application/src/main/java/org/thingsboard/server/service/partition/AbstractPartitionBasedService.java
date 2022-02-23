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
package org.thingsboard.server.service.partition;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.queue.discovery.TbApplicationEventListener;
import org.thingsboard.server.queue.discovery.event.PartitionChangeEvent;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;

@Slf4j
public abstract class AbstractPartitionBasedService<T extends EntityId> extends TbApplicationEventListener<PartitionChangeEvent> {

    protected final ConcurrentMap<TopicPartitionInfo, Set<T>> partitionedEntities = new ConcurrentHashMap<>();
    final Queue<Set<TopicPartitionInfo>> subscribeQueue = new ConcurrentLinkedQueue<>();

    protected ListeningScheduledExecutorService scheduledExecutor;

    abstract protected String getServiceName();

    abstract protected String getSchedulerExecutorName();

    abstract protected void onAddedPartitions(Set<TopicPartitionInfo> addedPartitions);

    abstract protected void cleanupEntityOnPartitionRemoval(T entityId);

    public Set<T> getPartitionedEntities(TopicPartitionInfo tpi) {
        return partitionedEntities.get(tpi);
    }

    protected void init() {
        // Should be always single threaded due to absence of locks.
        scheduledExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor(ThingsBoardThreadFactory.forName(getSchedulerExecutorName())));
    }

    protected ServiceType getServiceType() {
        return ServiceType.TB_CORE;
    }

    protected void stop() {
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
        }
    }

    /**
     * DiscoveryService will call this event from the single thread (one-by-one).
     * Events order is guaranteed by DiscoveryService.
     * The only concurrency is expected from the [main] thread on Application started.
     * Async implementation. Locks is not allowed by design.
     * Any locks or delays in this module will affect DiscoveryService and entire system
     */
    @Override
    protected void onTbApplicationEvent(PartitionChangeEvent partitionChangeEvent) {
        if (getServiceType().equals(partitionChangeEvent.getServiceType())) {
            log.debug("onTbApplicationEvent, processing event: {}", partitionChangeEvent);
            subscribeQueue.add(partitionChangeEvent.getPartitions());
            scheduledExecutor.submit(this::pollInitStateFromDB);
        }
    }

    protected void pollInitStateFromDB() {
        final Set<TopicPartitionInfo> partitions = getLatestPartitions();
        if (partitions == null) {
            log.debug("Nothing to do. Partitions are empty.");
            return;
        }
        initStateFromDB(partitions);
    }

    private void initStateFromDB(Set<TopicPartitionInfo> partitions) {
        try {
            log.info("[{}] CURRENT PARTITIONS: {}", getServiceName(), partitionedEntities.keySet());
            log.info("[{}] NEW PARTITIONS: {}", getServiceName(), partitions);

            Set<TopicPartitionInfo> addedPartitions = new HashSet<>(partitions);
            addedPartitions.removeAll(partitionedEntities.keySet());

            log.info("[{}] ADDED PARTITIONS: {}", getServiceName(), addedPartitions);

            Set<TopicPartitionInfo> removedPartitions = new HashSet<>(partitionedEntities.keySet());
            removedPartitions.removeAll(partitions);

            log.info("[{}] REMOVED PARTITIONS: {}", getServiceName(), removedPartitions);

            // We no longer manage current partition of entities;
            removedPartitions.forEach(partition -> {
                Set<T> entities = partitionedEntities.remove(partition);
                entities.forEach(this::cleanupEntityOnPartitionRemoval);
            });

            onRepartitionEvent();

            addedPartitions.forEach(tpi -> partitionedEntities.computeIfAbsent(tpi, key -> ConcurrentHashMap.newKeySet()));

            if (!addedPartitions.isEmpty()) {
                onAddedPartitions(addedPartitions);
            }

            log.info("[{}] Managing following partitions:", getServiceName());
            partitionedEntities.forEach((tpi, entities) -> {
                log.info("[{}][{}]: {} entities", getServiceName(), tpi.getFullTopicName(), entities.size());
            });
        } catch (Throwable t) {
            log.warn("[{}] Failed to init entities state from DB", getServiceName(), t);
        }
    }

    protected void onRepartitionEvent() {
    }

    private Set<TopicPartitionInfo> getLatestPartitions() {
        log.debug("getLatestPartitionsFromQueue, queue size {}", subscribeQueue.size());
        Set<TopicPartitionInfo> partitions = null;
        while (!subscribeQueue.isEmpty()) {
            partitions = subscribeQueue.poll();
            log.debug("polled from the queue partitions {}", partitions);
        }
        log.debug("getLatestPartitionsFromQueue, partitions {}", partitions);
        return partitions;
    }

}
