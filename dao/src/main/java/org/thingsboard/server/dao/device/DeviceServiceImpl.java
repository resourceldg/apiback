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
package org.thingsboard.server.dao.device;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceInfo;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.EntitySubtype;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.EntityView;
import org.thingsboard.server.common.data.OtaPackage;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.device.DeviceSearchQuery;
import org.thingsboard.server.common.data.device.credentials.BasicMqttCredentials;
import org.thingsboard.server.common.data.device.data.CoapDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.data.DefaultDeviceConfiguration;
import org.thingsboard.server.common.data.device.data.DefaultDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.DeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.data.Lwm2mDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.data.MqttDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.data.SnmpDeviceTransportConfiguration;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.ota.OtaPackageType;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.EntitySearchDirection;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.common.data.tenant.profile.DefaultTenantProfileConfiguration;
import org.thingsboard.server.dao.customer.CustomerDao;
import org.thingsboard.server.dao.device.provision.ProvisionFailedException;
import org.thingsboard.server.dao.device.provision.ProvisionRequest;
import org.thingsboard.server.dao.device.provision.ProvisionResponseStatus;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.event.EventService;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.ota.OtaPackageService;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.service.PaginatedRemover;
import org.thingsboard.server.dao.tenant.TbTenantProfileCache;
import org.thingsboard.server.dao.tenant.TenantDao;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.thingsboard.server.common.data.CacheConstants.DEVICE_CACHE;
import static org.thingsboard.server.dao.DaoUtil.toUUIDs;
import static org.thingsboard.server.dao.model.ModelConstants.NULL_UUID;
import static org.thingsboard.server.dao.service.Validator.validateId;
import static org.thingsboard.server.dao.service.Validator.validateIds;
import static org.thingsboard.server.dao.service.Validator.validatePageLink;
import static org.thingsboard.server.dao.service.Validator.validateString;

@Service
@Slf4j
public class DeviceServiceImpl extends AbstractEntityService implements DeviceService {

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_DEVICE_PROFILE_ID = "Incorrect deviceProfileId ";
    public static final String INCORRECT_PAGE_LINK = "Incorrect page link ";
    public static final String INCORRECT_CUSTOMER_ID = "Incorrect customerId ";
    public static final String INCORRECT_DEVICE_ID = "Incorrect deviceId ";
    public static final String INCORRECT_EDGE_ID = "Incorrect edgeId ";

    @Autowired
    private DeviceDao deviceDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private CustomerDao customerDao;

    @Autowired
    private DeviceCredentialsService deviceCredentialsService;

    @Autowired
    private DeviceProfileService deviceProfileService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private EventService eventService;

    @Autowired
    @Lazy
    private TbTenantProfileCache tenantProfileCache;

    @Autowired
    private OtaPackageService otaPackageService;

    @Override
    public DeviceInfo findDeviceInfoById(TenantId tenantId, DeviceId deviceId) {
        log.trace("Executing findDeviceInfoById [{}]", deviceId);
        validateId(deviceId, INCORRECT_DEVICE_ID + deviceId);
        return deviceDao.findDeviceInfoById(tenantId, deviceId.getId());
    }

    @Cacheable(cacheNames = DEVICE_CACHE, key = "{#tenantId, #deviceId}")
    @Override
    public Device findDeviceById(TenantId tenantId, DeviceId deviceId) {
        log.trace("Executing findDeviceById [{}]", deviceId);
        validateId(deviceId, INCORRECT_DEVICE_ID + deviceId);
        if (TenantId.SYS_TENANT_ID.equals(tenantId)) {
            return deviceDao.findById(tenantId, deviceId.getId());
        } else {
            return deviceDao.findDeviceByTenantIdAndId(tenantId, deviceId.getId());
        }
    }

    @Override
    public ListenableFuture<Device> findDeviceByIdAsync(TenantId tenantId, DeviceId deviceId) {
        log.trace("Executing findDeviceById [{}]", deviceId);
        validateId(deviceId, INCORRECT_DEVICE_ID + deviceId);
        if (TenantId.SYS_TENANT_ID.equals(tenantId)) {
            return deviceDao.findByIdAsync(tenantId, deviceId.getId());
        } else {
            return deviceDao.findDeviceByTenantIdAndIdAsync(tenantId, deviceId.getId());
        }
    }

