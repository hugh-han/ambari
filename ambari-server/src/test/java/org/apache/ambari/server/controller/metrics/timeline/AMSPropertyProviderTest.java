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
package org.apache.ambari.server.controller.metrics.timeline;

import org.apache.ambari.server.configuration.ComponentSSLConfiguration;
import org.apache.ambari.server.controller.internal.PropertyInfo;
import org.apache.ambari.server.controller.internal.ResourceImpl;
import org.apache.ambari.server.controller.internal.TemporalInfoImpl;
import org.apache.ambari.server.controller.metrics.MetricHostProvider;
import org.apache.ambari.server.controller.metrics.ganglia.TestStreamProvider;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.TemporalInfo;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.http.client.utils.URIBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.ambari.server.controller.metrics.MetricsServiceProvider.MetricsService;
import static org.easymock.EasyMock.createNiceMock;
import static org.mockito.Mockito.mock;

public class AMSPropertyProviderTest {
  private static final String PROPERTY_ID1 = PropertyHelper.getPropertyId("metrics/cpu", "cpu_user");
  private static final String PROPERTY_ID2 = PropertyHelper.getPropertyId("metrics/memory", "mem_free");
  private static final String CLUSTER_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("HostRoles", "cluster_name");
  private static final String HOST_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("HostRoles", "host_name");
  private static final String COMPONENT_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("HostRoles", "component_name");

  private static final String FILE_PATH_PREFIX = "ams" + File.separator;
  private static final String SINGLE_HOST_METRICS_FILE_PATH = FILE_PATH_PREFIX + "single_host_metric.json";
  private static final String MULTIPLE_HOST_METRICS_FILE_PATH = FILE_PATH_PREFIX + "multiple_host_metrics.json";
  private static final String SINGLE_COMPONENT_METRICS_FILE_PATH = FILE_PATH_PREFIX + "single_component_metrics.json";
  private static final String MULTIPLE_COMPONENT_METRICS_FILE_PATH = FILE_PATH_PREFIX + "multiple_component_metrics.json";
  private static final String CLUSTER_REPORT_METRICS_FILE_PATH = FILE_PATH_PREFIX + "cluster_report_metrics.json";
  private static final String MULTIPLE_COMPONENT_REGEXP_METRICS_FILE_PATH = FILE_PATH_PREFIX + "multiple_component_regexp_metrics.json";
  private static final String EMBEDDED_METRICS_FILE_PATH = FILE_PATH_PREFIX + "embedded_host_metric.json";

  @Test
  public void testPopulateResourcesForSingleHostMetric() throws Exception {
    TestStreamProvider streamProvider = new TestStreamProvider(SINGLE_HOST_METRICS_FILE_PATH);
    TestMetricHostProvider metricHostProvider = new TestMetricHostProvider();
    ComponentSSLConfiguration sslConfiguration = mock(ComponentSSLConfiguration.class);

    Map<String, Map<String, PropertyInfo>> propertyIds = PropertyHelper.getMetricPropertyIds(Resource.Type.Host);
    AMSPropertyProvider propertyProvider = new AMSHostPropertyProvider(
      propertyIds,
      streamProvider,
      sslConfiguration,
      metricHostProvider,
      CLUSTER_NAME_PROPERTY_ID,
      HOST_NAME_PROPERTY_ID
    );

    Resource resource = new ResourceImpl(Resource.Type.Host);
    resource.setProperty(HOST_NAME_PROPERTY_ID, "h1");
    Map<String, TemporalInfo> temporalInfoMap = new HashMap<String, TemporalInfo>();
    temporalInfoMap.put(PROPERTY_ID1, new TemporalInfoImpl(1416445244701L, 1416445244901L, 1L));
    Request request = PropertyHelper.getReadRequest(Collections.singleton(PROPERTY_ID1), temporalInfoMap);
    Set<Resource> resources =
      propertyProvider.populateResources(Collections.singleton(resource), request, null);
    Assert.assertEquals(1, resources.size());
    Resource res = resources.iterator().next();
    Map<String, Object> properties = PropertyHelper.getProperties(resources.iterator().next());
    Assert.assertNotNull(properties);
    URIBuilder uriBuilder = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder.addParameter("metricNames", "cpu_user");
    uriBuilder.addParameter("hostname", "h1");
    uriBuilder.addParameter("appId", "HOST");
    uriBuilder.addParameter("startTime", "1416445244701");
    uriBuilder.addParameter("endTime", "1416445244901");
    Assert.assertEquals(uriBuilder.toString(), streamProvider.getLastSpec());
    Number[][] val = (Number[][]) res.getPropertyValue(PROPERTY_ID1);
    Assert.assertEquals(111, val.length);
  }

