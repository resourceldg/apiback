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
package org.thingsboard.server.dao.sql.event;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.data.Event;
import org.thingsboard.server.common.data.event.DebugEvent;
import org.thingsboard.server.common.data.event.ErrorEventFilter;
import org.thingsboard.server.common.data.event.EventFilter;
import org.thingsboard.server.common.data.event.LifeCycleEventFilter;
import org.thingsboard.server.common.data.event.StatisticsEventFilter;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.TimePageLink;
import org.thingsboard.server.common.stats.StatsFactory;
import org.thingsboard.server.dao.DaoUtil;
import org.thingsboard.server.dao.event.EventDao;
import org.thingsboard.server.dao.model.sql.AttributeKvEntity;
import org.thingsboard.server.dao.model.sql.EventEntity;
import org.thingsboard.server.dao.sql.JpaAbstractDao;
import org.thingsboard.server.dao.sql.ScheduledLogExecutorComponent;
import org.thingsboard.server.dao.sql.TbSqlBlockingQueueParams;
import org.thingsboard.server.dao.sql.TbSqlBlockingQueueWrapper;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.thingsboard.server.dao.model.ModelConstants.NULL_UUID;

/**
 * Created by Valerii Sosliuk on 5/3/2017.
 */
@Slf4j
@Component
public class JpaBaseEventDao extends JpaAbstractDao<EventEntity, Event> implements EventDao {

    private final UUID systemTenantId = NULL_UUID;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventInsertRepository eventInsertRepository;

    @Autowired
    private EventCleanupRepository eventCleanupRepository;

    @Override
    protected Class<EventEntity> getEntityClass() {
        return EventEntity.class;
    }

    @Override
    protected CrudRepository<EventEntity, UUID> getCrudRepository() {
        return eventRepository;
    }

    @Autowired
    ScheduledLogExecutorComponent logExecutor;

    @Autowired
    private StatsFactory statsFactory;

    @Value("${sql.events.batch_size:10000}")
    private int batchSize;

    @Value("${sql.events.batch_max_delay:100}")
    private long maxDelay;

    @Value("${sql.events.stats_print_interval_ms:10000}")
    private long statsPrintIntervalMs;

    @Value("${sql.events.batch_threads:3}")
    private int batchThreads;

    @Value("${sql.batch_sort:false}")
    private boolean batchSortEnabled;

    private TbSqlBlockingQueueWrapper<EventEntity> queue;

    @PostConstruct
    private void init() {
        TbSqlBlockingQueueParams params = TbSqlBlockingQueueParams.builder()
                .logName("Events")
                .batchSize(batchSize)
                .maxDelay(maxDelay)
                .statsPrintIntervalMs(statsPrintIntervalMs)
                .statsNamePrefix("events")
                .batchSortEnabled(batchSortEnabled)
                .build();
        Function<EventEntity, Integer> hashcodeFunction = entity -> entity.getEntityId().hashCode();
        queue = new TbSqlBlockingQueueWrapper<>(params, hashcodeFunction, batchThreads, statsFactory);
        queue.init(logExecutor, v -> eventInsertRepository.save(v),
                Comparator.comparing((EventEntity eventEntity) -> eventEntity.getTs())
        );
    }

    @PreDestroy
    private void destroy() {
        if (queue != null) {
            queue.destroy();
        }
    }

    @Override
    public ListenableFuture<Void> saveAsync(Event event) {
        log.debug("Save event [{}] ", event);
        if (event.getId() == null) {
            UUID timeBased = Uuids.timeBased();
            event.setId(new EventId(timeBased));
            event.setCreatedTime(Uuids.unixTimestamp(timeBased));
        } else if (event.getCreatedTime() == 0L) {
            UUID eventId = event.getId().getId();
            if (eventId.version() == 1) {
                event.setCreatedTime(Uuids.unixTimestamp(eventId));
            } else {
                event.setCreatedTime(System.currentTimeMillis());
            }
        }
        if (StringUtils.isEmpty(event.getUid())) {
            event.setUid(event.getId().toString());
        }

        return save(new EventEntity(event));
    }

    private ListenableFuture<Void> save(EventEntity entity) {
        log.debug("Save event [{}] ", entity);
        if (entity.getTenantId() == null) {
            log.trace("Save system event with predefined id {}", systemTenantId);
            entity.setTenantId(systemTenantId);
        }
        if (entity.getUuid() == null) {
            entity.setUuid(Uuids.timeBased());
        }
        if (StringUtils.isEmpty(entity.getEventUid())) {
            entity.setEventUid(entity.getUuid().toString());
        }
        return addToQueue(entity);
    }

    private ListenableFuture<Void> addToQueue(EventEntity entity) {
        return queue.add(entity);
    }

    @Override
    public Event findEvent(UUID tenantId, EntityId entityId, String eventType, String eventUid) {
        return DaoUtil.getData(eventRepository.findByTenantIdAndEntityTypeAndEntityIdAndEventTypeAndEventUid(
                tenantId, entityId.getEntityType(), entityId.getId(), eventType, eventUid));
    }

    @Override
    public PageData<Event> findEvents(UUID tenantId, EntityId entityId, TimePageLink pageLink) {
        return DaoUtil.toPageData(
                eventRepository
                        .findEventsByTenantIdAndEntityId(
                                tenantId,
                                entityId.getEntityType(),
                                entityId.getId(),
                                Objects.toString(pageLink.getTextSearch(), ""),
                                pageLink.getStartTime(),
                                pageLink.getEndTime(),
                                DaoUtil.toPageable(pageLink)));
    }