    @Cacheable(cacheNames = DEVICE_CACHE, key = "{#tenantId, #name}")
    @Override
    public Device findDeviceByTenantIdAndName(TenantId tenantId, String name) {
        log.trace("Executing findDeviceByTenantIdAndName [{}][{}]", tenantId, name);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        Optional<Device> deviceOpt = deviceDao.findDeviceByTenantIdAndName(tenantId.getId(), name);
        return deviceOpt.orElse(null);
    }

    @Caching(evict= {
            @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.name}"),
            @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.id}")
    })
    @Transactional
    @Override
    public Device saveDeviceWithAccessToken(Device device, String accessToken) {
        return doSaveDevice(device, accessToken, true);
    }

    @Caching(evict= {
            @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.name}"),
            @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.id}")
    })
    @Override
    public Device saveDevice(Device device, boolean doValidate) {
        return doSaveDevice(device, null, doValidate);
    }

    @Caching(evict= {
            @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.name}"),
            @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.id}")
    })
    @Override
    public Device saveDevice(Device device) {
        return doSaveDevice(device, null, true);
    }

    @Caching(evict= {
            @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.name}"),
            @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#device.tenantId, #device.id}")
    })
    @Transactional
    @Override
    public Device saveDeviceWithCredentials(Device device, DeviceCredentials deviceCredentials) {
        if (device.getId() == null) {
            Device deviceWithName = this.findDeviceByTenantIdAndName(device.getTenantId(), device.getName());
            device = deviceWithName == null ? device : deviceWithName.updateDevice(device);
        }
        Device savedDevice = this.saveDeviceWithoutCredentials(device, true);
        deviceCredentials.setDeviceId(savedDevice.getId());
        if (device.getId() == null) {
            deviceCredentialsService.createDeviceCredentials(savedDevice.getTenantId(), deviceCredentials);
        } else {
            DeviceCredentials foundDeviceCredentials = deviceCredentialsService.findDeviceCredentialsByDeviceId(device.getTenantId(), savedDevice.getId());
            if (foundDeviceCredentials == null) {
                deviceCredentialsService.createDeviceCredentials(savedDevice.getTenantId(), deviceCredentials);
            } else {
                deviceCredentials.setId(foundDeviceCredentials.getId());
                deviceCredentialsService.updateDeviceCredentials(device.getTenantId(), deviceCredentials);
            }
        }
        return savedDevice;
    }

    private Device doSaveDevice(Device device, String accessToken, boolean doValidate) {
        Device savedDevice = this.saveDeviceWithoutCredentials(device, doValidate);
        if (device.getId() == null) {
            DeviceCredentials deviceCredentials = new DeviceCredentials();
            deviceCredentials.setDeviceId(new DeviceId(savedDevice.getUuidId()));
            deviceCredentials.setCredentialsType(DeviceCredentialsType.ACCESS_TOKEN);
            deviceCredentials.setCredentialsId(!StringUtils.isEmpty(accessToken) ? accessToken : RandomStringUtils.randomAlphanumeric(20));
            deviceCredentialsService.createDeviceCredentials(savedDevice.getTenantId(), deviceCredentials);
        }
        return savedDevice;
    }

    private Device saveDeviceWithoutCredentials(Device device, boolean doValidate) {
        log.trace("Executing saveDevice [{}]", device);
        if (doValidate) {
            deviceValidator.validate(device, Device::getTenantId);
        }
        try {
            DeviceProfile deviceProfile;
            if (device.getDeviceProfileId() == null) {
                if (!StringUtils.isEmpty(device.getType())) {
                    deviceProfile = this.deviceProfileService.findOrCreateDeviceProfile(device.getTenantId(), device.getType());
                } else {
                    deviceProfile = this.deviceProfileService.findDefaultDeviceProfile(device.getTenantId());
                }
                device.setDeviceProfileId(new DeviceProfileId(deviceProfile.getId().getId()));
            } else {
                deviceProfile = this.deviceProfileService.findDeviceProfileById(device.getTenantId(), device.getDeviceProfileId());
                if (deviceProfile == null) {
                    throw new DataValidationException("Device is referencing non existing device profile!");
                }
            }
            device.setType(deviceProfile.getName());
            device.setDeviceData(syncDeviceData(deviceProfile, device.getDeviceData()));
            return deviceDao.saveAndFlush(device.getTenantId(), device);
        } catch (Exception t) {
            ConstraintViolationException e = extractConstraintViolationException(t).orElse(null);
            if (e != null && e.getConstraintName() != null && e.getConstraintName().equalsIgnoreCase("device_name_unq_key")) {
                // remove device from cache in case null value cached in the distributed redis.
                removeDeviceFromCacheByName(device.getTenantId(), device.getName());
                removeDeviceFromCacheById(device.getTenantId(), device.getId());
                throw new DataValidationException("Device with such name already exists!");
            } else {
                throw t;
            }
        }
    }

    private DeviceData syncDeviceData(DeviceProfile deviceProfile, DeviceData deviceData) {
        if (deviceData == null) {
            deviceData = new DeviceData();
        }
        if (deviceData.getConfiguration() == null || !deviceProfile.getType().equals(deviceData.getConfiguration().getType())) {
            switch (deviceProfile.getType()) {
                case DEFAULT:
                    deviceData.setConfiguration(new DefaultDeviceConfiguration());
                    break;
            }
        }
        if (deviceData.getTransportConfiguration() == null || !deviceProfile.getTransportType().equals(deviceData.getTransportConfiguration().getType())) {
            switch (deviceProfile.getTransportType()) {
                case DEFAULT:
                    deviceData.setTransportConfiguration(new DefaultDeviceTransportConfiguration());
                    break;
                case MQTT:
                    deviceData.setTransportConfiguration(new MqttDeviceTransportConfiguration());
                    break;
                case COAP:
                    deviceData.setTransportConfiguration(new CoapDeviceTransportConfiguration());
                    break;
                case LWM2M:
                    deviceData.setTransportConfiguration(new Lwm2mDeviceTransportConfiguration());
                    break;
                case SNMP:
                    deviceData.setTransportConfiguration(new SnmpDeviceTransportConfiguration());
                    break;
            }
        }
        return deviceData;
    }

    @Override
    public Device assignDeviceToCustomer(TenantId tenantId, DeviceId deviceId, CustomerId customerId) {
        Device device = findDeviceById(tenantId, deviceId);
        device.setCustomerId(customerId);
        Device savedDevice = saveDevice(device);
        removeDeviceFromCacheByName(tenantId, device.getName());
        removeDeviceFromCacheById(tenantId, device.getId());
        return savedDevice;
    }

    @Override
    public Device unassignDeviceFromCustomer(TenantId tenantId, DeviceId deviceId) {
        Device device = findDeviceById(tenantId, deviceId);
        device.setCustomerId(null);
        Device savedDevice = saveDevice(device);
        removeDeviceFromCacheByName(tenantId, device.getName());
        removeDeviceFromCacheById(tenantId, device.getId());
        return savedDevice;
    }

    @Transactional
    @Override
    public void deleteDevice(final TenantId tenantId, final DeviceId deviceId) {
        log.trace("Executing deleteDevice [{}]", deviceId);
        validateId(deviceId, INCORRECT_DEVICE_ID + deviceId);

        Device device = deviceDao.findById(tenantId, deviceId.getId());
        final String deviceName = device.getName();
        try {
            List<EntityView> entityViews = entityViewService.findEntityViewsByTenantIdAndEntityIdAsync(device.getTenantId(), deviceId).get();
            if (entityViews != null && !entityViews.isEmpty()) {
                throw new DataValidationException("Can't delete device that has entity views!");
            }
        } catch (ExecutionException | InterruptedException e) {
            log.error("Exception while finding entity views for deviceId [{}]", deviceId, e);
            throw new RuntimeException("Exception while finding entity views for deviceId [" + deviceId + "]", e);
        }

        DeviceCredentials deviceCredentials = deviceCredentialsService.findDeviceCredentialsByDeviceId(tenantId, deviceId);
        if (deviceCredentials != null) {
            deviceCredentialsService.deleteDeviceCredentials(tenantId, deviceCredentials);
        }
        deleteEntityRelations(tenantId, deviceId);

        deviceDao.removeById(tenantId, deviceId.getId());

        removeDeviceFromCacheByName(tenantId, deviceName);
        removeDeviceFromCacheById(tenantId, deviceId);
    }

    private void removeDeviceFromCacheByName(TenantId tenantId, String name) {
        Cache cache = cacheManager.getCache(DEVICE_CACHE);
        cache.evict(Arrays.asList(tenantId, name));
    }

    private void removeDeviceFromCacheById(TenantId tenantId, DeviceId deviceId) {
        if (deviceId == null) {
            return;
        }
        Cache cache = cacheManager.getCache(DEVICE_CACHE);
        cache.evict(Arrays.asList(tenantId, deviceId));
    }

    @Override
    public PageData<Device> findDevicesByTenantId(TenantId tenantId, PageLink pageLink) {
        log.trace("Executing findDevicesByTenantId, tenantId [{}], pageLink [{}]", tenantId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validatePageLink(pageLink);
        return deviceDao.findDevicesByTenantId(tenantId.getId(), pageLink);
    }

    @Override
    public PageData<DeviceInfo> findDeviceInfosByTenantId(TenantId tenantId, PageLink pageLink) {
        log.trace("Executing findDeviceInfosByTenantId, tenantId [{}], pageLink [{}]", tenantId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validatePageLink(pageLink);
        return deviceDao.findDeviceInfosByTenantId(tenantId.getId(), pageLink);
    }

    @Override
    public PageData<Device> findDevicesByTenantIdAndType(TenantId tenantId, String type, PageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndType, tenantId [{}], type [{}], pageLink [{}]", tenantId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink);
        return deviceDao.findDevicesByTenantIdAndType(tenantId.getId(), type, pageLink);
    }

    @Override
    public PageData<Device> findDevicesByTenantIdAndTypeAndEmptyOtaPackage(TenantId tenantId,
                                                                           DeviceProfileId deviceProfileId,
                                                                           OtaPackageType type,
                                                                           PageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndTypeAndEmptyOtaPackage, tenantId [{}], deviceProfileId [{}], type [{}], pageLink [{}]",
                tenantId, deviceProfileId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(tenantId, INCORRECT_DEVICE_PROFILE_ID + deviceProfileId);
        validatePageLink(pageLink);
        return deviceDao.findDevicesByTenantIdAndTypeAndEmptyOtaPackage(tenantId.getId(), deviceProfileId.getId(), type, pageLink);
    }

    @Override
    public Long countDevicesByTenantIdAndDeviceProfileIdAndEmptyOtaPackage(TenantId tenantId, DeviceProfileId deviceProfileId, OtaPackageType type) {
        log.trace("Executing countDevicesByTenantIdAndDeviceProfileIdAndEmptyOtaPackage, tenantId [{}], deviceProfileId [{}], type [{}]", tenantId, deviceProfileId, type);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(tenantId, INCORRECT_DEVICE_PROFILE_ID + deviceProfileId);
        return deviceDao.countDevicesByTenantIdAndDeviceProfileIdAndEmptyOtaPackage(tenantId.getId(), deviceProfileId.getId(), type);
    }

    @Override
    public PageData<DeviceInfo> findDeviceInfosByTenantIdAndType(TenantId tenantId, String type, PageLink pageLink) {
        log.trace("Executing findDeviceInfosByTenantIdAndType, tenantId [{}], type [{}], pageLink [{}]", tenantId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink);
        return deviceDao.findDeviceInfosByTenantIdAndType(tenantId.getId(), type, pageLink);
    }

    @Override
    public PageData<DeviceInfo> findDeviceInfosByTenantIdAndDeviceProfileId(TenantId tenantId, DeviceProfileId deviceProfileId, PageLink pageLink) {
        log.trace("Executing findDeviceInfosByTenantIdAndDeviceProfileId, tenantId [{}], deviceProfileId [{}], pageLink [{}]", tenantId, deviceProfileId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(deviceProfileId, INCORRECT_DEVICE_PROFILE_ID + deviceProfileId);
        validatePageLink(pageLink);
        return deviceDao.findDeviceInfosByTenantIdAndDeviceProfileId(tenantId.getId(), deviceProfileId.getId(), pageLink);
    }

    @Override
    public ListenableFuture<List<Device>> findDevicesByTenantIdAndIdsAsync(TenantId tenantId, List<DeviceId> deviceIds) {
        log.trace("Executing findDevicesByTenantIdAndIdsAsync, tenantId [{}], deviceIds [{}]", tenantId, deviceIds);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateIds(deviceIds, "Incorrect deviceIds " + deviceIds);
        return deviceDao.findDevicesByTenantIdAndIdsAsync(tenantId.getId(), toUUIDs(deviceIds));
    }


    @Override
    public void deleteDevicesByTenantId(TenantId tenantId) {
        log.trace("Executing deleteDevicesByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        tenantDevicesRemover.removeEntities(tenantId, tenantId);
    }

    @Override
    public PageData<Device> findDevicesByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId, PageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndCustomerId, tenantId [{}], customerId [{}], pageLink [{}]", tenantId, customerId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validatePageLink(pageLink);
        return deviceDao.findDevicesByTenantIdAndCustomerId(tenantId.getId(), customerId.getId(), pageLink);
    }

    @Override
    public PageData<DeviceInfo> findDeviceInfosByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId, PageLink pageLink) {
        log.trace("Executing findDeviceInfosByTenantIdAndCustomerId, tenantId [{}], customerId [{}], pageLink [{}]", tenantId, customerId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validatePageLink(pageLink);
        return deviceDao.findDeviceInfosByTenantIdAndCustomerId(tenantId.getId(), customerId.getId(), pageLink);
    }

    @Override
    public PageData<Device> findDevicesByTenantIdAndCustomerIdAndType(TenantId tenantId, CustomerId customerId, String type, PageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndCustomerIdAndType, tenantId [{}], customerId [{}], type [{}], pageLink [{}]", tenantId, customerId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink);
        return deviceDao.findDevicesByTenantIdAndCustomerIdAndType(tenantId.getId(), customerId.getId(), type, pageLink);
    }

    @Override
    public PageData<DeviceInfo> findDeviceInfosByTenantIdAndCustomerIdAndType(TenantId tenantId, CustomerId customerId, String type, PageLink pageLink) {
        log.trace("Executing findDeviceInfosByTenantIdAndCustomerIdAndType, tenantId [{}], customerId [{}], type [{}], pageLink [{}]", tenantId, customerId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink);
        return deviceDao.findDeviceInfosByTenantIdAndCustomerIdAndType(tenantId.getId(), customerId.getId(), type, pageLink);
    }

    @Override
    public PageData<DeviceInfo> findDeviceInfosByTenantIdAndCustomerIdAndDeviceProfileId(TenantId tenantId, CustomerId customerId, DeviceProfileId deviceProfileId, PageLink pageLink) {
        log.trace("Executing findDeviceInfosByTenantIdAndCustomerIdAndDeviceProfileId, tenantId [{}], customerId [{}], deviceProfileId [{}], pageLink [{}]", tenantId, customerId, deviceProfileId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validateId(deviceProfileId, INCORRECT_DEVICE_PROFILE_ID + deviceProfileId);
        validatePageLink(pageLink);
        return deviceDao.findDeviceInfosByTenantIdAndCustomerIdAndDeviceProfileId(tenantId.getId(), customerId.getId(), deviceProfileId.getId(), pageLink);
    }

    @Override
    public ListenableFuture<List<Device>> findDevicesByTenantIdCustomerIdAndIdsAsync(TenantId tenantId, CustomerId customerId, List<DeviceId> deviceIds) {
        log.trace("Executing findDevicesByTenantIdCustomerIdAndIdsAsync, tenantId [{}], customerId [{}], deviceIds [{}]", tenantId, customerId, deviceIds);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validateIds(deviceIds, "Incorrect deviceIds " + deviceIds);
        return deviceDao.findDevicesByTenantIdCustomerIdAndIdsAsync(tenantId.getId(),
                customerId.getId(), toUUIDs(deviceIds));
    }

    @Override
    public void unassignCustomerDevices(TenantId tenantId, CustomerId customerId) {
        log.trace("Executing unassignCustomerDevices, tenantId [{}], customerId [{}]", tenantId, customerId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        customerDeviceUnasigner.removeEntities(tenantId, customerId);
    }

    @Override
    public ListenableFuture<List<Device>> findDevicesByQuery(TenantId tenantId, DeviceSearchQuery query) {
        ListenableFuture<List<EntityRelation>> relations = relationService.findByQuery(tenantId, query.toEntitySearchQuery());
        ListenableFuture<List<Device>> devices = Futures.transformAsync(relations, r -> {
            EntitySearchDirection direction = query.toEntitySearchQuery().getParameters().getDirection();
            List<ListenableFuture<Device>> futures = new ArrayList<>();
            for (EntityRelation relation : r) {
                EntityId entityId = direction == EntitySearchDirection.FROM ? relation.getTo() : relation.getFrom();
                if (entityId.getEntityType() == EntityType.DEVICE) {
                    futures.add(findDeviceByIdAsync(tenantId, new DeviceId(entityId.getId())));
                }
            }
            return Futures.successfulAsList(futures);
        }, MoreExecutors.directExecutor());

        devices = Futures.transform(devices, new Function<List<Device>, List<Device>>() {
            @Nullable
            @Override
            public List<Device> apply(@Nullable List<Device> deviceList) {
                return deviceList == null ? Collections.emptyList() : deviceList.stream().filter(device -> query.getDeviceTypes().contains(device.getType())).collect(Collectors.toList());
            }
        }, MoreExecutors.directExecutor());

        return devices;
    }

    @Override
    public ListenableFuture<List<EntitySubtype>> findDeviceTypesByTenantId(TenantId tenantId) {
        log.trace("Executing findDeviceTypesByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        ListenableFuture<List<EntitySubtype>> tenantDeviceTypes = deviceDao.findTenantDeviceTypesAsync(tenantId.getId());
        return Futures.transform(tenantDeviceTypes,
                deviceTypes -> {
                    deviceTypes.sort(Comparator.comparing(EntitySubtype::getType));
                    return deviceTypes;
                }, MoreExecutors.directExecutor());
    }

    @Transactional
    @Override
    public Device assignDeviceToTenant(TenantId tenantId, Device device) {
        log.trace("Executing assignDeviceToTenant [{}][{}]", tenantId, device);

        try {
            List<EntityView> entityViews = entityViewService.findEntityViewsByTenantIdAndEntityIdAsync(device.getTenantId(), device.getId()).get();
            if (!CollectionUtils.isEmpty(entityViews)) {
                throw new DataValidationException("Can't assign device that has entity views to another tenant!");
            }
        } catch (ExecutionException | InterruptedException e) {
            log.error("Exception while finding entity views for deviceId [{}]", device.getId(), e);
            throw new RuntimeException("Exception while finding entity views for deviceId [" + device.getId() + "]", e);
        }

        eventService.removeEvents(device.getTenantId(), device.getId());

        relationService.removeRelations(device.getTenantId(), device.getId());

        TenantId oldTenantId = device.getTenantId();

        device.setTenantId(tenantId);
        device.setCustomerId(null);
        Device savedDevice = doSaveDevice(device, null, true);

        // explicitly remove device with previous tenant id from cache
        // result device object will have different tenant id and will not remove entity from cache
        removeDeviceFromCacheByName(oldTenantId, device.getName());
        removeDeviceFromCacheById(oldTenantId, device.getId());

        return savedDevice;
    }

    @Override
    @CacheEvict(cacheNames = DEVICE_CACHE, key = "{#profile.tenantId, #provisionRequest.deviceName}")
    @Transactional
    public Device saveDevice(ProvisionRequest provisionRequest, DeviceProfile profile) {
        Device device = new Device();
        device.setName(provisionRequest.getDeviceName());
        device.setType(profile.getName());
        device.setTenantId(profile.getTenantId());
        Device savedDevice = saveDevice(device);
        if (!StringUtils.isEmpty(provisionRequest.getCredentialsData().getToken()) ||
                !StringUtils.isEmpty(provisionRequest.getCredentialsData().getX509CertHash()) ||
                !StringUtils.isEmpty(provisionRequest.getCredentialsData().getUsername()) ||
                !StringUtils.isEmpty(provisionRequest.getCredentialsData().getPassword()) ||
                !StringUtils.isEmpty(provisionRequest.getCredentialsData().getClientId())) {
            DeviceCredentials deviceCredentials = deviceCredentialsService.findDeviceCredentialsByDeviceId(savedDevice.getTenantId(), savedDevice.getId());
            if (deviceCredentials == null) {
                deviceCredentials = new DeviceCredentials();
            }
            deviceCredentials.setDeviceId(savedDevice.getId());
            deviceCredentials.setCredentialsType(provisionRequest.getCredentialsType());
            switch (provisionRequest.getCredentialsType()) {
                case ACCESS_TOKEN:
                    deviceCredentials.setCredentialsId(provisionRequest.getCredentialsData().getToken());
                    break;
                case MQTT_BASIC:
                    BasicMqttCredentials mqttCredentials = new BasicMqttCredentials();
                    mqttCredentials.setClientId(provisionRequest.getCredentialsData().getClientId());
                    mqttCredentials.setUserName(provisionRequest.getCredentialsData().getUsername());
                    mqttCredentials.setPassword(provisionRequest.getCredentialsData().getPassword());
                    deviceCredentials.setCredentialsValue(JacksonUtil.toString(mqttCredentials));
                    break;
                case X509_CERTIFICATE:
                    deviceCredentials.setCredentialsValue(provisionRequest.getCredentialsData().getX509CertHash());
                    break;
                case LWM2M_CREDENTIALS:
                    break;
            }
            try {
                deviceCredentialsService.updateDeviceCredentials(savedDevice.getTenantId(), deviceCredentials);
            } catch (Exception e) {
                throw new ProvisionFailedException(ProvisionResponseStatus.FAILURE.name());
            }
        }
        removeDeviceFromCacheById(savedDevice.getTenantId(), savedDevice.getId()); // eviction by name is described as annotation @CacheEvict above
        return savedDevice;
    }

    @Override
    public PageData<UUID> findDevicesIdsByDeviceProfileTransportType(DeviceTransportType transportType, PageLink pageLink) {
        return deviceDao.findDevicesIdsByDeviceProfileTransportType(transportType, pageLink);
    }

    @Override
    public Device assignDeviceToEdge(TenantId tenantId, DeviceId deviceId, EdgeId edgeId) {
        Device device = findDeviceById(tenantId, deviceId);
        Edge edge = edgeService.findEdgeById(tenantId, edgeId);
        if (edge == null) {
            throw new DataValidationException("Can't assign device to non-existent edge!");
        }
        if (!edge.getTenantId().getId().equals(device.getTenantId().getId())) {
            throw new DataValidationException("Can't assign device to edge from different tenant!");
        }
        try {
            createRelation(tenantId, new EntityRelation(edgeId, deviceId, EntityRelation.CONTAINS_TYPE, RelationTypeGroup.EDGE));
        } catch (Exception e) {
            log.warn("[{}] Failed to create device relation. Edge Id: [{}]", deviceId, edgeId);
            throw new RuntimeException(e);
        }
        return device;
    }

    @Override
    public Device unassignDeviceFromEdge(TenantId tenantId, DeviceId deviceId, EdgeId edgeId) {
        Device device = findDeviceById(tenantId, deviceId);
        Edge edge = edgeService.findEdgeById(tenantId, edgeId);
        if (edge == null) {
            throw new DataValidationException("Can't unassign device from non-existent edge!");
        }

        checkAssignedEntityViewsToEdge(tenantId, deviceId, edgeId);

        try {
            deleteRelation(tenantId, new EntityRelation(edgeId, deviceId, EntityRelation.CONTAINS_TYPE, RelationTypeGroup.EDGE));
        } catch (Exception e) {
            log.warn("[{}] Failed to delete device relation. Edge Id: [{}]", deviceId, edgeId);
            throw new RuntimeException(e);
        }
        return device;
    }

    @Override
    public PageData<Device> findDevicesByTenantIdAndEdgeId(TenantId tenantId, EdgeId edgeId, PageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndEdgeId, tenantId [{}], edgeId [{}], pageLink [{}]", tenantId, edgeId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(edgeId, INCORRECT_EDGE_ID + edgeId);
        validatePageLink(pageLink);
        return deviceDao.findDevicesByTenantIdAndEdgeId(tenantId.getId(), edgeId.getId(), pageLink);
    }

    @Override
    public PageData<Device> findDevicesByTenantIdAndEdgeIdAndType(TenantId tenantId, EdgeId edgeId, String type, PageLink pageLink) {
        log.trace("Executing findDevicesByTenantIdAndEdgeIdAndType, tenantId [{}], edgeId [{}], type [{}] pageLink [{}]", tenantId, edgeId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(edgeId, INCORRECT_EDGE_ID + edgeId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink);
        return deviceDao.findDevicesByTenantIdAndEdgeIdAndType(tenantId.getId(), edgeId.getId(), type, pageLink);
    }

    @Override
    public long countByTenantId(TenantId tenantId) {
        return deviceDao.countByTenantId(tenantId);
    }

    private DataValidator<Device> deviceValidator =
            new DataValidator<Device>() {

                @Override
                protected void validateCreate(TenantId tenantId, Device device) {
                    DefaultTenantProfileConfiguration profileConfiguration =
                            (DefaultTenantProfileConfiguration) tenantProfileCache.get(tenantId).getProfileData().getConfiguration();
                    long maxDevices = profileConfiguration.getMaxDevices();
                    validateNumberOfEntitiesPerTenant(tenantId, deviceDao, maxDevices, EntityType.DEVICE);
                }

                @Override
                protected void validateUpdate(TenantId tenantId, Device device) {
                    Device old = deviceDao.findById(device.getTenantId(), device.getId().getId());
                    if (old == null) {
                        throw new DataValidationException("Can't update non existing device!");
                    }
                    if (!old.getName().equals(device.getName())) {
                        removeDeviceFromCacheByName(tenantId, old.getName());
                        removeDeviceFromCacheById(tenantId, device.getId());
                    }
                }

                @Override
                protected void validateDataImpl(TenantId tenantId, Device device) {
                    if (StringUtils.isEmpty(device.getName()) || device.getName().trim().length() == 0) {
                        throw new DataValidationException("Device name should be specified!");
                    }
                    if (device.getTenantId() == null) {
                        throw new DataValidationException("Device should be assigned to tenant!");
                    } else {
                        Tenant tenant = tenantDao.findById(device.getTenantId(), device.getTenantId().getId());
                        if (tenant == null) {
                            throw new DataValidationException("Device is referencing to non-existent tenant!");
                        }
                    }
                    if (device.getCustomerId() == null) {
                        device.setCustomerId(new CustomerId(NULL_UUID));
                    } else if (!device.getCustomerId().getId().equals(NULL_UUID)) {
                        Customer customer = customerDao.findById(device.getTenantId(), device.getCustomerId().getId());
                        if (customer == null) {
                            throw new DataValidationException("Can't assign device to non-existent customer!");
                        }
                        if (!customer.getTenantId().getId().equals(device.getTenantId().getId())) {
                            throw new DataValidationException("Can't assign device to customer from different tenant!");
                        }
                    }
                    Optional.ofNullable(device.getDeviceData())
                            .flatMap(deviceData -> Optional.ofNullable(deviceData.getTransportConfiguration()))
                            .ifPresent(DeviceTransportConfiguration::validate);

                    if (device.getFirmwareId() != null) {
                        OtaPackage firmware = otaPackageService.findOtaPackageById(tenantId, device.getFirmwareId());
                        if (firmware == null) {
                            throw new DataValidationException("Can't assign non-existent firmware!");
                        }
                        if (!firmware.getType().equals(OtaPackageType.FIRMWARE)) {
                            throw new DataValidationException("Can't assign firmware with type: " + firmware.getType());
                        }
                        if (firmware.getData() == null && !firmware.hasUrl()) {
                            throw new DataValidationException("Can't assign firmware with empty data!");
                        }
                        if (!firmware.getDeviceProfileId().equals(device.getDeviceProfileId())) {
                            throw new DataValidationException("Can't assign firmware with different deviceProfile!");
                        }
                    }

                    if (device.getSoftwareId() != null) {
                        OtaPackage software = otaPackageService.findOtaPackageById(tenantId, device.getSoftwareId());
                        if (software == null) {
                            throw new DataValidationException("Can't assign non-existent software!");
                        }
                        if (!software.getType().equals(OtaPackageType.SOFTWARE)) {
                            throw new DataValidationException("Can't assign software with type: " + software.getType());
                        }
                        if (software.getData() == null && !software.hasUrl()) {
                            throw new DataValidationException("Can't assign software with empty data!");
                        }
                        if (!software.getDeviceProfileId().equals(device.getDeviceProfileId())) {
                            throw new DataValidationException("Can't assign firmware with different deviceProfile!");
                        }
                    }
                }
            };

    private PaginatedRemover<TenantId, Device> tenantDevicesRemover =
            new PaginatedRemover<TenantId, Device>() {

                @Override
                protected PageData<Device> findEntities(TenantId tenantId, TenantId id, PageLink pageLink) {
                    return deviceDao.findDevicesByTenantId(id.getId(), pageLink);
                }

                @Override
                protected void removeEntity(TenantId tenantId, Device entity) {
                    deleteDevice(tenantId, new DeviceId(entity.getUuidId()));
                }
            };

    private PaginatedRemover<CustomerId, Device> customerDeviceUnasigner = new PaginatedRemover<CustomerId, Device>() {

        @Override
        protected PageData<Device> findEntities(TenantId tenantId, CustomerId id, PageLink pageLink) {
            return deviceDao.findDevicesByTenantIdAndCustomerId(tenantId.getId(), id.getId(), pageLink);
        }

        @Override
        protected void removeEntity(TenantId tenantId, Device entity) {
            unassignDeviceFromCustomer(tenantId, new DeviceId(entity.getUuidId()));
        }
    };
}
