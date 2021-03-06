/**
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

package org.apache.ambari.server.state;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ServiceComponentNotFoundException;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.ServiceResponse;
import org.apache.ambari.server.events.MaintenanceModeEvent;
import org.apache.ambari.server.events.ServiceInstalledEvent;
import org.apache.ambari.server.events.ServiceRemovedEvent;
import org.apache.ambari.server.events.publishers.AmbariEventPublisher;
import org.apache.ambari.server.orm.dao.ClusterDAO;
import org.apache.ambari.server.orm.dao.ClusterServiceDAO;
import org.apache.ambari.server.orm.dao.ServiceDesiredStateDAO;
import org.apache.ambari.server.orm.entities.ClusterEntity;
import org.apache.ambari.server.orm.entities.ClusterServiceEntity;
import org.apache.ambari.server.orm.entities.ClusterServiceEntityPK;
import org.apache.ambari.server.orm.entities.ServiceComponentDesiredStateEntity;
import org.apache.ambari.server.orm.entities.ServiceDesiredStateEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.ProvisionException;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.inject.persist.Transactional;


public class ServiceImpl implements Service {
  private final ReadWriteLock clusterGlobalLock;
  private ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

  private ClusterServiceEntity serviceEntity;
  private ServiceDesiredStateEntity serviceDesiredStateEntity;

  private static final Logger LOG =
      LoggerFactory.getLogger(ServiceImpl.class);

  private boolean persisted = false;
  private final Cluster cluster;
  private Map<String, ServiceComponent> components;
  private final boolean isClientOnlyService;

  @Inject
  Gson gson;
  @Inject
  private ClusterServiceDAO clusterServiceDAO;
  @Inject
  private ServiceDesiredStateDAO serviceDesiredStateDAO;
  @Inject
  private ClusterDAO clusterDAO;
  @Inject
  private ServiceComponentFactory serviceComponentFactory;
  @Inject
  private AmbariMetaInfo ambariMetaInfo;

  /**
   * Used to publish events relating to service CRUD operations.
   */
  @Inject
  private AmbariEventPublisher eventPublisher;

  private void init() {
    // TODO load from DB during restart?
  }

  @AssistedInject
  public ServiceImpl(@Assisted Cluster cluster, @Assisted String serviceName,
      Injector injector) throws AmbariException {
    injector.injectMembers(this);
    clusterGlobalLock = cluster.getClusterGlobalLock();
    serviceEntity = new ClusterServiceEntity();
    serviceEntity.setServiceName(serviceName);
    serviceDesiredStateEntity = new ServiceDesiredStateEntity();

    serviceDesiredStateEntity.setClusterServiceEntity(serviceEntity);
    serviceEntity.setServiceDesiredStateEntity(serviceDesiredStateEntity);

    this.cluster = cluster;

    components = new HashMap<String, ServiceComponent>();

    StackId stackId = cluster.getDesiredStackVersion();
    setDesiredStackVersion(stackId);

    ServiceInfo sInfo = ambariMetaInfo.getService(stackId.getStackName(),
        stackId.getStackVersion(), serviceName);
    isClientOnlyService = sInfo.isClientOnlyService();

    init();
  }

  @AssistedInject
  public ServiceImpl(@Assisted Cluster cluster, @Assisted ClusterServiceEntity
      serviceEntity, Injector injector) throws AmbariException {
    injector.injectMembers(this);
    clusterGlobalLock = cluster.getClusterGlobalLock();
    this.serviceEntity = serviceEntity;
    this.cluster = cluster;

    //TODO check for null states?
    serviceDesiredStateEntity = serviceEntity.getServiceDesiredStateEntity();

    components = new HashMap<String, ServiceComponent>();

    if (!serviceEntity.getServiceComponentDesiredStateEntities().isEmpty()) {
      for (ServiceComponentDesiredStateEntity serviceComponentDesiredStateEntity
          : serviceEntity.getServiceComponentDesiredStateEntities()) {
        try {
            components.put(serviceComponentDesiredStateEntity.getComponentName(),
                serviceComponentFactory.createExisting(this,
                    serviceComponentDesiredStateEntity));
          } catch(ProvisionException ex) {
            StackId stackId = cluster.getCurrentStackVersion();
            LOG.error(String.format("Can not get component info: stackName=%s, stackVersion=%s, serviceName=%s, componentName=%s",
                stackId.getStackName(), stackId.getStackVersion(),
                serviceEntity.getServiceName(),serviceComponentDesiredStateEntity.getComponentName()));
            ex.printStackTrace();
          }
      }
    }

    StackId stackId = getDesiredStackVersion();
    ServiceInfo sInfo = ambariMetaInfo.getService(stackId.getStackName(),
        stackId.getStackVersion(), getName());
    isClientOnlyService = sInfo.isClientOnlyService();

    persisted = true;
  }

  @Override
  public ReadWriteLock getClusterGlobalLock() {
    return clusterGlobalLock;
  }

  @Override
  public String getName() {
    return serviceEntity.getServiceName();
  }

  @Override
  public long getClusterId() {
    return cluster.getClusterId();
  }

  @Override
  public Map<String, ServiceComponent> getServiceComponents() {
    readWriteLock.readLock().lock();
    try {
      return new HashMap<String, ServiceComponent>(components);
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  @Override
  public void addServiceComponents(
      Map<String, ServiceComponent> components) throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      readWriteLock.writeLock().lock();
      try {
        for (ServiceComponent sc : components.values()) {
          addServiceComponent(sc);
        }
      } finally {
        readWriteLock.writeLock().unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public void addServiceComponent(ServiceComponent component)
      throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      readWriteLock.writeLock().lock();
      try {
        // TODO validation
        if (LOG.isDebugEnabled()) {
          LOG.debug("Adding a ServiceComponent to Service"
              + ", clusterName=" + cluster.getClusterName()
              + ", clusterId=" + cluster.getClusterId()
              + ", serviceName=" + getName()
              + ", serviceComponentName=" + component.getName());
        }
        if (components.containsKey(component.getName())) {
          throw new AmbariException("Cannot add duplicate ServiceComponent"
              + ", clusterName=" + cluster.getClusterName()
              + ", clusterId=" + cluster.getClusterId()
              + ", serviceName=" + getName()
              + ", serviceComponentName=" + component.getName());
        }
        components.put(component.getName(), component);
      } finally {
        readWriteLock.writeLock().unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public ServiceComponent addServiceComponent(
      String serviceComponentName) throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      readWriteLock.writeLock().lock();
      try {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Adding a ServiceComponent to Service"
              + ", clusterName=" + cluster.getClusterName()
              + ", clusterId=" + cluster.getClusterId()
              + ", serviceName=" + getName()
              + ", serviceComponentName=" + serviceComponentName);
        }
        if (components.containsKey(serviceComponentName)) {
          throw new AmbariException("Cannot add duplicate ServiceComponent"
              + ", clusterName=" + cluster.getClusterName()
              + ", clusterId=" + cluster.getClusterId()
              + ", serviceName=" + getName()
              + ", serviceComponentName=" + serviceComponentName);
        }
        ServiceComponent component = serviceComponentFactory.createNew(this, serviceComponentName);
        components.put(component.getName(), component);
        return component;
      } finally {
        readWriteLock.writeLock().unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public ServiceComponent getServiceComponent(String componentName)
      throws AmbariException {
    readWriteLock.readLock().lock();
    try {
      if (!components.containsKey(componentName)) {
        throw new ServiceComponentNotFoundException(cluster.getClusterName(),
            getName(), componentName);
      }
      return components.get(componentName);
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  @Override
  public State getDesiredState() {
    readWriteLock.readLock().lock();
    try {
      return serviceDesiredStateEntity.getDesiredState();
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  @Override
  public void setDesiredState(State state) {
    readWriteLock.writeLock().lock();
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting DesiredState of Service" + ", clusterName="
            + cluster.getClusterName() + ", clusterId="
            + cluster.getClusterId() + ", serviceName=" + getName()
            + ", oldDesiredState=" + getDesiredState() + ", newDesiredState="
            + state);
      }
      serviceDesiredStateEntity.setDesiredState(state);
      saveIfPersisted();
    } finally {
      readWriteLock.writeLock().unlock();
    }
  }

  @Override
  public SecurityState getSecurityState() {
    readWriteLock.readLock().lock();
    try {
      return serviceDesiredStateEntity.getSecurityState();
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  @Override
  public void setSecurityState(SecurityState securityState) throws AmbariException {
    if(!securityState.isEndpoint()) {
      throw new AmbariException("The security state must be an endpoint state");
    }

    readWriteLock.writeLock().lock();
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting DesiredSecurityState of Service" + ", clusterName="
            + cluster.getClusterName() + ", clusterId="
            + cluster.getClusterId() + ", serviceName=" + getName()
            + ", oldDesiredSecurityState=" + getSecurityState()
            + ", newDesiredSecurityState=" + securityState);
      }
      serviceDesiredStateEntity.setSecurityState(securityState);
      saveIfPersisted();
    } finally {
      readWriteLock.writeLock().unlock();
    }
  }

  @Override
  public StackId getDesiredStackVersion() {
    readWriteLock.readLock().lock();
    try {
      return gson.fromJson(serviceDesiredStateEntity.getDesiredStackVersion(),
          StackId.class);
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  @Override
  public void setDesiredStackVersion(StackId stackVersion) {
    readWriteLock.writeLock().lock();
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting DesiredStackVersion of Service" + ", clusterName="
            + cluster.getClusterName() + ", clusterId="
            + cluster.getClusterId() + ", serviceName=" + getName()
            + ", oldDesiredStackVersion=" + getDesiredStackVersion()
            + ", newDesiredStackVersion=" + stackVersion);
      }
      serviceDesiredStateEntity.setDesiredStackVersion(gson.toJson(stackVersion));
      saveIfPersisted();
    } finally {
      readWriteLock.writeLock().unlock();
    }
  }

  @Override
  public ServiceResponse convertToResponse() {
    readWriteLock.readLock().lock();
    try {
      ServiceResponse r = new ServiceResponse(cluster.getClusterId(),
          cluster.getClusterName(), getName(),
          getDesiredStackVersion().getStackId(), getDesiredState().toString());

      r.setMaintenanceState(getMaintenanceState().name());
      return r;
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  @Override
  public Cluster getCluster() {
    return cluster;
  }

  @Override
  public void debugDump(StringBuilder sb) {
    readWriteLock.readLock().lock();
    try {
      sb.append("Service={ serviceName=" + getName() + ", clusterName="
          + cluster.getClusterName() + ", clusterId=" + cluster.getClusterId()
          + ", desiredStackVersion=" + getDesiredStackVersion()
          + ", desiredState=" + getDesiredState().toString()
          + ", components=[ ");
      boolean first = true;
      for (ServiceComponent sc : components.values()) {
        if (!first) {
          sb.append(" , ");
        }
        first = false;
        sb.append("\n      ");
        sc.debugDump(sb);
        sb.append(" ");
      }
      sb.append(" ] }");
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isPersisted() {
    // a lock around this internal state variable is not required since we
    // have appropriate locks in the persist() method and this member is
    // only ever false under the condition that the object is new
    return persisted;
  }

  @Override
  public void persist() {
    clusterGlobalLock.writeLock().lock();
    try {
      readWriteLock.writeLock().lock();
      try {
        if (!persisted) {
          persistEntities();
          refresh();
          cluster.refresh();
          persisted = true;

          // publish the service installed event
          StackId stackId = cluster.getDesiredStackVersion();

          ServiceInstalledEvent event = new ServiceInstalledEvent(
              getClusterId(), stackId.getStackName(),
              stackId.getStackVersion(), getName());

          eventPublisher.publish(event);
        } else {
          saveIfPersisted();
        }
      } finally {
        readWriteLock.writeLock().unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Transactional
  protected void persistEntities() {
    long clusterId = cluster.getClusterId();

    ClusterEntity clusterEntity = clusterDAO.findById(clusterId);
    serviceEntity.setClusterEntity(clusterEntity);
    clusterServiceDAO.create(serviceEntity);
    serviceDesiredStateDAO.create(serviceDesiredStateEntity);
    clusterEntity.getClusterServiceEntities().add(serviceEntity);
    clusterDAO.merge(clusterEntity);
    clusterServiceDAO.merge(serviceEntity);
    serviceDesiredStateDAO.merge(serviceDesiredStateEntity);
  }

  @Transactional
  private void saveIfPersisted() {
    if (isPersisted()) {
      clusterServiceDAO.merge(serviceEntity);
      serviceDesiredStateDAO.merge(serviceDesiredStateEntity);
    }
  }

  @Override
  @Transactional
  public void refresh() {
    readWriteLock.writeLock().lock();
    try {
      if (isPersisted()) {
        ClusterServiceEntityPK pk = new ClusterServiceEntityPK();
        pk.setClusterId(getClusterId());
        pk.setServiceName(getName());
        serviceEntity = clusterServiceDAO.findByPK(pk);
        serviceDesiredStateEntity = serviceEntity.getServiceDesiredStateEntity();
        clusterServiceDAO.refresh(serviceEntity);
        serviceDesiredStateDAO.refresh(serviceDesiredStateEntity);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }
  }

  @Override
  public boolean canBeRemoved() {
    clusterGlobalLock.readLock().lock();
    try {
      readWriteLock.readLock().lock();
      try {
        if (!getDesiredState().isRemovableState()) {
          return false;
        }

        for (ServiceComponent sc : components.values()) {
          if (!sc.canBeRemoved()) {
            LOG.warn("Found non removable component when trying to delete service"
                + ", clusterName=" + cluster.getClusterName()
                + ", serviceName=" + getName()
                + ", componentName=" + sc.getName());
            return false;
          }
        }
        return true;
      } finally {
        readWriteLock.readLock().unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  @Transactional
  public void deleteAllComponents() throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      readWriteLock.writeLock().lock();
      try {
        LOG.info("Deleting all components for service"
            + ", clusterName=" + cluster.getClusterName()
            + ", serviceName=" + getName());
        // FIXME check dependencies from meta layer
        for (ServiceComponent component : components.values()) {
          if (!component.canBeRemoved()) {
            throw new AmbariException("Found non removable component when trying to"
                + " delete all components from service"
                + ", clusterName=" + cluster.getClusterName()
                + ", serviceName=" + getName()
                + ", componentName=" + component.getName());
          }
        }

        for (ServiceComponent serviceComponent : components.values()) {
          serviceComponent.delete();
        }

        components.clear();
      } finally {
        readWriteLock.writeLock().unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }
  }

  @Override
  public void deleteServiceComponent(String componentName)
      throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      readWriteLock.writeLock().lock();
      try {
        ServiceComponent component = getServiceComponent(componentName);
        LOG.info("Deleting servicecomponent for cluster"
            + ", clusterName=" + cluster.getClusterName()
            + ", serviceName=" + getName()
            + ", componentName=" + componentName);
        // FIXME check dependencies from meta layer
        if (!component.canBeRemoved()) {
          throw new AmbariException("Could not delete component from cluster"
              + ", clusterName=" + cluster.getClusterName()
              + ", serviceName=" + getName()
              + ", componentName=" + componentName);
        }

        component.delete();
        components.remove(componentName);
      } finally {
        readWriteLock.writeLock().unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }


  }

  @Override
  public boolean isClientOnlyService() {
    return isClientOnlyService;
  }

  @Override
  @Transactional
  public void delete() throws AmbariException {
    clusterGlobalLock.writeLock().lock();
    try {
      readWriteLock.writeLock().lock();
      try {
        deleteAllComponents();

        if (persisted) {
          removeEntities();
          persisted = false;

          // publish the service removed event
          StackId stackId = cluster.getDesiredStackVersion();

          ServiceRemovedEvent event = new ServiceRemovedEvent(getClusterId(),
              stackId.getStackName(), stackId.getStackVersion(), getName());

          eventPublisher.publish(event);
        }
      } finally {
        readWriteLock.writeLock().unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }


  }

  @Transactional
  protected void removeEntities() throws AmbariException {
    ClusterServiceEntityPK pk = new ClusterServiceEntityPK();
    pk.setClusterId(getClusterId());
    pk.setServiceName(getName());

    clusterServiceDAO.removeByPK(pk);
  }

  @Override
  public void setMaintenanceState(MaintenanceState state) {
    readWriteLock.writeLock().lock();
    try {
      serviceDesiredStateEntity.setMaintenanceState(state);
      saveIfPersisted();

      // broadcast the maintenance mode change
      MaintenanceModeEvent event = new MaintenanceModeEvent(state, this);
      eventPublisher.publish(event);
    } finally {
      readWriteLock.writeLock().unlock();
    }
  }

  @Override
  public MaintenanceState getMaintenanceState() {
    return serviceDesiredStateEntity.getMaintenanceState();
  }
}
