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

package org.apache.ambari.server.controller.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ambari.server.controller.metrics.MetricReportingAdapter;
import org.apache.ambari.server.controller.spi.PropertyProvider;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;

/**
 *  Abstract property provider implementation.
 */
public abstract class AbstractPropertyProvider extends BaseProvider implements PropertyProvider {

  /**
   * The property/metric information for this provider keyed by component name / property id.
   */
  private final Map<String, Map<String, PropertyInfo>> componentMetrics;

  /**
   * Regular expression for checking a property id for a metric argument with methods.
   * (e.g. metrics/yarn/Queue$1.replaceAll(\",q(\\d+)=\",\"/\")/AppsRunning)
   */
  private static final Pattern CHECK_FOR_METRIC_ARGUMENT_METHODS_REGEX = Pattern.compile("(\\$\\d\\.[^\\$]+\\))+");

  /**
   * Regular expression for extracting the methods from a metric argument.
   * (e.g. $1.replaceAll(\",q(\\d+)=\",\"/\"))
   */
  private static final Pattern FIND_ARGUMENT_METHOD_REGEX = Pattern.compile(".\\w+\\(.*?\\)");

  /**
   * Regular expression for extracting the arguments for methods from a metric argument.
   * Only strings and integers are supported.
   */
  private static final Pattern FIND_ARGUMENT_METHOD_ARGUMENTS_REGEX = Pattern.compile("\".*?\"|[0-9]+");

  /**
   * Supported any regex inside ()
   */
  private static final String FIND_REGEX_IN_METRIC_REGEX = "\\([^)]+\\)";

  private static final DecimalFormat decimalFormat = new DecimalFormat("#.00");

  // ----- Constructors ------------------------------------------------------

  /**
   * Construct a provider.
   *
   * @param componentMetrics map of metrics for this provider
   */
  public AbstractPropertyProvider(Map<String, Map<String, PropertyInfo>> componentMetrics) {
    super(PropertyHelper.getPropertyIds(componentMetrics));
    this.componentMetrics = componentMetrics;
  }


  // ----- accessors ---------------------------------------------------------

  /**
   * Get the map of metrics for this provider.
   *
   * @return the map of metric / property info.
   */
  public Map<String, Map<String, PropertyInfo>> getComponentMetrics() {
    return componentMetrics;
  }


  // ----- helper methods ----------------------------------------------------

  /**
   * Get a map of metric / property info based on the given component name and property id.
   * Note that the property id may map to multiple metrics if the property id is a category.
   *
   * @param componentName  the component name
   * @param propertyId     the property id; may be a category
   *
   * @return a map of metrics
   */
  protected Map<String, PropertyInfo> getPropertyInfoMap(String componentName, String propertyId) {
    Map<String, PropertyInfo> propertyInfoMap = new HashMap<String, PropertyInfo>();

    getPropertyInfoMap(componentName, propertyId, propertyInfoMap);

    return propertyInfoMap;
  }

  protected void getPropertyInfoMap(String componentName, String propertyId, Map<String, PropertyInfo> propertyInfoMap) {
    Map<String, PropertyInfo> componentMetricMap = getComponentMetrics().get(componentName);

    propertyInfoMap.clear();

    if (componentMetricMap == null) {
      return;
    }

    PropertyInfo propertyInfo = componentMetricMap.get(propertyId);
    if (propertyInfo != null) {
      propertyInfoMap.put(propertyId, propertyInfo);
      return;
    }

    Map.Entry<String, Pattern> regexEntry = getRegexEntry(propertyId);

    if (regexEntry != null) {
      String regexKey = regexEntry.getKey();
      propertyInfo = componentMetricMap.get(regexKey);
      if (propertyInfo != null) {
        propertyInfoMap.put(regexKey, propertyInfo);
        return;
      }
    }

    if (!propertyId.endsWith("/")){
      propertyId += "/";
    }

    for (Map.Entry<String, PropertyInfo> entry : componentMetricMap.entrySet()) {
      if (entry.getKey().startsWith(propertyId)) {
        String key = entry.getKey();
        propertyInfoMap.put(key, entry.getValue());
      }
    }

    if (regexEntry != null) {
      // in the event that a category is being requested, the pattern must
      // match all child properties; append \S* for this
      String regexPattern = regexEntry.getValue().pattern();
      regexPattern += "(\\S*)";

      for (Map.Entry<String, PropertyInfo> entry : componentMetricMap.entrySet()) {
        if (entry.getKey().matches(regexPattern)) {
          propertyInfoMap.put(entry.getKey(), entry.getValue());
        }
      }
    }

    return;
  }

