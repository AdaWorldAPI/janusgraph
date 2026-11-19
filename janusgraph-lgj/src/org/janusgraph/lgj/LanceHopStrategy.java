// Copyright 2026 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.lgj;

import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.graphdb.tinkerpop.optimize.strategy.AdjacentVertexFilterOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.strategy.AdjacentVertexHasIdOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.strategy.AdjacentVertexHasUniquePropertyOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.strategy.AdjacentVertexIsOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.strategy.JanusGraphLocalQueryOptimizerStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.strategy.JanusGraphMultiQueryStrategy;
import org.janusgraph.graphdb.tinkerpop.optimize.strategy.JanusGraphStepStrategy;

/**
 * A provider strategy that answers one traversal shape of a JanusGraph traversal source from a
 * lance-graph-java row store, as a chain of mask hops, and leaves every other traversal to
 * JanusGraph.
 *
 * <p>The lowered shape is exactly
 *
 * <pre>{@code g.V(ids).out(label)^k.dedup().count()}</pre>
 *
 * with k &gt;= 1 and one mapped edge label per hop. It is the shape lance-graph-java's own
 * TinkerPop harness lowers; this class is that strategy for JanusGraph. Two things differ:
 *
 * <ul>
 *   <li><b>Vertex ids.</b> A JanusGraph vertex id is not a row number. The caller supplies the
 *       mapping from one to the other; an id it maps outside the store is not lowered.
 *   <li><b>Order.</b> It declares JanusGraph's own provider strategies as coming after it, so it
 *       sees plain TinkerPop steps and JanusGraph never starts a storage read for a traversal
 *       this strategy answers.
 * </ul>
 *
 * <p>Every decision is counted: {@link #lowered()} and {@link #fellBack()}, with the reason for
 * the most recent fallback. A fallback is never an error; JanusGraph still answers.
 *
 * <p>For a lowered traversal JanusGraph's storage is not read at all. Keeping the row store and
 * the graph consistent is the caller's job.
 */
public final class LanceHopStrategy
        extends AbstractTraversalStrategy<ProviderOptimizationStrategy>
        implements ProviderOptimizationStrategy {

    private static final Set<Class<? extends ProviderOptimizationStrategy>> POSTS = Set.of(
            JanusGraphStepStrategy.class,
            JanusGraphLocalQueryOptimizerStrategy.class,
            JanusGraphMultiQueryStrategy.class,
            AdjacentVertexFilterOptimizerStrategy.class,
            AdjacentVertexHasIdOptimizerStrategy.class,
            AdjacentVertexIsOptimizerStrategy.class,
            AdjacentVertexHasUniquePropertyOptimizerStrategy.class);

    private final RowStore store;
    private final Map<String, Integer> edgeClassids;
    private final LongUnaryOperator vertexIdToRow;
    private final AtomicLong lowered = new AtomicLong();
    private final AtomicLong fellBack = new AtomicLong();
    private volatile String lastFallbackReason = "";

    /**
     * @param store the row store lowered traversals are answered from
     * @param edgeClassids Gremlin edge label to lance-graph edge classid; a label not in the map
     *     is not lowered
     * @param vertexIdToRow JanusGraph vertex id to row number; a result outside
     *     {@code [0, store.rowCount())} means "not a row of this store"
     */
    public LanceHopStrategy(RowStore store, Map<String, Integer> edgeClassids,
                            LongUnaryOperator vertexIdToRow) {
        this.store = store;
        this.edgeClassids = Map.copyOf(edgeClassids);
        this.vertexIdToRow = vertexIdToRow;
    }

    /** Number of traversals answered by the mask hop chain. */
    public long lowered() {
        return lowered.get();
    }

    /** Number of root traversals left to JanusGraph. */
    public long fellBack() {
        return fellBack.get();
    }

    /** Why the most recent fallback happened; empty if none has. */
    public String lastFallbackReason() {
        return lastFallbackReason;
    }

    @Override
    public Set<Class<? extends ProviderOptimizationStrategy>> applyPost() {
        return POSTS;
    }

    @Override
    public void apply(Traversal.Admin<?, ?> traversal) {
        // Only the root traversal is a candidate, and only the root is counted.
        if (!(traversal.getParent() instanceof EmptyStep)) {
            return;
        }
        String reason = plan(traversal);
        if (reason != null) {
            fellBack.incrementAndGet();
            lastFallbackReason = reason;
        }
    }

    /** Lowers the traversal and returns null, or leaves it untouched and returns the reason. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String plan(Traversal.Admin<?, ?> traversal) {
        List<Step> steps = new ArrayList<>();
        for (Step s : traversal.getSteps()) {
            if (!(s instanceof NoOpBarrierStep)) {
                steps.add(s);
            }
        }
        if (steps.size() < 4) {
            return "too few steps for V().out()..dedup().count()";
        }
        if (!(steps.get(0) instanceof GraphStep<?, ?> start) || !start.returnsVertex()) {
            return "does not start with V()";
        }
        if (start instanceof HasContainerHolder holder && !holder.getHasContainers().isEmpty()) {
            return "has() on the start step";
        }
        long[] seeds = seedRows(start.getIds());
        if (seeds == null) {
            return "V() needs explicit vertex ids that map to rows of the row store";
        }
        int last = steps.size() - 1;
        if (!(steps.get(last) instanceof CountGlobalStep)) {
            return "does not end with count()";
        }
        if (!(steps.get(last - 1) instanceof DedupGlobalStep<?> dedup)
                || !dedup.getLocalChildren().isEmpty()
                || !dedup.getScopeKeys().isEmpty()) {
            return "count() without a plain dedup() counts walks, not vertices";
        }
        int[] hops = new int[last - 2];
        for (int i = 1; i < last - 1; i++) {
            if (steps.get(i) instanceof HasContainerHolder) {
                return "has() at step " + i;
            }
            if (!(steps.get(i) instanceof VertexStep<?> v)) {
                return "step " + i + " is not out(label): " + steps.get(i);
            }
            if (v.getDirection() != Direction.OUT || !v.returnsVertex()) {
                return "step " + i + " is not an out() to vertices";
            }
            if (v.getEdgeLabels().length != 1) {
                return "step " + i + " needs exactly one edge label";
            }
            Integer classid = edgeClassids.get(v.getEdgeLabels()[0]);
            if (classid == null) {
                return "edge label '" + v.getEdgeLabels()[0] + "' has no lance-graph classid";
            }
            hops[i - 1] = classid;
        }
        for (Step s : steps) {
            if (!s.getLabels().isEmpty()) {
                return "as() labels would be lost";
            }
        }
        TraversalHelper.removeAllSteps(traversal);
        traversal.addStep(new LanceHopCountStep(traversal, store, seeds, hops));
        lowered.incrementAndGet();
        return null;
    }

    /** Row numbers for V(ids), or null when any id does not map to a row of this store. */
    private long[] seedRows(Object[] ids) {
        if (ids == null || ids.length == 0) {
            return null;
        }
        long rows = store.rowCount();
        long[] out = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Object id = ids[i] instanceof Vertex v ? v.id() : ids[i];
            if (!(id instanceof Long || id instanceof Integer || id instanceof Short)) {
                return null;
            }
            long row = vertexIdToRow.applyAsLong(((Number) id).longValue());
            if (row < 0 || row >= rows) {
                return null;
            }
            out[i] = row;
        }
        return out;
    }
}
