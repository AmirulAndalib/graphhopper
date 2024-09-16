package com.graphhopper.routing.weighting.custom;

import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;

import static com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER;

public class CustomWeighting2 implements Weighting {
    public static final String NAME = "custom_two";
    private final double distanceInfluence;
    private final CustomWeighting.EdgeToDoubleMapping edgeToSpeedMapping;
    private final CustomWeighting.EdgeToDoubleMapping edgeToPriorityMapping;
    private final TurnCostProvider turnCostProvider;

    public CustomWeighting2(TurnCostProvider turnCostProvider, CustomWeighting.Parameters parameters) {
        if (!Weighting.isValidName(getName()))
            throw new IllegalStateException("Not a valid name for a Weighting: " + getName());
        this.turnCostProvider = turnCostProvider;
        this.edgeToSpeedMapping = parameters.getEdgeToSpeedMapping();
        this.edgeToPriorityMapping = parameters.getEdgeToPriorityMapping();
        this.distanceInfluence = parameters.getDistanceInfluence();
    }

    @Override
    public double calcMinWeightPerDistance() {
        return 0.01;
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        double priority = edgeToPriorityMapping.get(edgeState, reverse);
        if (priority < 0 || priority > 100)
            throw new IllegalArgumentException("Invalid priority: " + priority + ", must be in [0, 100]");
        else if (priority == 0)
            return Double.POSITIVE_INFINITY;
        return edgeState.getDistance() * (1 / priority + distanceInfluence);
    }

    @Override
    public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
        double speed = edgeToSpeedMapping.get(edgeState, reverse);
        if (speed == 0) return Long.MAX_VALUE;
        return Math.round(edgeState.getDistance() * 1000 / speed * 3.6);
    }

    @Override
    public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
        return turnCostProvider.calcTurnWeight(inEdge, viaNode, outEdge);
    }

    @Override
    public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
        return turnCostProvider.calcTurnMillis(inEdge, viaNode, outEdge);
    }

    @Override
    public boolean hasTurnCosts() {
        return turnCostProvider != NO_TURN_COST_PROVIDER;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