  @Test
  public void testPopulateResourcesForSingleHostMetricPointInTime() throws
    Exception {

    // given
    TestStreamProvider streamProvider = new TestStreamProvider(SINGLE_HOST_METRICS_FILE_PATH);
    TestMetricHostProvider metricHostProvider = new TestMetricHostProvider();
    ComponentSSLConfiguration sslConfiguration = mock(ComponentSSLConfiguration.class);
    Map<String, Map<String, PropertyInfo>> propertyIds = PropertyHelper.getMetricPropertyIds(Resource.Type.Host);
    AMSPropertyProvider propertyProvider = new AMSHostPropertyProvider(
      propertyIds,
      streamProvider,
      sslConfiguration,
      metricHostProvider,
      CLUSTER_NAME_PROPERTY_ID,
      HOST_NAME_PROPERTY_ID
    );

    Resource resource = new ResourceImpl(Resource.Type.Host);
    resource.setProperty(HOST_NAME_PROPERTY_ID, "h1");
    Map<String, TemporalInfo> temporalInfoMap = Collections.emptyMap();
    Request request = PropertyHelper.getReadRequest(Collections.singleton
      (PROPERTY_ID1), temporalInfoMap);
    System.out.println(request);

    // when
    Set<Resource> resources =
      propertyProvider.populateResources(Collections.singleton(resource), request, null);

    // then
    Assert.assertEquals(1, resources.size());
    Resource res = resources.iterator().next();
    Map<String, Object> properties = PropertyHelper.getProperties(res);
    Assert.assertNotNull(properties);
    URIBuilder uriBuilder = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder.addParameter("metricNames", "cpu_user");
    uriBuilder.addParameter("hostname", "h1");
    uriBuilder.addParameter("appId", "HOST");
    Assert.assertEquals(uriBuilder.toString(), streamProvider.getLastSpec());
    Double val = (Double) res.getPropertyValue(PROPERTY_ID1);
    Assert.assertEquals(41.088, val, 0.001);
  }

  @Test
  public void testPopulateResourcesForMultipleHostMetricscPointInTime() throws Exception {
    TestStreamProvider streamProvider = new TestStreamProvider(MULTIPLE_HOST_METRICS_FILE_PATH);
    TestMetricHostProvider metricHostProvider = new TestMetricHostProvider();
    ComponentSSLConfiguration sslConfiguration = mock(ComponentSSLConfiguration.class);

    Map<String, Map<String, PropertyInfo>> propertyIds = PropertyHelper.getMetricPropertyIds(Resource.Type.Host);
    AMSPropertyProvider propertyProvider = new AMSHostPropertyProvider(
      propertyIds,
      streamProvider,
      sslConfiguration,
      metricHostProvider,
      CLUSTER_NAME_PROPERTY_ID,
      HOST_NAME_PROPERTY_ID
    );

    Resource resource = new ResourceImpl(Resource.Type.Host);
    resource.setProperty(HOST_NAME_PROPERTY_ID, "h1");
    Map<String, TemporalInfo> temporalInfoMap = Collections.emptyMap();
    Request request = PropertyHelper.getReadRequest(
      new HashSet<String>() {{ add(PROPERTY_ID1); add(PROPERTY_ID2); }}, temporalInfoMap);
    Set<Resource> resources =
      propertyProvider.populateResources(Collections.singleton(resource), request, null);
    Assert.assertEquals(1, resources.size());
    Resource res = resources.iterator().next();
    Map<String, Object> properties = PropertyHelper.getProperties(resources.iterator().next());
    Assert.assertNotNull(properties);
    URIBuilder uriBuilder = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder.addParameter("metricNames", "cpu_user,mem_free");
    uriBuilder.addParameter("hostname", "h1");
    uriBuilder.addParameter("appId", "HOST");

    URIBuilder uriBuilder2 = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder2.addParameter("metricNames", "mem_free,cpu_user");
    uriBuilder2.addParameter("hostname", "h1");
    uriBuilder2.addParameter("appId", "HOST");
    System.out.println(streamProvider.getLastSpec());
    Assert.assertTrue(uriBuilder.toString().equals(streamProvider.getLastSpec())
        || uriBuilder2.toString().equals(streamProvider.getLastSpec()));
    Double val1 = (Double) res.getPropertyValue(PROPERTY_ID1);
    Assert.assertEquals(41.088, val1, 0.001);
    Double val2 = (Double)res.getPropertyValue(PROPERTY_ID2);
    Assert.assertEquals(2.47025664E8, val2, 0.1);
  }