  /**
   * Substitute the given value into the argument in the given property id.  If there are methods attached
   * to the argument then execute them for the given value.
   *
   * @param propertyId  the property id
   * @param argName     the argument name
   * @param val         the value to substitute
   *
   * @return the modified property id
   */
  protected static String substituteArgument(String propertyId, String argName, String val) {

    // find the argument in the property id
    int argStart = propertyId.indexOf(argName);

    if (argStart > -1) {
      // get the string segment starting with the given argument
      String argSegment = propertyId.substring(argStart);

      // check to see if there are any methods attached to the argument
      Matcher matcher = CHECK_FOR_METRIC_ARGUMENT_METHODS_REGEX.matcher(argSegment);
      if (matcher.find()) {

        // expand the argument name to include its methods
        argName = argSegment.substring(matcher.start(), matcher.end());

        // for each method attached to the argument ...
        matcher = FIND_ARGUMENT_METHOD_REGEX.matcher(argName);
        while (matcher.find()) {
          // find the end of the method
          int openParenIndex  = argName.indexOf('(', matcher.start());
          int closeParenIndex = indexOfClosingParenthesis(argName, openParenIndex);

          String methodName = argName.substring(matcher.start() + 1, openParenIndex);
          String args       = argName.substring(openParenIndex + 1, closeParenIndex);

          List<Object>   argList    = new LinkedList<Object>();
          List<Class<?>> paramTypes = new LinkedList<Class<?>>();

          // for each argument of the method ...
          Matcher argMatcher = FIND_ARGUMENT_METHOD_ARGUMENTS_REGEX.matcher(args);
          while (argMatcher.find()) {
            addArgument(args, argMatcher.start(), argMatcher.end(), argList, paramTypes);
          }

          try {
            val = invokeArgumentMethod(val, methodName, argList, paramTypes);
          } catch (Exception e) {
            throw new IllegalArgumentException("Can't apply method " + methodName + " for argument " +
                argName + " in " + propertyId, e);
          }
        }
      }
      // Do the substitution
      return propertyId.replace(argName, val);
    }
    throw new IllegalArgumentException("Can't substitute " + val + "  for argument " + argName + " in " + propertyId);
  }

  /**
   * Find the index of the closing parenthesis in the given string.
   */
  private static int indexOfClosingParenthesis(String s, int index) {
    int depth  = 0;
    int length = s.length();

    while (index < length) {
      char c = s.charAt(index++);
      if (c == '(') {
        ++depth;
      } else if (c == ')') {
        if (--depth ==0 ){
         return index;
        }
      }
    }
    return -1;
  }

  /**
   * Extract an argument from the given string and add it to the given arg and param type collections.
   */
  private static void addArgument(String args, int start, int end, List<Object> argList, List<Class<?>> paramTypes) {
    String arg = args.substring(start, end);

    // only supports strings and integers
    if (arg.contains("\"")) {
      argList.add(arg.substring(1, arg.length() -1));
      paramTypes.add(String.class);
    } else {
      Integer number = Integer.parseInt(arg);
      argList.add(number);
      paramTypes.add(Integer.TYPE);
    }
  }

  /**
   * Invoke a method on the given argument string with the given parameters.
   */
  private static String invokeArgumentMethod(String argValue, String methodName, List<Object> argList,
                                             List<Class<?>> paramTypes)
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    // invoke the method through reflection
    Method method = String.class.getMethod(methodName, paramTypes.toArray(new Class<?>[paramTypes.size()]));

