package org.opentripplanner.graph_builder.module;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.ext.flex.trip.FlexTrip;
import org.opentripplanner.graph_builder.DataImportIssue;
import org.opentripplanner.graph_builder.DataImportIssueStore;
import org.opentripplanner.graph_builder.issues.HopSpeedFast;
import org.opentripplanner.graph_builder.issues.HopSpeedSlow;
import org.opentripplanner.graph_builder.issues.HopZeroDistance;
import org.opentripplanner.graph_builder.issues.HopZeroTime;
import org.opentripplanner.graph_builder.issues.NegativeDwellTime;
import org.opentripplanner.graph_builder.issues.NegativeHopTime;
import org.opentripplanner.graph_builder.issues.RepeatedStops;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.model.TripStopTimes;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.util.OTPFeature;
import org.opentripplanner.util.logging.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for making sure, that all trips have arrival and departure times for
 * all stops. It also removes all stop times for trips, which have invalid stop times.
 */
public class ValidateAndInterpolateStopTimesForEachTrip {

  private static final Logger LOG = LoggerFactory.getLogger(
    ValidateAndInterpolateStopTimesForEachTrip.class
  );

  private final TripStopTimes stopTimesByTrip;
  private final boolean interpolate;
  private final DataImportIssueStore issueStore;

  public ValidateAndInterpolateStopTimesForEachTrip(
    TripStopTimes stopTimesByTrip,
    boolean interpolate,
    DataImportIssueStore issueStore
  ) {
    this.stopTimesByTrip = stopTimesByTrip;
    this.interpolate = interpolate;
    this.issueStore = issueStore;
  }

  public void run() {
    final int tripSize = stopTimesByTrip.size();
    var progress = ProgressTracker.track("Validate StopTimes", 100_000, tripSize);
    LOG.info(progress.startMessage());

    for (Trip trip : stopTimesByTrip.keys()) {
      // Fetch the stop times for this trip. Copy the list since it's immutable.
      List<StopTime> stopTimes = new ArrayList<>(stopTimesByTrip.get(trip));

      // if we don't have flex routing enabled then remove all the flex locations and location
      // groups
      if (OTPFeature.FlexRouting.isOff()) {
        stopTimes.removeIf(st -> !(st.getStop() instanceof RegularStop));
      }

      // Stop times frequently contain duplicate, missing, or incorrect entries. Repair them.
      TIntList removedStopSequences = removeRepeatedStops(stopTimes);
      if (!removedStopSequences.isEmpty()) {
        issueStore.add(new RepeatedStops(trip, removedStopSequences));
      }
      if (!filterStopTimes(stopTimes)) {
        stopTimesByTrip.replace(trip, List.of());
      } else if (interpolate) {
        interpolateStopTimes(stopTimes);
        stopTimesByTrip.replace(trip, stopTimes);
      } else {
        stopTimes.removeIf(st -> !st.isArrivalTimeSet() || !st.isDepartureTimeSet());
        stopTimesByTrip.replace(trip, stopTimes);
      }

      //noinspection Convert2MethodRef
      progress.step(m -> LOG.info(m));
    }

    LOG.info(progress.completeMessage());
  }

  /**
   * Filter out any series of stop times that refer to the same stop. This is very inefficient in an
   * array-backed list, but we are assuming that this is a rare occurrence. The alternative is to
   * copy every list of stop times during filtering.
   * <p>
   *
   * @return whether any repeated stops were filtered out.
   */
  private TIntList removeRepeatedStops(List<StopTime> stopTimes) {
    StopTime prev = null;
    Iterator<StopTime> it = stopTimes.iterator();
    TIntList stopSequencesRemoved = new TIntArrayList();
    while (it.hasNext()) {
      StopTime st = it.next();
      if (prev != null) {
        if (prev.getStop().equals(st.getStop())) {
          // Merge the two stop times, making sure we're not throwing out a stop time with times in
          // favor of an interpolated stop time. Keep the arrival time of the previous stop, unless
          // it didn't have an arrival time, in which case replace it with the arrival time of this
          // stop time. This is particularly important at the last stop in a route (see issue #2220)
          if (prev.getArrivalTime() == StopTime.MISSING_VALUE) {
            prev.setArrivalTime(st.getArrivalTime());
          }

          // prefer to replace with the departure time of this stop time, unless this stop time has
          // no departure time
          if (st.getDepartureTime() != StopTime.MISSING_VALUE) {
            prev.setDepartureTime(st.getDepartureTime());
          }

          it.remove();
          stopSequencesRemoved.add(st.getStopSequence());
        }
      }
      prev = st;
    }
    return stopSequencesRemoved;
  }