  @Test
  public void testPopulateResourcesForMultipleHostMetrics() throws Exception {
    TestStreamProvider streamProvider = new TestStreamProvider(MULTIPLE_HOST_METRICS_FILE_PATH);
    TestMetricHostProvider metricHostProvider = new TestMetricHostProvider();
    ComponentSSLConfiguration sslConfiguration = mock(ComponentSSLConfiguration.class);

    Map<String, Map<String, PropertyInfo>> propertyIds = PropertyHelper.getMetricPropertyIds(Resource.Type.Host);
    AMSPropertyProvider propertyProvider = new AMSHostPropertyProvider(
      propertyIds,
      streamProvider,
      sslConfiguration,
      metricHostProvider,
      CLUSTER_NAME_PROPERTY_ID,
      HOST_NAME_PROPERTY_ID
    );

    Resource resource = new ResourceImpl(Resource.Type.Host);
    resource.setProperty(HOST_NAME_PROPERTY_ID, "h1");
    Map<String, TemporalInfo> temporalInfoMap = new HashMap<String, TemporalInfo>();
    temporalInfoMap.put(PROPERTY_ID1, new TemporalInfoImpl(1416445244701L, 1416445244901L, 1L));
    temporalInfoMap.put(PROPERTY_ID2, new TemporalInfoImpl(1416445244701L, 1416445244901L, 1L));
    Request request = PropertyHelper.getReadRequest(
      new HashSet<String>() {{ add(PROPERTY_ID1); add(PROPERTY_ID2); }}, temporalInfoMap);
    Set<Resource> resources =
      propertyProvider.populateResources(Collections.singleton(resource), request, null);
    Assert.assertEquals(1, resources.size());
    Resource res = resources.iterator().next();
    Map<String, Object> properties = PropertyHelper.getProperties(resources.iterator().next());
    Assert.assertNotNull(properties);
    URIBuilder uriBuilder = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder.addParameter("metricNames", "cpu_user,mem_free");
    uriBuilder.addParameter("hostname", "h1");
    uriBuilder.addParameter("appId", "HOST");
    uriBuilder.addParameter("startTime", "1416445244701");
    uriBuilder.addParameter("endTime", "1416445244901");

    URIBuilder uriBuilder2 = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder2.addParameter("metricNames", "mem_free,cpu_user");
    uriBuilder2.addParameter("hostname", "h1");
    uriBuilder2.addParameter("appId", "HOST");
    uriBuilder2.addParameter("startTime", "1416445244701");
    uriBuilder2.addParameter("endTime", "1416445244901");
    Assert.assertTrue(uriBuilder.toString().equals(streamProvider.getLastSpec())
      || uriBuilder2.toString().equals(streamProvider.getLastSpec()));
    Number[][] val = (Number[][]) res.getPropertyValue(PROPERTY_ID1);
    Assert.assertEquals(111, val.length);
    val = (Number[][]) res.getPropertyValue(PROPERTY_ID2);
    Assert.assertEquals(86, val.length);
  }