    return (String) method.invoke(argValue, argList.toArray(new Object[argList.size()]));
  }

  /**
   * Adds to the componentMetricMap a specific(not regexp)
   * metric for the propertyId
   *
   * @param componentMetricMap
   * @param propertyId
   */
  protected void updateComponentMetricMap(
    Map<String, PropertyInfo> componentMetricMap, String propertyId) {

    String regexKey = null;
    Map.Entry<String, Pattern> regexEntry = getRegexEntry(propertyId);
    if (null != regexEntry) {
      regexKey = regexEntry.getKey();
    }

    if (!componentMetricMap.containsKey(propertyId) && regexKey != null
        && !regexKey.equals(propertyId)) {

      PropertyInfo propertyInfo = componentMetricMap.get(regexKey);
      if (propertyInfo != null) {
        List<String> regexGroups = getRegexGroups(regexKey, propertyId);
        String key = propertyInfo.getPropertyId();
        for (String regexGroup : regexGroups) {
          regexGroup = regexGroup.replace("/", ".");
          key = key.replaceFirst(FIND_REGEX_IN_METRIC_REGEX, regexGroup);
        }
        componentMetricMap.put(propertyId, new PropertyInfo(key,
          propertyInfo.isTemporal(), propertyInfo.isPointInTime()));
      }

    }
  }

  protected PropertyInfo updatePropertyInfo(String propertyKey, String id, PropertyInfo propertyInfo) {
    List<String> regexGroups = getRegexGroups(propertyKey, id);
    String propertyId = propertyInfo.getPropertyId();
    if(propertyId != null) {
      for (String regexGroup : regexGroups) {
        regexGroup = regexGroup.replace("/", ".");
        propertyId = propertyId.replaceFirst(FIND_REGEX_IN_METRIC_REGEX, regexGroup);
      }
    }
    return new PropertyInfo(propertyId, propertyInfo.isTemporal(), propertyInfo.isPointInTime());
  }

  /**
   * Verify that the component metrics contains the property id.
   * @param componentName Name of the component
   * @param propertyId Property Id
   * @return true/false
   */
  protected boolean isSupportedPropertyId(String componentName, String propertyId) {
    Map<String, PropertyInfo> componentMetricMap = componentMetrics.get(componentName);

    return componentMetricMap != null
      && (componentMetricMap.containsKey(propertyId) || checkPropertyCategory(propertyId));
  }

  /**
   * Verify if the property category is supported
   */
  protected boolean checkPropertyCategory(String propertyId) {
    Set<String> categoryIds = getCategoryIds();
    // Support query by category
    if (categoryIds.contains(propertyId)) {
      return true;
    }

    String category = PropertyHelper.getPropertyCategory(propertyId);
    while (category != null) {
      if(categoryIds.contains(category)) {
        return true;
      }
      category = PropertyHelper.getPropertyCategory(category);
    }
    return false;
  }

  // Normalize percent values: Copied over from Ganglia Metric
  private static Number[][] getGangliaLikeDatapoints(TimelineMetric metric) {
    MetricReportingAdapter rpt = new MetricReportingAdapter(metric);

    //TODO Don't we always need to downsample?
    return rpt.reportMetricData(metric);
  }

  /**
   * Get value from the given metric.
   *
   * @param metric      the metric
   * @param isTemporal  indicates whether or not this a temporal metric
   *
   * @return a range of temporal data or a point in time value if not temporal
   */
  protected static Object getValue(TimelineMetric metric, boolean isTemporal) {
    Number[][] dataPoints = getGangliaLikeDatapoints(metric);

    int length = dataPoints.length;
    if (isTemporal) {
      return length > 0 ? dataPoints : null;
    } else {
      // return the value of the last data point
      return length > 0 ? dataPoints[length - 1][0] : 0;
    }
  }

}
