package mamba.applicationhistoryservice.metrics.timeline.aggregators;

import mamba.applicationhistoryservice.metrics.timeline.PhoenixHBaseAccessor;
import mamba.applicationhistoryservice.metrics.timeline.discovery.TimelineMetricMetadataManager;
import mamba.applicationhistoryservice.metrics.timeline.query.Condition;
import mamba.applicationhistoryservice.metrics.timeline.query.DefaultCondition;
import mamba.timeline.PostProcessingUtil;
import mamba.timeline.TimelineMetric;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static mamba.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.SERVER_SIDE_TIMESIFT_ADJUSTMENT;
import static mamba.applicationhistoryservice.metrics.timeline.TimelineMetricConfiguration.TIMELINE_METRICS_CLUSTER_AGGREGATOR_INTERPOLATION_ENABLED;
import static mamba.applicationhistoryservice.metrics.timeline.query.PhoenixTransactSQL.GET_METRIC_SQL;
import static mamba.applicationhistoryservice.metrics.timeline.query.PhoenixTransactSQL.METRICS_RECORD_TABLE_NAME;

/**
 * Created by dongbin on 2016/10/10.
 */
public class TimelineMetricClusterAggregatorSecond extends AbstractTimelineAggregator {
    // Aggregator to perform app-level aggregates for host metrics
    private final TimelineMetricAppAggregator appAggregator;
    // 1 minute client side buffering adjustment
    private final Long serverTimeShiftAdjustment;
    private final boolean interpolationEnabled;
    public Long timeSliceIntervalMillis;
    private TimelineMetricReadHelper timelineMetricReadHelper = new TimelineMetricReadHelper(true);


    public TimelineMetricClusterAggregatorSecond(String aggregatorName,
                                                 TimelineMetricMetadataManager metadataManager,
                                                 PhoenixHBaseAccessor hBaseAccessor,
                                                 Configuration metricsConf,
                                                 String checkpointLocation,
                                                 Long sleepIntervalMillis,
                                                 Integer checkpointCutOffMultiplier,
                                                 String aggregatorDisabledParam,
                                                 String tableName,
                                                 String outputTableName,
                                                 Long nativeTimeRangeDelay,
                                                 Long timeSliceInterval) {
        super(aggregatorName, hBaseAccessor, metricsConf, checkpointLocation,
                sleepIntervalMillis, checkpointCutOffMultiplier, aggregatorDisabledParam,
                tableName, outputTableName, nativeTimeRangeDelay);

        appAggregator = new TimelineMetricAppAggregator(metadataManager, metricsConf);
        this.timeSliceIntervalMillis = timeSliceInterval;
        this.serverTimeShiftAdjustment = Long.parseLong(metricsConf.get(SERVER_SIDE_TIMESIFT_ADJUSTMENT, "90000"));
        this.interpolationEnabled = Boolean.parseBoolean(metricsConf.get(TIMELINE_METRICS_CLUSTER_AGGREGATOR_INTERPOLATION_ENABLED, "true"));
    }

    @Override
    protected void aggregate(ResultSet rs, long startTime, long endTime) throws SQLException, IOException {
        // Account for time shift due to client side buffering by shifting the
        // timestamps with the difference between server time and series start time
        // Also, we do not want to look at the shift time period from the end as well since we can interpolate those points
        // that come earlier than the expected, during the next run.
        List<Long[]> timeSlices = getTimeSlices(startTime - serverTimeShiftAdjustment, endTime - serverTimeShiftAdjustment);
        // Initialize app aggregates for host metrics
        appAggregator.init();
        Map<TimelineClusterMetric, MetricClusterAggregate> aggregateClusterMetrics =
                aggregateMetricsFromResultSet(rs, timeSlices);

        LOG.info("Saving " + aggregateClusterMetrics.size() + " metric aggregates.");
        hBaseAccessor.saveClusterAggregateRecords(aggregateClusterMetrics);
        appAggregator.cleanup();
    }