  /**
   * Scan through the given list, looking for clearly incorrect series of stop times. This includes
   * duplicate times (0-time hops), as well as negative, fast or slow hops. {@link DataImportIssue}s
   * are reported to reveal the problems to the user.
   *
   * @param stopTimes the stop times to be filtered (from a single trip)
   * @return whether the stop time is usable
   */
  private boolean filterStopTimes(List<StopTime> stopTimes) {
    if (stopTimes.size() < 2 && !FlexTrip.containsFlexStops(stopTimes)) {
      return false;
    }

    StopTime st0 = stopTimes.get(0);

    // If the feed does not specify any time points, we want to mark all times that are present as
    // time points.
    boolean hasTimepoints = stopTimes.stream().anyMatch(stopTime -> stopTime.getTimepoint() == 1);

    if (!hasTimepoints) {
      st0.setTimepoint(1);
    }

    for (int i = 1; i < stopTimes.size(); i++) {
      StopTime st1 = stopTimes.get(i);

      // If the feed did not specify any time points, mark all times that are present as time points
      if (!hasTimepoints && (st1.isDepartureTimeSet() || st1.isArrivalTimeSet())) {
        st1.setTimepoint(1);
      }

      // Set arrival and departure times if one of them is missing.
      if (!st1.isArrivalTimeSet() && st1.isDepartureTimeSet()) {
        st1.setArrivalTime(st1.getDepartureTime());
      } else if (!st1.isDepartureTimeSet() && st1.isArrivalTimeSet()) {
        st1.setDepartureTime(st1.getArrivalTime());
      }

      // Do not process non-time-point stop times, leaving them in place for interpolation.
      // All non-timepoint stoptimes in a series will have identical arrival and departure values of
      // MISSING_VALUE.
      if (!(st1.isArrivalTimeSet() && st1.isDepartureTimeSet())) {
        continue;
      }
      int dwellTime = st0.getDepartureTime() - st0.getArrivalTime();
      if (dwellTime < 0) {
        issueStore.add(new NegativeDwellTime(st0));
        return false;
      }

      int runningTime = st1.getArrivalTime() - st0.getDepartureTime();
      if (runningTime < 0) {
        issueStore.add(new NegativeHopTime(st0, st1));
        return false;
      }

      double hopDistance = SphericalDistanceLibrary.fastDistance(
        st0.getStop().getCoordinate().asJtsCoordinate(),
        st1.getStop().getCoordinate().asJtsCoordinate()
      );
      double hopSpeed = hopDistance / runningTime;

      if (hopDistance == 0) {
        issueStore.add(new HopZeroDistance(runningTime, st1.getTrip(), st1.getStopSequence()));
      }
      // sanity-check the hop
      if (runningTime == 0) {
        // identical stop times at different stops
        issueStore.add(new HopZeroTime((float) hopDistance, st1.getTrip(), st1.getStopSequence()));
      } else if (hopSpeed > 45) {
        // 45 m/sec ~= 100 miles/hr
        // elapsed time of 0 will give speed of +inf
        issueStore.add(
          new HopSpeedFast(
            (float) hopSpeed,
            (float) hopDistance,
            st0.getTrip(),
            st0.getStopSequence()
          )
        );
      } else if (hopSpeed < 0.1) {
        // 0.1 m/sec ~= 0.2 miles/hr
        issueStore.add(
          new HopSpeedSlow(
            (float) hopSpeed,
            (float) hopDistance,
            st0.getTrip(),
            st0.getStopSequence()
          )
        );
      }
      st0 = st1;
    } // END for loop over stop times
    return true;
  }

  /**
   * Scan through the given list of stoptimes, interpolating the missing (unset) ones. This is
   * currently done by assuming equidistant stops and constant speed. While we may not be able to
   * improve the constant speed assumption, we can
   * TODO: use route matching (or shape distance etc.) to improve inter-stop distances
   *
   * @param stopTimes the stoptimes (from a single trip) to be interpolated
   */
  private void interpolateStopTimes(List<StopTime> stopTimes) {
    int lastStop = stopTimes.size() - 1;
    int numInterpStops;
    int departureTime = -1;
    int prevDepartureTime;
    int interpStep;

    for (int i = 0; i < lastStop; i++) {
      StopTime st0 = stopTimes.get(i);

      prevDepartureTime = departureTime;
      departureTime = st0.getDepartureTime();

      // Interpolate, if necessary, the times of non-timepoint stops
      if (
        !(st0.isDepartureTimeSet() && st0.isArrivalTimeSet()) && !FlexTrip.isFlexStop(st0.getStop())
      ) {
        // figure out how many such stops there are in a row.
        int j;
        StopTime st = null;
        for (j = i + 1; j < lastStop + 1; ++j) {
          st = stopTimes.get(j);
          if (
            (st.isDepartureTimeSet() && st.getDepartureTime() != departureTime) ||
            (st.isArrivalTimeSet() && st.getArrivalTime() != departureTime)
          ) {
            break;
          }
        }
        if (j == lastStop + 1) {
          throw new RuntimeException(
            "Could not interpolate arrival/departure time on stop " +
            i +
            " (missing final stop time) on trip " +
            st0.getTrip()
          );
        }
        numInterpStops = j - i;
        int arrivalTime;
        if (st.isArrivalTimeSet()) {
          arrivalTime = st.getArrivalTime();
        } else {
          arrivalTime = st.getDepartureTime();
        }
        interpStep = (arrivalTime - prevDepartureTime) / (numInterpStops + 1);
        if (interpStep < 0) {
          throw new RuntimeException("trip goes backwards for some reason");
        }
        for (j = i; j < i + numInterpStops; ++j) {
          //System.out.println("interpolating " + j + " between " + prevDepartureTime + " and " + arrivalTime);
          departureTime = prevDepartureTime + interpStep * (j - i + 1);
          st = stopTimes.get(j);
          if (st.isArrivalTimeSet()) {
            departureTime = st.getArrivalTime();
          } else {
            st.setArrivalTime(departureTime);
          }
          if (!st.isDepartureTimeSet()) {
            st.setDepartureTime(departureTime);
          }
        }
        i = j - 1;
      }
    }
  }
}