    @Override
    public PageData<Event> findEvents(UUID tenantId, EntityId entityId, String eventType, TimePageLink pageLink) {
        return DaoUtil.toPageData(
                eventRepository
                        .findEventsByTenantIdAndEntityIdAndEventType(
                                tenantId,
                                entityId.getEntityType(),
                                entityId.getId(),
                                eventType,
                                pageLink.getStartTime(),
                                pageLink.getEndTime(),
                                DaoUtil.toPageable(pageLink)));
    }

    @Override
    public PageData<Event> findEventByFilter(UUID tenantId, EntityId entityId, EventFilter eventFilter, TimePageLink pageLink) {
        if (eventFilter.hasFilterForJsonBody()) {
            switch (eventFilter.getEventType()) {
                case DEBUG_RULE_NODE:
                case DEBUG_RULE_CHAIN:
                    return findEventByFilter(tenantId, entityId, (DebugEvent) eventFilter, pageLink);
                case LC_EVENT:
                    return findEventByFilter(tenantId, entityId, (LifeCycleEventFilter) eventFilter, pageLink);
                case ERROR:
                    return findEventByFilter(tenantId, entityId, (ErrorEventFilter) eventFilter, pageLink);
                case STATS:
                    return findEventByFilter(tenantId, entityId, (StatisticsEventFilter) eventFilter, pageLink);
                default:
                    throw new RuntimeException("Not supported event type: " + eventFilter.getEventType());
            }
        } else {
            return findEvents(tenantId, entityId, eventFilter.getEventType().name(), pageLink);
        }
    }

    private PageData<Event> findEventByFilter(UUID tenantId, EntityId entityId, DebugEvent eventFilter, TimePageLink pageLink) {
        return DaoUtil.toPageData(
                eventRepository.findDebugRuleNodeEvents(
                        tenantId,
                        entityId.getId(),
                        entityId.getEntityType().name(),
                        eventFilter.getEventType().name(),
                        notNull(pageLink.getStartTime()),
                        notNull(pageLink.getEndTime()),
                        eventFilter.getMsgDirectionType(),
                        eventFilter.getServer(),
                        eventFilter.getEntityName(),
                        eventFilter.getRelationType(),
                        eventFilter.getEntityId(),
                        eventFilter.getMsgType(),
                        eventFilter.isError(),
                        eventFilter.getErrorStr(),
                        eventFilter.getDataSearch(),
                        eventFilter.getMetadataSearch(),
                        DaoUtil.toPageable(pageLink)));
    }

    private PageData<Event> findEventByFilter(UUID tenantId, EntityId entityId, ErrorEventFilter eventFilter, TimePageLink pageLink) {
        return DaoUtil.toPageData(
                eventRepository.findErrorEvents(
                        tenantId,
                        entityId.getId(),
                        entityId.getEntityType().name(),
                        notNull(pageLink.getStartTime()),
                        notNull(pageLink.getEndTime()),
                        eventFilter.getServer(),
                        eventFilter.getMethod(),
                        eventFilter.getErrorStr(),
                        DaoUtil.toPageable(pageLink))
        );
    }

    private PageData<Event> findEventByFilter(UUID tenantId, EntityId entityId, LifeCycleEventFilter eventFilter, TimePageLink pageLink) {
        boolean statusFilterEnabled = !StringUtils.isEmpty(eventFilter.getStatus());
        boolean statusFilter = statusFilterEnabled && eventFilter.getStatus().equalsIgnoreCase("Success");
        return DaoUtil.toPageData(
                eventRepository.findLifeCycleEvents(
                        tenantId,
                        entityId.getId(),
                        entityId.getEntityType().name(),
                        notNull(pageLink.getStartTime()),
                        notNull(pageLink.getEndTime()),
                        eventFilter.getServer(),
                        eventFilter.getEvent(),
                        statusFilterEnabled,
                        statusFilter,
                        eventFilter.getErrorStr(),
                        DaoUtil.toPageable(pageLink))
        );
    }

    private PageData<Event> findEventByFilter(UUID tenantId, EntityId entityId, StatisticsEventFilter eventFilter, TimePageLink pageLink) {
        return DaoUtil.toPageData(
                eventRepository.findStatisticsEvents(
                        tenantId,
                        entityId.getId(),
                        entityId.getEntityType().name(),
                        notNull(pageLink.getStartTime()),
                        notNull(pageLink.getEndTime()),
                        eventFilter.getServer(),
                        notNull(eventFilter.getMessagesProcessed()),
                        notNull(eventFilter.getErrorsOccurred()),
                        DaoUtil.toPageable(pageLink))
        );
    }

    @Override
    public List<Event> findLatestEvents(UUID tenantId, EntityId entityId, String eventType, int limit) {
        List<EventEntity> latest = eventRepository.findLatestByTenantIdAndEntityTypeAndEntityIdAndEventType(
                tenantId,
                entityId.getEntityType(),
                entityId.getId(),
                eventType,
                PageRequest.of(0, limit));
        return DaoUtil.convertDataList(latest);
    }

    @Override
    public void cleanupEvents(long regularEventStartTs, long regularEventEndTs, long debugEventStartTs, long debugEventEndTs) {
        log.info("Going to cleanup old events. Interval for regular events: [{}:{}], for debug events: [{}:{}]", regularEventStartTs, regularEventEndTs, debugEventStartTs, debugEventEndTs);
        eventCleanupRepository.cleanupEvents(regularEventStartTs, regularEventEndTs, debugEventStartTs, debugEventEndTs);
    }

    private long notNull(Long value) {
        return value != null ? value : 0;
    }

    private int notNull(Integer value) {
        return value != null ? value : 0;
    }

}