    @Override
    protected Condition prepareMetricQueryCondition(long startTime, long endTime) {
        Condition condition = new DefaultCondition(null, null, null, null, startTime - serverTimeShiftAdjustment,
                endTime, null, null, true);
        condition.setNoLimit();
        condition.setFetchSize(resultsetFetchSize);
        condition.setStatement(String.format(GET_METRIC_SQL,
                getQueryHint(startTime), METRICS_RECORD_TABLE_NAME));
        // Retaining order of the row-key avoids client side merge sort.
        condition.addOrderByColumn("METRIC_NAME");
        condition.addOrderByColumn("HOSTNAME");
        condition.addOrderByColumn("APP_ID");
        condition.addOrderByColumn("SERVER_TIME");
        return condition;
    }

    /**
     * Return time slices to normalize the timeseries data.
     */
    protected List<Long[]> getTimeSlices(long startTime, long endTime) {
        List<Long[]> timeSlices = new ArrayList<Long[]>();
        long sliceStartTime = startTime;
        while (sliceStartTime < endTime) {
            timeSlices.add(new Long[]{sliceStartTime, sliceStartTime + timeSliceIntervalMillis});
            sliceStartTime += timeSliceIntervalMillis;
        }
        return timeSlices;
    }

    private Map<TimelineClusterMetric, MetricClusterAggregate> aggregateMetricsFromResultSet(ResultSet rs, List<Long[]> timeSlices)
            throws SQLException, IOException {
        Map<TimelineClusterMetric, MetricClusterAggregate> aggregateClusterMetrics =
                new HashMap<TimelineClusterMetric, MetricClusterAggregate>();

        TimelineMetric metric = null;
        if (rs.next()) {
            metric = timelineMetricReadHelper.getTimelineMetricFromResultSet(rs);

            // Call slice after all rows for a host are read
            while (rs.next()) {
                TimelineMetric nextMetric = timelineMetricReadHelper.getTimelineMetricFromResultSet(rs);
                // If rows belong to same host combine them before slicing. This
                // avoids issues across rows that belong to same hosts but get
                // counted as coming from different ones.
                if (metric.equalsExceptTime(nextMetric)) {
                    metric.addMetricValues(nextMetric.getMetricValues());
                } else {
                    // Process the current metric
                    processAggregateClusterMetrics(aggregateClusterMetrics, metric, timeSlices);
                    metric = nextMetric;
                }
            }
        }
        // Process last metric
        if (metric != null) {
            processAggregateClusterMetrics(aggregateClusterMetrics, metric, timeSlices);
        }

        // Add app level aggregates to save
        aggregateClusterMetrics.putAll(appAggregator.getAggregateClusterMetrics());
        return aggregateClusterMetrics;
    }

    /**
     * Slice metric values into interval specified by :
     * timeline.metrics.cluster.aggregator.minute.timeslice.interval
     * Normalize value by averaging them within the interval
     */
    protected void processAggregateClusterMetrics(Map<TimelineClusterMetric, MetricClusterAggregate> aggregateClusterMetrics,
                                                  TimelineMetric metric, List<Long[]> timeSlices) {
        // Create time slices
        Map<TimelineClusterMetric, Double> clusterMetrics = sliceFromTimelineMetric(metric, timeSlices);

        if (clusterMetrics != null && !clusterMetrics.isEmpty()) {
            for (Map.Entry<TimelineClusterMetric, Double> clusterMetricEntry :
                    clusterMetrics.entrySet()) {

                TimelineClusterMetric clusterMetric = clusterMetricEntry.getKey();
                Double avgValue = clusterMetricEntry.getValue();

                MetricClusterAggregate aggregate = aggregateClusterMetrics.get(clusterMetric);

                if (aggregate == null) {
                    aggregate = new MetricClusterAggregate(avgValue, 1, null, avgValue, avgValue);
                    aggregateClusterMetrics.put(clusterMetric, aggregate);
                } else {
                    aggregate.updateSum(avgValue);
                    aggregate.updateNumberOfHosts(1);
                    aggregate.updateMax(avgValue);
                    aggregate.updateMin(avgValue);
                }
                // Update app level aggregates
                appAggregator.processTimelineClusterMetric(clusterMetric, metric.getHostName(), avgValue);
            }
        }
    }

