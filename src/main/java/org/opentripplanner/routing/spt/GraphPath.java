package org.opentripplanner.routing.spt;

import java.util.LinkedList;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.api.resource.CoordinateArrayListSequence;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.util.geometry.GeometryUtils;

/**
 * A shortest path on the graph.
 */
public class GraphPath {

  public LinkedList<State> states;

  public LinkedList<Edge> edges;

  private double walkDistance = 0;

  /**
   * Construct a GraphPath based on the given state by following back-edge fields all the way back
   * to the origin of the search. This constructs a proper Java list of states (allowing random
   * access etc.) from the predecessor information left in states by the search algorithm.
   * <p>
   * Optionally re-traverses all edges backward in order to remove excess waiting time from the
   * final itinerary presented to the user. When planning with departure time, the edges will then
   * be re-traversed once more in order to move the waiting time forward in time, towards the end.
   *
   * @param s - the state for which a path is requested
   */
  public GraphPath(State s) {
    walkDistance = s.getWalkDistance();

    /* Put path in chronological order */
    State lastState;
    // needed to track repeat invocations of path-reversing methods
    if (s.getOptions().arriveBy) {
      lastState = s.reverse();
    } else {
      lastState = s;
    }

    /*
     * Starting from latest (time-wise) state, copy states to the head of a list in reverse
     * chronological order. List indices will thus increase forward in time, and backEdges will
     * be chronologically 'back' relative to their state.
     */
    this.states = new LinkedList<>();
    this.edges = new LinkedList<>();
    for (State cur = lastState; cur != null; cur = cur.getBackState()) {
      states.addFirst(cur);

      // Record the edge if it exists and this is not the first state in the path.
      if (cur.getBackEdge() != null && cur.getBackState() != null) {
        edges.addFirst(cur.getBackEdge());
      }
    }
  }

  /**
   * Returns the start time of the trip in seconds since the epoch.
   */
  public long getStartTime() {
    return states.getFirst().getTimeSeconds();
  }

  /**
   * Returns the end time of the trip in seconds since the epoch.
   */
  public long getEndTime() {
    return states.getLast().getTimeSeconds();
  }

  /**
   * Returns the duration of the trip in seconds.
   */
  public int getDuration() {
    // test to see if it is the same as getStartTime - getEndTime;
    return (int) states.getLast().getElapsedTimeSeconds();
  }

  /**
   * Returns the total distance of all the edges in this path
   */
  public double getDistanceMeters() {
    return edges.stream().mapToDouble(Edge::getDistanceMeters).sum();
  }

  public double getWeight() {
    return states.getLast().getWeight();
  }

  public Vertex getStartVertex() {
    return states.getFirst().getVertex();
  }

  public Vertex getEndVertex() {
    return states.getLast().getVertex();
  }

  /**
   * Returns the geometry for the entire path
   */
  public LineString getGeometry() {
    CoordinateArrayListSequence coordinates = new CoordinateArrayListSequence();

    for (Edge edge : edges) {
      LineString geometry = edge.getGeometry();

      if (geometry != null) {
        if (coordinates.size() == 0) {
          coordinates.extend(geometry.getCoordinates());
        } else {
          // Avoid duplications
          coordinates.extend(geometry.getCoordinates(), 1);
        }
      }
    }

    return GeometryUtils.getGeometryFactory().createLineString(coordinates);
  }

  // must compare edges, not states, since states are different at each search
  public int hashCode() {
    return this.edges.hashCode();
  }

  /**
   * Two paths are equal if they use the same ordered list of trips
   */
  // TODO OTP2 How should this be implemented now that the GraphPath has no trips?
  public boolean equals(Object o) {
    return false;
  }

  public String toString() {
    return "GraphPath(nStates=" + states.size() + ")";
  }

  /****
   * Private Methods
   ****/

  public double getWalkDistance() {
    return walkDistance;
  }
}