  @Test
  public void testPopulateResourcesForRegexpMetrics() throws Exception {
    TestStreamProvider streamProvider = new TestStreamProvider(MULTIPLE_COMPONENT_REGEXP_METRICS_FILE_PATH);
    TestMetricHostProvider metricHostProvider = new TestMetricHostProvider();
    ComponentSSLConfiguration sslConfiguration = mock(ComponentSSLConfiguration.class);

    Map<String, Map<String, PropertyInfo>> propertyIds =
        new HashMap<String, Map<String, PropertyInfo>>() {{
      put("RESOURCEMANAGER", new HashMap<String, PropertyInfo>() {{
        put("metrics/yarn/Queue/$1.replaceAll(\"([.])\",\"/\")/AvailableMB",
            new PropertyInfo("yarn.QueueMetrics.(.+).AvailableMB", true, false));
      }});
    }};

    AMSPropertyProvider propertyProvider = new AMSComponentPropertyProvider(
        propertyIds,
        streamProvider,
        sslConfiguration,
        metricHostProvider,
        CLUSTER_NAME_PROPERTY_ID,
        COMPONENT_NAME_PROPERTY_ID
    );


    String propertyId1 = "metrics/yarn/Queue/root/AvailableMB";
    Resource resource = new ResourceImpl(Resource.Type.Component);
    resource.setProperty(HOST_NAME_PROPERTY_ID, "h1");
    resource.setProperty(COMPONENT_NAME_PROPERTY_ID, "RESOURCEMANAGER");
    Map<String, TemporalInfo> temporalInfoMap = new HashMap<String, TemporalInfo>();
    temporalInfoMap.put(propertyId1, new TemporalInfoImpl(1416528819369L, 1416528819569L, 1L));
    Request request = PropertyHelper.getReadRequest(
        Collections.singleton(propertyId1), temporalInfoMap);
    Set<Resource> resources =
        propertyProvider.populateResources(Collections.singleton(resource), request, null);
    Assert.assertEquals(1, resources.size());
    Resource res = resources.iterator().next();
    Map<String, Object> properties = PropertyHelper.getProperties(resources.iterator().next());
    Assert.assertNotNull(properties);
    URIBuilder uriBuilder = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder.addParameter("metricNames", "yarn.QueueMetrics.%.AvailableMB");
    uriBuilder.addParameter("appId", "RESOURCEMANAGER");
    uriBuilder.addParameter("startTime", "1416528819369");
    uriBuilder.addParameter("endTime", "1416528819569");
    Assert.assertEquals(uriBuilder.toString(), streamProvider.getLastSpec());
    Number[][] val = (Number[][]) res.getPropertyValue("metrics/yarn/Queue/Queue=root/AvailableMB");
    Assert.assertEquals(238, val.length);
  }

  @Test
  public void testPopulateResourcesForSingleComponentMetric() throws Exception {
    TestStreamProvider streamProvider = new TestStreamProvider(SINGLE_COMPONENT_METRICS_FILE_PATH);
    TestMetricHostProvider metricHostProvider = new TestMetricHostProvider();
    ComponentSSLConfiguration sslConfiguration = mock(ComponentSSLConfiguration.class);

    Map<String, Map<String, PropertyInfo>> propertyIds =
      PropertyHelper.getMetricPropertyIds(Resource.Type.Component);

    AMSPropertyProvider propertyProvider = new AMSComponentPropertyProvider(
      propertyIds,
      streamProvider,
      sslConfiguration,
      metricHostProvider,
      CLUSTER_NAME_PROPERTY_ID,
      COMPONENT_NAME_PROPERTY_ID
    );

    String propertyId = PropertyHelper.getPropertyId("metrics/rpc", "RpcQueueTime_avg_time");
    Resource resource = new ResourceImpl(Resource.Type.Component);
    resource.setProperty(HOST_NAME_PROPERTY_ID, "h1");
    resource.setProperty(COMPONENT_NAME_PROPERTY_ID, "NAMENODE");
    Map<String, TemporalInfo> temporalInfoMap = new HashMap<String, TemporalInfo>();
    temporalInfoMap.put(propertyId, new TemporalInfoImpl(1416528819369L, 1416528819569L, 1L));
    Request request = PropertyHelper.getReadRequest(
      Collections.singleton(propertyId), temporalInfoMap);
    Set<Resource> resources =
      propertyProvider.populateResources(Collections.singleton(resource), request, null);
    Assert.assertEquals(1, resources.size());
    Resource res = resources.iterator().next();
    Map<String, Object> properties = PropertyHelper.getProperties(resources.iterator().next());
    Assert.assertNotNull(properties);
    URIBuilder uriBuilder = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder.addParameter("metricNames", "rpc.rpc.RpcQueueTimeAvgTime");
    uriBuilder.addParameter("appId", "NAMENODE");
    uriBuilder.addParameter("startTime", "1416528819369");
    uriBuilder.addParameter("endTime", "1416528819569");
    Assert.assertEquals(uriBuilder.toString(), streamProvider.getLastSpec());
    Number[][] val = (Number[][]) res.getPropertyValue(propertyId);
    Assert.assertEquals(238, val.length);
  }