    protected Map<TimelineClusterMetric, Double> sliceFromTimelineMetric(
            TimelineMetric timelineMetric, List<Long[]> timeSlices) {

        if (timelineMetric.getMetricValues().isEmpty()) {
            return null;
        }

        Map<TimelineClusterMetric, Double> timelineClusterMetricMap =
                new HashMap<TimelineClusterMetric, Double>();

        Long timeShift = timelineMetric.getTimestamp() - timelineMetric.getStartTime();
        if (timeShift < 0) {
            LOG.debug("Invalid time shift found, possible discrepancy in clocks. " +
                    "timeShift = " + timeShift);
            timeShift = 0l;
        }

        Long prevTimestamp = -1l;
        TimelineClusterMetric prevMetric = null;
        int count = 0;
        double sum = 0.0;

        Map<Long, Double> timeSliceValueMap = new HashMap<>();
        for (Map.Entry<Long, Double> metric : timelineMetric.getMetricValues().entrySet()) {
            // TODO: investigate null values - pre filter
            if (metric.getValue() == null) {
                continue;
            }

            Long timestamp = getSliceTimeForMetric(timeSlices, Long.parseLong(metric.getKey().toString()));
            if (timestamp != -1) {
                // Metric is within desired time range
                TimelineClusterMetric clusterMetric = new TimelineClusterMetric(
                        timelineMetric.getMetricName(),
                        timelineMetric.getAppId(),
                        timelineMetric.getInstanceId(),
                        timestamp,
                        timelineMetric.getType());

                if (prevTimestamp < 0 || timestamp.equals(prevTimestamp)) {
                    Double newValue = metric.getValue();
                    if (newValue > 0.0) {
                        sum += newValue;
                        count++;
                    }
                } else {
                    double metricValue = (count > 0) ? (sum / count) : 0.0;
                    timelineClusterMetricMap.put(prevMetric, metricValue);
                    timeSliceValueMap.put(prevMetric.getTimestamp(), metricValue);
                    sum = metric.getValue();
                    count = sum > 0.0 ? 1 : 0;
                }

                prevTimestamp = timestamp;
                prevMetric = clusterMetric;
            }
        }

        if (prevTimestamp > 0) {
            double metricValue = (count > 0) ? (sum / count) : 0.0;
            timelineClusterMetricMap.put(prevMetric, metricValue);
            timeSliceValueMap.put(prevTimestamp, metricValue);
        }

        if (interpolationEnabled) {
            interpolateMissingPeriods(timelineClusterMetricMap, timelineMetric, timeSlices, timeSliceValueMap);
        }

        return timelineClusterMetricMap;
    }

