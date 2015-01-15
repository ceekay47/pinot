package com.linkedin.pinot.controller.validation;

import com.linkedin.pinot.common.metrics.ValidationMetrics;
import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.common.utils.CommonConstants;
import com.linkedin.pinot.controller.ControllerConf;
import com.linkedin.pinot.controller.helix.core.PinotHelixResourceManager;
import com.linkedin.pinot.controller.helix.core.utils.PinotHelixUtils;
import com.linkedin.pinot.core.segment.index.SegmentMetadataImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.helix.AccessOption;
import org.apache.helix.ZNRecord;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Manages the segment validation metrics, to ensure that all offline segments are contiguous (no missing segments) and
 * that the offline push delay isn't too high.
 *
 * @author Dhaval Patel<dpatel@linkedin.com>
 * @author jfim
 * Dec 10, 2014
*/

public class ValidationManager {
  private final Logger logger = LoggerFactory.getLogger(ValidationManager.class);
  private final ValidationMetrics _validationMetrics;
  private final ScheduledExecutorService _executorService;
  private final PinotHelixResourceManager _pinotHelixResourceManager;
  private final long _validationIntervalSeconds;

  /**
   * Constructs the validation manager.
   *  @param validationMetrics The validation metrics utility used to publish the metrics.
   * @param pinotHelixResourceManager The resource manager used to interact with Helix
   * @param config
   */
  public ValidationManager(ValidationMetrics validationMetrics, PinotHelixResourceManager pinotHelixResourceManager,
      ControllerConf config) {
    _validationMetrics = validationMetrics;
    _pinotHelixResourceManager = pinotHelixResourceManager;
    _validationIntervalSeconds = config.getValidationControllerFrequencyInSeconds();

    _executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setName("PinotValidationManagerExecutorService");
        return thread;
      }
    });
  }

  /**
   * Starts the validation manager.
   */
  public void start() {
    logger.info("Starting validation manager");

    // Set up an executor that executes validation tasks periodically
    _executorService.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        try {
          runValidation();
        } catch (Exception e) {
          logger.warn("Caught exception while running validation", e);
        }
      }
    }, 120, _validationIntervalSeconds, TimeUnit.SECONDS);
  }

  /**
   * Stops the validation manager.
   */
  public void stop() {
    // Shut down the executor
    _executorService.shutdown();
  }

  /**
   * Runs a validation pass over the currently loaded resources.
   */
  public void runValidation() {
    if (!_pinotHelixResourceManager.isLeader()) {
      logger.info("Skipping validation, not leader!");
      return;
    }

    logger.info("Starting validation");
    // Fetch the list of resources
    List<String> allResourceNames = _pinotHelixResourceManager.getAllResourceNames();
    ZkHelixPropertyStore<ZNRecord> propertyStore = _pinotHelixResourceManager.getPropertyStore();
    for (String resourceName : allResourceNames) {
      if (!resourceName.equals(CommonConstants.Helix.BROKER_RESOURCE_INSTANCE)) {
        // For each resource, fetch the metadata for all its segments and group them by table
        List<ZNRecord> segmentRecords = propertyStore.getChildren(
            PinotHelixUtils.constructPropertyStorePathForResource(resourceName), null, AccessOption.PERSISTENT);
        Map<String, List<SegmentMetadata>> tableToSegmentMetadata = new HashMap<String, List<SegmentMetadata>>();
        for (ZNRecord record : segmentRecords) {
          SegmentMetadata segmentMetadata = new SegmentMetadataImpl(record);

          String tableName = segmentMetadata.getTableName();

          if (tableToSegmentMetadata.containsKey(tableName)) {
            List<SegmentMetadata> metadataList = tableToSegmentMetadata.get(tableName);
            metadataList.add(segmentMetadata);
          } else {
            List<SegmentMetadata> metadataList = new ArrayList<SegmentMetadata>();
            metadataList.add(segmentMetadata);
            tableToSegmentMetadata.put(tableName, metadataList);
          }
        }

        // For each table
        for (Map.Entry<String, List<SegmentMetadata>> stringListEntry : tableToSegmentMetadata.entrySet()) {
          String tableName = stringListEntry.getKey();
          List<SegmentMetadata> tableSegmentsMetadata = stringListEntry.getValue();

          // Compute the missing segments if there are at least two
          if(2 < tableSegmentsMetadata.size()) {
            List<Interval> segmentIntervals = new ArrayList<Interval>();
            for (SegmentMetadata tableSegmentMetadata : tableSegmentsMetadata) {
              segmentIntervals.add(tableSegmentMetadata.getTimeInterval());
            }

            List<Interval> missingIntervals = computeMissingIntervals(segmentIntervals, tableSegmentsMetadata.get(0).getTimeGranularity());

            // Update the gauge that contains the number of missing segments
            _validationMetrics.updateMissingSegmentsGauge(resourceName, tableName, missingIntervals.size());
          }

          // Compute the max segment end time and max segment push time
          long maxSegmentEndTime = Long.MIN_VALUE;
          long maxSegmentPushTime = Long.MIN_VALUE;

          for (SegmentMetadata segmentMetadata : tableSegmentsMetadata) {
            Interval segmentInterval = segmentMetadata.getTimeInterval();

            if (segmentInterval != null && maxSegmentEndTime < segmentInterval.getEndMillis()) {
              maxSegmentEndTime = segmentInterval.getEndMillis();
            }

            long segmentPushTime = segmentMetadata.getPushTime();
            long segmentRefreshTime = segmentMetadata.getRefreshTime();
            long segmentUpdateTime = Math.max(segmentPushTime, segmentRefreshTime);

            if (maxSegmentPushTime < segmentUpdateTime) {
              maxSegmentPushTime = segmentUpdateTime;
            }
          }

          // Update the gauges that contain the delay between the current time and last segment end time
          _validationMetrics.updateOfflineSegmentDelayGauge(resourceName, tableName, maxSegmentEndTime);
          _validationMetrics.updateLastPushTimeGauge(resourceName, tableName, maxSegmentPushTime);
        }
      }
    }
    logger.info("Validation completed");
  }

  /**
   * Computes a list of missing intervals, given a list of existing intervals and the expected frequency of the
   * intervals.
   *
   * @param segmentIntervals The list of existing intervals
   * @param frequency The expected interval frequency
   * @return The list of missing intervals
   */
  public static List<Interval> computeMissingIntervals(List<Interval> segmentIntervals, Duration frequency) {
    // If there are less than two segments, none can be missing
    if (segmentIntervals.size() < 2) {
      return Collections.emptyList();
    }

    // Sort the intervals by ascending starting time
    List<Interval> sortedSegmentIntervals = new ArrayList<Interval>(segmentIntervals);
    Collections.sort(sortedSegmentIntervals, new Comparator<Interval>() {
      @Override
      public int compare(Interval first, Interval second) {
        if (first.getStartMillis() < second.getStartMillis()) return -1;
        else if (second.getStartMillis() < first.getStartMillis()) return 1;
        return 0;
      }
    });

    // Find the minimum starting time and maximum ending time
    final long startTime = sortedSegmentIntervals.get(0).getStartMillis();
    long endTime = Long.MIN_VALUE;
    for (Interval sortedSegmentInterval : sortedSegmentIntervals) {
      if (endTime < sortedSegmentInterval.getEndMillis()) {
        endTime = sortedSegmentInterval.getEndMillis();
      }
    }

    final long frequencyMillis = frequency.getMillis();
    int lastEndIntervalCount = 0;
    List<Interval> missingIntervals = new ArrayList<Interval>(10);
    for (Interval segmentInterval : sortedSegmentIntervals) {
      int startIntervalCount = (int) ((segmentInterval.getStartMillis() - startTime) / frequencyMillis);
      int endIntervalCount = (int) ((segmentInterval.getEndMillis() - startTime) / frequencyMillis);

      // If there is at least one complete missing interval between the end of the previous interval and the start of
      // the current interval, then mark the missing interval(s) as missing
      if(lastEndIntervalCount < startIntervalCount - 1) {
        for (int missingIntervalIndex = lastEndIntervalCount + 1; missingIntervalIndex < startIntervalCount;
            ++missingIntervalIndex) {
          missingIntervals.add(new Interval(startTime + frequencyMillis * missingIntervalIndex, startTime + frequencyMillis * (missingIntervalIndex + 1) - 1));
        }
      }

      lastEndIntervalCount = Math.max(lastEndIntervalCount, endIntervalCount);
    }

    return missingIntervals;
  }

  /**
   * Counts the number of missing segments, given their start times and their expected frequency.
   *
   * @param sortedStartTimes Start times for the segments, sorted in ascending order.
   * @param frequency The expected segment frequency (ie. daily, hourly, etc.)
   */
  public static int countMissingSegments(long[] sortedStartTimes, TimeUnit frequency) {
    // If there are less than two segments, none can be missing
    if (sortedStartTimes.length < 2) {
      return 0;
    }

    final long frequencyMillis = frequency.toMillis(1);
    final long halfFrequencyMillis = frequencyMillis / 2;
    final long firstStartTime = sortedStartTimes[0];
    final long lastStartTime = sortedStartTimes[sortedStartTimes.length - 1];
    final int expectedSegmentCount = (int) ((lastStartTime + halfFrequencyMillis - firstStartTime) / frequencyMillis);

    int missingSegments = 0;
    int currentIndex = 1;
    for(int expectedIntervalCount = 1; expectedIntervalCount <= expectedSegmentCount;) {
      // Count the number of complete intervals that are found
      final int intervalCount =
          (int) ((sortedStartTimes[currentIndex] + halfFrequencyMillis - firstStartTime) / frequencyMillis);

      // Does this segment have the expected interval count?
      if (intervalCount == expectedIntervalCount) {
        // Yes, advance both the current index and expected interval count
        ++expectedIntervalCount;
        ++currentIndex;
      } else {
        if (intervalCount < expectedIntervalCount) {
          // Duplicate segment, just advance the index
          ++currentIndex;
        } else  {
          // Missing segment(s), advance the index, increment the number of missing segments by the number of missing
          // intervals and set the expected interval to the following one
          missingSegments += intervalCount - expectedIntervalCount;
          expectedIntervalCount = intervalCount + 1;
          ++currentIndex;
        }
      }
    }

    return missingSegments;
  }
}