  @Test
  public void testPopulateMetricsForEmbeddedHBase() throws Exception {
    TestStreamProvider streamProvider = new TestStreamProvider(EMBEDDED_METRICS_FILE_PATH);
    TestMetricHostProvider metricHostProvider = new TestMetricHostProvider();
    ComponentSSLConfiguration sslConfiguration = mock(ComponentSSLConfiguration.class);

    Map<String, Map<String, PropertyInfo>> propertyIds =
      PropertyHelper.getMetricPropertyIds(Resource.Type.Component);

    AMSPropertyProvider propertyProvider = new AMSComponentPropertyProvider(
      propertyIds,
      streamProvider,
      sslConfiguration,
      metricHostProvider,
      CLUSTER_NAME_PROPERTY_ID,
      COMPONENT_NAME_PROPERTY_ID
    );

    String propertyId = PropertyHelper.getPropertyId("metrics/hbase/regionserver", "requests");
    Resource resource = new ResourceImpl(Resource.Type.Component);
    resource.setProperty(HOST_NAME_PROPERTY_ID, "h1");
    resource.setProperty(COMPONENT_NAME_PROPERTY_ID, "METRICS_COLLECTOR");
    Map<String, TemporalInfo> temporalInfoMap = new HashMap<String, TemporalInfo>();
    temporalInfoMap.put(propertyId, new TemporalInfoImpl(1421694000L, 1421697600L, 1L));
    Request request = PropertyHelper.getReadRequest(
      Collections.singleton(propertyId), temporalInfoMap);
    Set<Resource> resources =
      propertyProvider.populateResources(Collections.singleton(resource), request, null);
    Assert.assertEquals(1, resources.size());
    Resource res = resources.iterator().next();
    Map<String, Object> properties = PropertyHelper.getProperties(resources.iterator().next());
    Assert.assertNotNull(properties);
    URIBuilder uriBuilder = AMSPropertyProvider.getAMSUriBuilder("localhost", 8188);
    uriBuilder.addParameter("metricNames", "regionserver.Server.totalRequestCount");
    uriBuilder.addParameter("appId", "AMS-HBASE");
    uriBuilder.addParameter("startTime", "1421694000");
    uriBuilder.addParameter("endTime", "1421697600");
    Assert.assertEquals(uriBuilder.toString(), streamProvider.getLastSpec());
    Number[][] val = (Number[][]) res.getPropertyValue(propertyId);
    Assert.assertEquals(188, val.length);
  }

  public static class TestMetricHostProvider implements MetricHostProvider {

    @Override
    public String getCollectorHostName(String clusterName, MetricsService service)
      throws SystemException {
      return "localhost";
    }

    @Override
    public String getHostName(String clusterName, String componentName) throws SystemException {
      return "h1";
    }

    @Override
    public String getCollectorPortName(String clusterName, MetricsService service) throws SystemException {
      return "8188";
    }

    @Override
    public boolean isCollectorHostLive(String clusterName, MetricsService service) throws SystemException {
      return true;
    }

    @Override
    public boolean isCollectorComponentLive(String clusterName, MetricsService service) throws SystemException {
      return true;
    }
  }
}