    private void interpolateMissingPeriods(Map<TimelineClusterMetric, Double> timelineClusterMetricMap,
                                           TimelineMetric timelineMetric,
                                           List<Long[]> timeSlices,
                                           Map<Long, Double> timeSliceValueMap) {


        if (StringUtils.isNotEmpty(timelineMetric.getType()) && "COUNTER".equalsIgnoreCase(timelineMetric.getType())) {
            //For Counter Based metrics, ok to do interpolation and extrapolation

            List<Long> requiredTimestamps = new ArrayList<>();
            for (Long[] timeSlice : timeSlices) {
                if (!timeSliceValueMap.containsKey(timeSlice[1])) {
                    requiredTimestamps.add(timeSlice[1]);
                }
            }
            Map<Long, Double> interpolatedValuesMap = PostProcessingUtil.interpolate(timelineMetric.getMetricValues(), requiredTimestamps);

            if (interpolatedValuesMap != null) {
                for (Map.Entry<Long, Double> entry : interpolatedValuesMap.entrySet()) {
                    Double interpolatedValue = entry.getValue();

                    if (interpolatedValue != null) {
                        TimelineClusterMetric clusterMetric = new TimelineClusterMetric(
                                timelineMetric.getMetricName(),
                                timelineMetric.getAppId(),
                                timelineMetric.getInstanceId(),
                                entry.getKey(),
                                timelineMetric.getType());

                        timelineClusterMetricMap.put(clusterMetric, interpolatedValue);
                    } else {
                        LOG.debug("Cannot compute interpolated value, hence skipping.");
                    }
                }
            }
        } else {
            //For other metrics, ok to do only interpolation

            Double defaultNextSeenValue = null;
            if (MapUtils.isEmpty(timeSliceValueMap) && MapUtils.isNotEmpty(timelineMetric.getMetricValues())) {
                //If no value was found within the start_time based slices, but the metric has value in the server_time range,
                // use that.

                LOG.debug("No value found within range for metric : " + timelineMetric.getMetricName());
                Map.Entry<Long, Double> firstEntry = timelineMetric.getMetricValues().firstEntry();
                defaultNextSeenValue = firstEntry.getValue();
                LOG.debug("Found a data point outside timeslice range: " + new Date(firstEntry.getKey()) + ": " + defaultNextSeenValue);
            }

            for (int sliceNum = 0; sliceNum < timeSlices.size(); sliceNum++) {
                Long[] timeSlice = timeSlices.get(sliceNum);

                if (!timeSliceValueMap.containsKey(timeSlice[1])) {
                    LOG.debug("Found an empty slice : " + new Date(timeSlice[0]) + ", " + new Date(timeSlice[1]));

                    Double lastSeenValue = null;
                    int index = sliceNum - 1;
                    Long[] prevTimeSlice = null;
                    while (lastSeenValue == null && index >= 0) {
                        prevTimeSlice = timeSlices.get(index--);
                        lastSeenValue = timeSliceValueMap.get(prevTimeSlice[1]);
                    }

                    Double nextSeenValue = null;
                    index = sliceNum + 1;
                    Long[] nextTimeSlice = null;
                    while (nextSeenValue == null && index < timeSlices.size()) {
                        nextTimeSlice = timeSlices.get(index++);
                        nextSeenValue = timeSliceValueMap.get(nextTimeSlice[1]);
                    }

                    if (nextSeenValue == null) {
                        nextSeenValue = defaultNextSeenValue;
                    }

                    Double interpolatedValue = PostProcessingUtil.interpolate(timeSlice[1],
                            (prevTimeSlice != null ? prevTimeSlice[1] : null), lastSeenValue,
                            (nextTimeSlice != null ? nextTimeSlice[1] : null), nextSeenValue);

                    if (interpolatedValue != null) {
                        TimelineClusterMetric clusterMetric = new TimelineClusterMetric(
                                timelineMetric.getMetricName(),
                                timelineMetric.getAppId(),
                                timelineMetric.getInstanceId(),
                                timeSlice[1],
                                timelineMetric.getType());

                        LOG.debug("Interpolated value : " + interpolatedValue);
                        timelineClusterMetricMap.put(clusterMetric, interpolatedValue);
                    } else {
                        LOG.debug("Cannot compute interpolated value, hence skipping.");
                    }
                }
            }
        }
    }

    /**
     * Return end of the time slice into which the metric fits.
     */
    private Long getSliceTimeForMetric(List<Long[]> timeSlices, Long timestamp) {
        for (Long[] timeSlice : timeSlices) {
            if (timestamp > timeSlice[0] && timestamp <= timeSlice[1]) {
                return timeSlice[1];
            }
        }
        return -1l;
    }

}
