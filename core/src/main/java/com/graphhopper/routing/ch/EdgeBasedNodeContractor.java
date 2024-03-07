/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.ch;

import com.carrotsearch.hppc.*;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.graphhopper.reader.osm.Pair;
import com.graphhopper.storage.CHStorageBuilder;
import com.graphhopper.util.BitUtil;
import com.graphhopper.util.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

import static com.graphhopper.routing.ch.CHParameters.*;
import static com.graphhopper.util.GHUtility.reverseEdgeKey;
import static com.graphhopper.util.Helper.nf;
import static java.util.Collections.emptyList;

/**
 * This class is used to calculate the priority of or contract a given node in edge-based Contraction Hierarchies as it
 * is required to support turn-costs. This implementation follows the 'aggressive' variant described in
 * 'Efficient Routing in Road Networks with Turn Costs' by R. Geisberger and C. Vetter. Here, we do not store the center
 * node for each shortcut, but introduce helper shortcuts when a loop shortcut is encountered.
 * <p>
 * This class is mostly concerned with triggering the required local searches and introducing the necessary shortcuts
 * or calculating the node priority, while the actual searches for witness paths are delegated to
 * {@link EdgeBasedWitnessPathSearcher}.
 *
 * @author easbar
 */
class EdgeBasedNodeContractor implements NodeContractor {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeBasedNodeContractor.class);
    private final CHPreparationGraph prepareGraph;
    private PrepareGraphEdgeExplorer inEdgeExplorer;
    private PrepareGraphEdgeExplorer outEdgeExplorer;
    private PrepareGraphEdgeExplorer existingShortcutExplorer;
    private PrepareGraphOrigEdgeExplorer sourceNodeOrigInEdgeExplorer;
    private CHStorageBuilder chBuilder;
    private final Params params = new Params();
    // temporary data used during node contraction
    private final IntSet sourceNodes = new IntHashSet(10);
    private final LongSet addedShortcuts = new LongHashSet();
    private final Stats addingStats = new Stats();
    private final Stats countingStats = new Stats();
    private final ExecutorService executorService;

    private static class NumberedThread extends Thread {
        public int number;

        NumberedThread(int number, Runnable r) {
            super(r);
            this.number = number;
        }
    }

    private Stats activeStats;
    int futureIdx;
    List<Future<Pair<List<ShortcutHandlerData>, EdgeBasedWitnessPathSearcher.Stats>>> futures = new ArrayList<>();
    List<Pair<List<ShortcutHandlerData>, EdgeBasedWitnessPathSearcher.Stats>> results = new ArrayList<>();

    private Worker[] workers;
    private int[] hierarchyDepths;
    private final EdgeBasedWitnessPathSearcher.Stats wpsStatsHeur = new EdgeBasedWitnessPathSearcher.Stats();
    private final EdgeBasedWitnessPathSearcher.Stats wpsStatsContr = new EdgeBasedWitnessPathSearcher.Stats();
    private final int average_count;
    private final int logging_count;
    private final int threads;
    private final double parallelThreshold;
    private final IntDeque times;
    private double average;
    private boolean parallel;

    // counts the total number of added shortcuts
    private int addedShortcutsCount;

    // edge counts used to calculate priority
    private int numShortcuts;
    private int numPrevEdges;
    private int numOrigEdges;
    private int numPrevOrigEdges;
    private int numAllEdges;

    private double meanDegree;

    int count = 0;

    public EdgeBasedNodeContractor(CHPreparationGraph prepareGraph, CHStorageBuilder chBuilder, PMap pMap) {
        this.prepareGraph = prepareGraph;
        this.chBuilder = chBuilder;
        average_count = pMap.getInt("parallel_ch.average_count", 10_000);
        logging_count = pMap.getInt("parallel_ch.logging_count", 10_000);
        threads = pMap.getInt("parallel_ch.threads", 4);
        parallelThreshold = pMap.getDouble("parallel_ch.threshold", 100);
        times = new IntArrayDeque(average_count);
        for (int i = 0; i < average_count; i++)
            times.addLast(0);
        executorService = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            int count = 0;

            @Override
            public Thread newThread(@NotNull Runnable r) {
                return new NumberedThread(count++, r);
            }
        });
        extractParams(pMap);
    }

    private void extractParams(PMap pMap) {
        params.edgeQuotientWeight = pMap.getFloat(EDGE_QUOTIENT_WEIGHT, params.edgeQuotientWeight);
        params.originalEdgeQuotientWeight = pMap.getFloat(ORIGINAL_EDGE_QUOTIENT_WEIGHT, params.originalEdgeQuotientWeight);
        params.hierarchyDepthWeight = pMap.getFloat(HIERARCHY_DEPTH_WEIGHT, params.hierarchyDepthWeight);
        params.maxPollFactorHeuristic = pMap.getDouble(MAX_POLL_FACTOR_HEURISTIC_EDGE, params.maxPollFactorHeuristic);
        params.maxPollFactorContraction = pMap.getDouble(MAX_POLL_FACTOR_CONTRACTION_EDGE, params.maxPollFactorContraction);
    }

    @Override
    public void initFromGraph() {
        inEdgeExplorer = prepareGraph.createInEdgeExplorer();
        outEdgeExplorer = prepareGraph.createOutEdgeExplorer();
        existingShortcutExplorer = prepareGraph.createOutEdgeExplorer();
        sourceNodeOrigInEdgeExplorer = prepareGraph.createInOrigEdgeExplorer();
        hierarchyDepths = new int[prepareGraph.getNodes()];
        meanDegree = prepareGraph.getOriginalEdges() * 1.0 / prepareGraph.getNodes();
        // todonow: do we ever need to destroy the worker, shutdown the threadpool and/or call witnesspathfinder.close()?
        workers = new Worker[threads];
        for (int i = 0; i < threads; i++)
            workers[i] = new Worker(prepareGraph);
    }

    @Override
    public float calculatePriority(int node) {
        activeStats = countingStats;
        resetEdgeCounters();
        countPreviousEdges(node);
        if (numAllEdges == 0)
            // this node is isolated, maybe it belongs to a removed subnetwork, in any case we can quickly contract it
            // no shortcuts will be introduced
            return Float.NEGATIVE_INFINITY;
        stats().stopWatch.start();
        findAndHandlePrepareShortcuts(node, this::countShortcuts, (int) (meanDegree * params.maxPollFactorHeuristic), wpsStatsHeur);
        stats().stopWatch.stop();
        // the higher the priority the later (!) this node will be contracted
        float edgeQuotient = numShortcuts / (float) (prepareGraph.getDegree(node));
        float origEdgeQuotient = numOrigEdges / (float) numPrevOrigEdges;
        int hierarchyDepth = hierarchyDepths[node];
        float priority = params.edgeQuotientWeight * edgeQuotient +
                params.originalEdgeQuotientWeight * origEdgeQuotient +
                params.hierarchyDepthWeight * hierarchyDepth;
        if (LOGGER.isTraceEnabled())
            LOGGER.trace("node: {}, eq: {} / {} = {}, oeq: {} / {} = {}, depth: {} --> {}",
                    node,
                    numShortcuts, numPrevEdges, edgeQuotient,
                    numOrigEdges, numPrevOrigEdges, origEdgeQuotient,
                    hierarchyDepth, priority);
        return priority;
    }

    @Override
    public IntContainer contractNode(int node) {
        activeStats = addingStats;
        stats().stopWatch.start();
        findAndHandlePrepareShortcuts(node, this::addShortcutsToPrepareGraph, (int) (meanDegree * params.maxPollFactorContraction), wpsStatsContr);
        insertShortcuts(node);
        IntContainer neighbors = prepareGraph.disconnect(node);
        // We maintain an approximation of the mean degree which we update after every contracted node.
        // We do it the same way as for node-based CH for now.
        meanDegree = (meanDegree * 2 + neighbors.size()) / 3;
        updateHierarchyDepthsOfNeighbors(node, neighbors);
        stats().stopWatch.stop();
        return neighbors;
    }

    @Override
    public void finishContraction() {
        chBuilder.replaceSkippedEdges(prepareGraph::getShortcutForPrepareEdge);
        executorService.shutdown();
    }

    @Override
    public long getAddedShortcutsCount() {
        return addedShortcutsCount;
    }

    @Override
    public float getDijkstraSeconds() {
        return (wpsStatsHeur.dijkstraNanos + wpsStatsContr.dijkstraNanos) / 1e9f;
    }

    @Override
    public String getStatisticsString() {
        return String.format(Locale.ROOT, "degree_approx: %3.1f", meanDegree) + ", priority   : " + countingStats + ", " + wpsStatsHeur + ", contraction: " + addingStats + ", " + wpsStatsContr;
    }

    /**
     * This method performs witness searches between all nodes adjacent to the given node and calls the
     * given handler for all required shortcuts.
     */
    private void findAndHandlePrepareShortcuts(int node, PrepareShortcutHandler shortcutHandler, int maxPolls, EdgeBasedWitnessPathSearcher.Stats wpsStats) {
        stats().nodes++;
        addedShortcuts.clear();
        sourceNodes.clear();

        long start = System.nanoTime();
        futureIdx = -1;
        // traverse incoming edges/shortcuts to find all the source nodes
        PrepareGraphEdgeIterator incomingEdges = inEdgeExplorer.setBaseNode(node);
        while (incomingEdges.next()) {
            final int sourceNode = incomingEdges.getAdjNode();
            if (sourceNode == node)
                continue;
            // make sure we process each source node only once
            if (!sourceNodes.add(sourceNode))
                continue;
            // for each source node we need to look at every incoming original edge and check which target edges are reachable
            PrepareGraphOrigEdgeIterator origInIter = sourceNodeOrigInEdgeExplorer.setBaseNode(sourceNode);
            while (origInIter.next()) {
                final int sourceInEdgeKey = reverseEdgeKey(origInIter.getOrigEdgeKeyLast());
                futureIdx++;
                if (futureIdx >= futures.size())
                    futures.add(null);
                futures.set(futureIdx, parallel ? executorService.submit(() -> {
                    Thread thread = Thread.currentThread();
                    int threadId = thread instanceof NumberedThread ? ((NumberedThread) thread).number : -1;
                    if (threadId < 0)
                        throw new IllegalStateException("Unexpected thread: " + thread.getName());
                    Worker worker = workers[threadId];
                    return worker.findShortcuts(sourceNode, sourceInEdgeKey, node, maxPolls);
                })
                        : new Future<Pair<List<ShortcutHandlerData>, EdgeBasedWitnessPathSearcher.Stats>>() {
                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        return false;
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }

                    @Override
                    public boolean isDone() {
                        return false;
                    }

                    @Override
                    public Pair<List<ShortcutHandlerData>, EdgeBasedWitnessPathSearcher.Stats> get() throws InterruptedException, ExecutionException {
                        return workers[0].findShortcuts(sourceNode, sourceInEdgeKey, node, maxPolls);
                    }

                    @Override
                    public Pair<List<ShortcutHandlerData>, EdgeBasedWitnessPathSearcher.Stats> get(long timeout, @NotNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                        return null;
                    }
                });
            }
        }

        while (futureIdx + 1 >= results.size())
            results.add(null);
        for (int i = 0; i < futureIdx + 1; i++) {
            try {
                results.set(i, futures.get(i).get());
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        long time = System.nanoTime() - start;
        int last = times.removeLast();
        times.addFirst((int) (time / 1_000));
        int first = ((int) (time / 1_000));
        average -= last / (double) average_count;
        average += first / (double) average_count;

        if (count % average_count == 0)
            parallel = average > parallelThreshold;

        if (count % logging_count == 0)
            LOGGER.info(Helper.nf(count) + " " + String.format("%.2f", average) + "μs - " + (parallel ? "PARALLEL" : "SEQUENTIAL"));
        count++;
        for (int i = 0; i < futureIdx + 1; i++) {
            Pair<List<ShortcutHandlerData>, EdgeBasedWitnessPathSearcher.Stats> result = results.get(i);
            handleResults(result, shortcutHandler, wpsStats);
        }
    }

    private void handleResults(Pair<List<ShortcutHandlerData>, EdgeBasedWitnessPathSearcher.Stats> data, PrepareShortcutHandler shortcutHandler, EdgeBasedWitnessPathSearcher.Stats wpsStats) {
        for (ShortcutHandlerData shortcut : data.first) {
            // we make sure to add each shortcut only once. when we are actually adding shortcuts we check for existing
            // shortcuts anyway, but at least this is important when we *count* shortcuts.
            long addedShortcutKey = BitUtil.LITTLE.toLong(shortcut.edgeFrom.firstEdgeKey, shortcut.edgeTo.incEdgeKey);
            if (!addedShortcuts.add(addedShortcutKey))
                continue;
            shortcutHandler.handleShortcut(shortcut.edgeFrom, shortcut.edgeTo, shortcut.origEdgeCount);
        }
        EdgeBasedWitnessPathSearcher.Stats stats = data.second;
        // update stats using values of last search
        wpsStats.numTrees += stats.numTrees;
        wpsStats.numSearches += stats.numSearches;
        wpsStats.numPolls += stats.numPolls;
        wpsStats.maxPolls = Math.max(wpsStats.maxPolls, stats.numPolls);
        wpsStats.numExplored += stats.numExplored;
        wpsStats.maxExplored = Math.max(wpsStats.maxExplored, stats.numExplored);
        wpsStats.numUpdates += stats.numUpdates;
        wpsStats.maxUpdates = Math.max(wpsStats.maxUpdates, stats.numUpdates);
        wpsStats.numCapped += stats.numCapped;
        wpsStats.dijkstraNanos += stats.dijkstraNanos;
    }

    private static class Worker {
        private final CHPreparationGraph prepareGraph;
        private final EdgeBasedWitnessPathSearcher witnessPathSearcher;
        private final BridgePathFinder bridgePathFinder;

        public Worker(CHPreparationGraph prepareGraph) {
            this.prepareGraph = prepareGraph;
            witnessPathSearcher = new EdgeBasedWitnessPathSearcher(prepareGraph);
            bridgePathFinder = new BridgePathFinder(prepareGraph);
        }

        private Pair<List<ShortcutHandlerData>, EdgeBasedWitnessPathSearcher.Stats> findShortcuts(int sourceNode, int sourceInEdgeKey, int centerNode, int maxPolls) {
            // we search 'bridge paths' leading to the target edges
            IntObjectMap<BridgePathFinder.BridePathEntry> bridgePaths = bridgePathFinder.find(sourceInEdgeKey, sourceNode, centerNode);
            if (bridgePaths.isEmpty())
                return new Pair<>(emptyList(), new EdgeBasedWitnessPathSearcher.Stats());
            List<ShortcutHandlerData> result = new ArrayList<>();
            witnessPathSearcher.initSearch(sourceInEdgeKey, sourceNode, centerNode);
            long dijkstraNanos = 0;
            for (IntObjectCursor<BridgePathFinder.BridePathEntry> bridgePath : bridgePaths) {
                if (!Double.isFinite(bridgePath.value.weight))
                    throw new IllegalStateException("Bridge entry weights should always be finite");
                int targetEdgeKey = bridgePath.key;
                long dijkstraStart = System.nanoTime();
                double weight = witnessPathSearcher.runSearch(bridgePath.value.chEntry.adjNode, targetEdgeKey, bridgePath.value.weight, maxPolls);
                dijkstraNanos += (System.nanoTime() - dijkstraStart);
                if (weight <= bridgePath.value.weight)
                    // we found a witness, nothing to do
                    continue;
                PrepareCHEntry root = bridgePath.value.chEntry;
                while (EdgeIterator.Edge.isValid(root.parent.prepareEdge))
                    root = root.getParent();
                double initialTurnCost = prepareGraph.getTurnWeight(sourceInEdgeKey, sourceNode, root.firstEdgeKey);
                bridgePath.value.chEntry.weight -= initialTurnCost;
                LOGGER.trace("Adding shortcuts for target entry {}", bridgePath.value.chEntry);
                // todo: re-implement loop-avoidance heuristic as it existed in GH 1.0? it did not work the
                //       way it was implemented so it was removed at some point
                result.add(new ShortcutHandlerData(root, bridgePath.value.chEntry, bridgePath.value.chEntry.origEdges));
            }
            EdgeBasedWitnessPathSearcher.Stats stats = witnessPathSearcher.finishSearch();
            stats.dijkstraNanos = dijkstraNanos;
            return new Pair<>(result, stats);
        }
    }

    private static class ShortcutHandlerData {
        PrepareCHEntry edgeFrom;
        PrepareCHEntry edgeTo;
        int origEdgeCount;

        public ShortcutHandlerData(PrepareCHEntry edgeFrom, PrepareCHEntry edgeTo, int origEdgeCount) {
            this.edgeFrom = edgeFrom;
            this.edgeTo = edgeTo;
            this.origEdgeCount = origEdgeCount;
        }
    }

    /**
     * Calls the shortcut handler for all edges and shortcuts adjacent to the given node. After this method is called
     * these edges and shortcuts will be removed from the prepare graph, so this method offers the last chance to deal
     * with them.
     */
    private void insertShortcuts(int node) {
        insertOutShortcuts(node);
        insertInShortcuts(node);
    }

    private void insertOutShortcuts(int node) {
        PrepareGraphEdgeIterator iter = outEdgeExplorer.setBaseNode(node);
        while (iter.next()) {
            if (!iter.isShortcut())
                continue;
            int shortcut = chBuilder.addShortcutEdgeBased(node, iter.getAdjNode(),
                    PrepareEncoder.getScFwdDir(), iter.getWeight(),
                    iter.getSkipped1(), iter.getSkipped2(),
                    iter.getOrigEdgeKeyFirst(),
                    iter.getOrigEdgeKeyLast());
            prepareGraph.setShortcutForPrepareEdge(iter.getPrepareEdge(), prepareGraph.getOriginalEdges() + shortcut);
            addedShortcutsCount++;
        }
    }

    private void insertInShortcuts(int node) {
        PrepareGraphEdgeIterator iter = inEdgeExplorer.setBaseNode(node);
        while (iter.next()) {
            if (!iter.isShortcut())
                continue;
            // we added loops already using the outEdgeExplorer
            if (iter.getAdjNode() == node)
                continue;
            int shortcut = chBuilder.addShortcutEdgeBased(node, iter.getAdjNode(),
                    PrepareEncoder.getScBwdDir(), iter.getWeight(),
                    iter.getSkipped1(), iter.getSkipped2(),
                    iter.getOrigEdgeKeyFirst(),
                    iter.getOrigEdgeKeyLast());
            prepareGraph.setShortcutForPrepareEdge(iter.getPrepareEdge(), prepareGraph.getOriginalEdges() + shortcut);
            addedShortcutsCount++;
        }
    }

    private void countPreviousEdges(int node) {
        // todo: this edge counting can probably be simplified, but we might need to re-optimize heuristic parameters then
        PrepareGraphEdgeIterator outIter = outEdgeExplorer.setBaseNode(node);
        while (outIter.next()) {
            numAllEdges++;
            numPrevEdges++;
            numPrevOrigEdges += outIter.getOrigEdgeCount();
        }

        PrepareGraphEdgeIterator inIter = inEdgeExplorer.setBaseNode(node);
        while (inIter.next()) {
            numAllEdges++;
            // do not consider loop edges a second time
            if (inIter.getBaseNode() == inIter.getAdjNode())
                continue;
            numPrevEdges++;
            numPrevOrigEdges += inIter.getOrigEdgeCount();
        }
    }

    private void updateHierarchyDepthsOfNeighbors(int node, IntContainer neighbors) {
        int level = hierarchyDepths[node];
        for (IntCursor n : neighbors) {
            if (n.value == node)
                continue;
            hierarchyDepths[n.value] = Math.max(hierarchyDepths[n.value], level + 1);
        }
    }

    private PrepareCHEntry addShortcutsToPrepareGraph(PrepareCHEntry edgeFrom, PrepareCHEntry edgeTo, int origEdgeCount) {
        if (edgeTo.parent.prepareEdge != edgeFrom.prepareEdge) {
            // counting origEdgeCount correctly is tricky with loop shortcuts and the recursion we use here. so we
            // simply ignore this, it probably does not matter that much
            PrepareCHEntry prev = addShortcutsToPrepareGraph(edgeFrom, edgeTo.getParent(), origEdgeCount);
            return doAddShortcut(prev, edgeTo, origEdgeCount);
        } else {
            return doAddShortcut(edgeFrom, edgeTo, origEdgeCount);
        }
    }

    private PrepareCHEntry doAddShortcut(PrepareCHEntry edgeFrom, PrepareCHEntry edgeTo, int origEdgeCount) {
        int from = edgeFrom.parent.adjNode;
        int adjNode = edgeTo.adjNode;

        final PrepareGraphEdgeIterator iter = existingShortcutExplorer.setBaseNode(from);
        while (iter.next()) {
            if (!isSameShortcut(iter, adjNode, edgeFrom.firstEdgeKey, edgeTo.incEdgeKey)) {
                // this is some other (shortcut) edge -> we do not care
                continue;
            }
            final double existingWeight = iter.getWeight();
            if (existingWeight <= edgeTo.weight) {
                // our shortcut already exists with lower weight --> do nothing
                PrepareCHEntry entry = new PrepareCHEntry(iter.getPrepareEdge(), iter.getOrigEdgeKeyFirst(), iter.getOrigEdgeKeyLast(), adjNode, existingWeight, origEdgeCount);
                entry.parent = edgeFrom.parent;
                return entry;
            } else {
                // update weight
                iter.setSkippedEdges(edgeFrom.prepareEdge, edgeTo.prepareEdge);
                iter.setWeight(edgeTo.weight);
                iter.setOrigEdgeCount(origEdgeCount);
                PrepareCHEntry entry = new PrepareCHEntry(iter.getPrepareEdge(), iter.getOrigEdgeKeyFirst(), iter.getOrigEdgeKeyLast(), adjNode, edgeTo.weight, origEdgeCount);
                entry.parent = edgeFrom.parent;
                return entry;
            }
        }

        // our shortcut is new --> add it
        int origFirstKey = edgeFrom.firstEdgeKey;
        LOGGER.trace("Adding shortcut from {} to {}, weight: {}, firstOrigEdgeKey: {}, lastOrigEdgeKey: {}",
                from, adjNode, edgeTo.weight, origFirstKey, edgeTo.incEdgeKey);
        int prepareEdge = prepareGraph.addShortcut(from, adjNode, origFirstKey, edgeTo.incEdgeKey, edgeFrom.prepareEdge, edgeTo.prepareEdge, edgeTo.weight, origEdgeCount);
        // does not matter here
        int incEdgeKey = -1;
        PrepareCHEntry entry = new PrepareCHEntry(prepareEdge, origFirstKey, incEdgeKey, edgeTo.adjNode, edgeTo.weight, origEdgeCount);
        entry.parent = edgeFrom.parent;
        return entry;
    }

    private boolean isSameShortcut(PrepareGraphEdgeIterator iter, int adjNode, int firstOrigEdgeKey, int lastOrigEdgeKey) {
        return iter.isShortcut()
                && (iter.getAdjNode() == adjNode)
                && (iter.getOrigEdgeKeyFirst() == firstOrigEdgeKey)
                && (iter.getOrigEdgeKeyLast() == lastOrigEdgeKey);
    }

    private void resetEdgeCounters() {
        numShortcuts = 0;
        numPrevEdges = 0;
        numOrigEdges = 0;
        numPrevOrigEdges = 0;
        numAllEdges = 0;
    }

    @Override
    public void close() {
        prepareGraph.close();
        inEdgeExplorer = null;
        outEdgeExplorer = null;
        existingShortcutExplorer = null;
        sourceNodeOrigInEdgeExplorer = null;
        chBuilder = null;
        sourceNodes.release();
        addedShortcuts.release();
        hierarchyDepths = null;
    }

    private Stats stats() {
        return activeStats;
    }

    @FunctionalInterface
    private interface PrepareShortcutHandler {
        void handleShortcut(PrepareCHEntry edgeFrom, PrepareCHEntry edgeTo, int origEdgeCount);
    }

    private void countShortcuts(PrepareCHEntry edgeFrom, PrepareCHEntry edgeTo, int origEdgeCount) {
        int fromNode = edgeFrom.parent.adjNode;
        int toNode = edgeTo.adjNode;
        int firstOrigEdgeKey = edgeFrom.firstEdgeKey;
        int lastOrigEdgeKey = edgeTo.incEdgeKey;

        // check if this shortcut already exists
        final PrepareGraphEdgeIterator iter = existingShortcutExplorer.setBaseNode(fromNode);
        while (iter.next()) {
            if (isSameShortcut(iter, toNode, firstOrigEdgeKey, lastOrigEdgeKey)) {
                // this shortcut exists already, maybe its weight will be updated but we should not count it as
                // a new edge
                return;
            }
        }

        // this shortcut is new --> increase counts
        while (edgeTo != edgeFrom) {
            numShortcuts++;
            edgeTo = edgeTo.parent;
        }
        numOrigEdges += origEdgeCount;
    }

    long getNumPolledEdges() {
        return wpsStatsContr.numPolls + wpsStatsHeur.numPolls;
    }

    public static class Params {
        private float edgeQuotientWeight = 100;
        private float originalEdgeQuotientWeight = 100;
        private float hierarchyDepthWeight = 20;
        // Increasing these parameters (heuristic especially) will lead to a longer preparation time but also to fewer
        // shortcuts and possibly (slightly) faster queries.
        private double maxPollFactorHeuristic = 5;
        private double maxPollFactorContraction = 200;
    }

    private static class Stats {
        int nodes;
        StopWatch stopWatch = new StopWatch();

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "time: %7.2fs, nodes: %10s", stopWatch.getCurrentSeconds(), nf(nodes));
        }
    }

}
