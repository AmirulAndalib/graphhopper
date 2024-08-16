package com.graphhopper.routing.weighting.custom;

import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;

import static com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER;

public class CustomWeighting2 implements Weighting {
    public static final String NAME = "custom";

    /**
     * Converting to seconds is not necessary but makes adding other penalties easier (e.g. turn
     * costs or traffic light costs etc)
     */
    private final static double SPEED_CONV = 3.6;
    private final double headingPenaltySeconds;
    private final CustomWeighting.EdgeToDoubleMapping edgeToSpeedMapping;
    private final CustomWeighting.EdgeToDoubleMapping edgeToPriorityMapping;
    private final TurnCostProvider turnCostProvider;

    public CustomWeighting2(TurnCostProvider turnCostProvider, CustomWeighting.Parameters parameters) {
        if (!Weighting.isValidName(getName()))
            throw new IllegalStateException("Not a valid name for a Weighting: " + getName());
        this.turnCostProvider = turnCostProvider;

        this.edgeToSpeedMapping = parameters.getEdgeToSpeedMapping();
        this.edgeToPriorityMapping = parameters.getEdgeToPriorityMapping();
        this.headingPenaltySeconds = parameters.getHeadingPenaltySeconds();
    }

    @Override
    public double calcMinWeightPerDistance() {
        return 1d ;
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        double priority = edgeToPriorityMapping.get(edgeState, reverse);
        if (priority == 0)
            return Double.POSITIVE_INFINITY;
        else if (priority < 1)
            throw new IllegalArgumentException("priority must be >= 1 (or 0 for infinite weight), use 1 only for the **highest** 'priority' roads");
        return priority * edgeState.getDistance() + headingPenaltySeconds / SPEED_CONV;
    }

    @Override
    public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
        double speed = edgeToSpeedMapping.get(edgeState, reverse);
        if (speed == 0) return Long.MAX_VALUE;
        return Math.round(edgeState.getDistance() * 1000 / speed * SPEED_CONV);
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
