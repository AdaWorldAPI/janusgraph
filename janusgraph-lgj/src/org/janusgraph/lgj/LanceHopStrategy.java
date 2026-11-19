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

import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy.ProviderOptimizationStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.IdStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.T;
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
 * and, when a row-to-vertex-id mapping is given, also
 *
 * <pre>{@code g.V(ids).out(label)^k.dedup().id()}</pre>
 *
 * with k &gt;= 1 and one mapped edge label per hop. When the caller maps vertex labels to a label
 * facet (see {@link Builder#vertexLabels}), one {@code hasLabel(L)} with a single label may stand
 * between the last hop and {@code dedup()}:
 *
 * <pre>{@code g.V(ids).out(label)^k.hasLabel(L).dedup().count()}</pre>
 *
 * It is answered as the last frontier intersected, natively, with the mask of the rows whose
 * label facet carries the class id mapped to L. Only {@code hasLabel} with one string, that is
 * label equality, is lowered; it is not a general {@code has()} or {@code where()}.
 *
 * <p>When the caller registers a property key with a facet (see {@link Builder#vertexProperty}),
 * one equality filter on that key may stand in the same place:
 *
 * <pre>{@code g.V(ids).out(label)^k.has(key, value).dedup().count()}</pre>
 *
 * with a Long or Integer value (written {@code has(key, value)} or {@code has(key, P.eq(value))}).
 * It is answered as the last frontier intersected, natively, with the mask of the rows whose
 * facet register has a low 64 bit payload equal to the value. The high 32 bits of the register
 * are not compared (see {@link Builder#vertexProperty}). The filter may be combined with one
 * {@code hasLabel(L)} (both masks are intersected with the frontier). Any other predicate, an
 * unregistered key, a value that is not a Long or Integer, two property filters, or a property
 * filter between hops is not lowered. It is the shape lance-graph-java's own
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
    private final LongUnaryOperator rowToVertexId;
    private final FacetId labelFacet;
    private final Map<String, Integer> vertexLabelClassids;
    private final Map<String, FacetId> vertexProperties;
    private final AtomicLong lowered = new AtomicLong();
    private final AtomicLong fellBack = new AtomicLong();
    private volatile String lastFallbackReason = "";

    /**
     * @param store the row store lowered traversals are answered from
     * @param edgeClassids Gremlin edge label to lance-graph edge classid; a label not in the map
     *     is not lowered
     * @param vertexIdToRow JanusGraph vertex id to row number; a result outside
     *     {@code [0, store.rowCount())} means "not a row of this store"
     *     <p>Count-only: with this constructor the {@code id()} shape is not lowered.
     */
    public LanceHopStrategy(RowStore store, Map<String, Integer> edgeClassids,
                            LongUnaryOperator vertexIdToRow) {
        this(store, edgeClassids, vertexIdToRow, null);
    }

    /**
     * @param rowToVertexId row number to JanusGraph vertex id, the inverse of
     *     {@code vertexIdToRow}; without it the {@code id()} shape is not lowered
     */
    public LanceHopStrategy(RowStore store, Map<String, Integer> edgeClassids,
                            LongUnaryOperator vertexIdToRow, LongUnaryOperator rowToVertexId) {
        this(store, edgeClassids, vertexIdToRow, rowToVertexId, null, Map.of(), Map.of());
    }

    private LanceHopStrategy(RowStore store, Map<String, Integer> edgeClassids,
                             LongUnaryOperator vertexIdToRow, LongUnaryOperator rowToVertexId,
                             FacetId labelFacet, Map<String, Integer> vertexLabelClassids,
                             Map<String, FacetId> vertexProperties) {
        this.store = store;
        this.edgeClassids = Map.copyOf(edgeClassids);
        this.vertexIdToRow = vertexIdToRow;
        this.rowToVertexId = rowToVertexId;
        this.labelFacet = labelFacet;
        this.vertexLabelClassids = Map.copyOf(vertexLabelClassids);
        this.vertexProperties = Map.copyOf(vertexProperties);
    }

    /**
     * A builder for the optional parts, so the constructors do not grow. The three required
     * arguments are those of the three-argument constructor.
     */
    public static Builder builder(RowStore store, Map<String, Integer> edgeClassids,
                                  LongUnaryOperator vertexIdToRow) {
        return new Builder(store, edgeClassids, vertexIdToRow);
    }

    /** Builds a {@link LanceHopStrategy}; every part but the three required ones is optional. */
    public static final class Builder {
        private final RowStore store;
        private final Map<String, Integer> edgeClassids;
        private final LongUnaryOperator vertexIdToRow;
        private LongUnaryOperator rowToVertexId;
        private FacetId labelFacet;
        private Map<String, Integer> vertexLabelClassids = Map.of();
        private final Map<String, FacetId> vertexProperties = new java.util.HashMap<>();

        private Builder(RowStore store, Map<String, Integer> edgeClassids,
                        LongUnaryOperator vertexIdToRow) {
            this.store = java.util.Objects.requireNonNull(store, "store");
            this.edgeClassids = java.util.Objects.requireNonNull(edgeClassids, "edgeClassids");
            this.vertexIdToRow = java.util.Objects.requireNonNull(vertexIdToRow, "vertexIdToRow");
        }

        /** Row number to JanusGraph vertex id; without it the {@code id()} shape is not lowered. */
        public Builder rowToVertexId(LongUnaryOperator rowToVertexId) {
            this.rowToVertexId = rowToVertexId;
            return this;
        }

        /**
         * How a vertex label maps to the row store: the rows of that label are the rows whose
         * {@code labelFacet} carries the mapped class id. Without this, {@code hasLabel} is not
         * lowered; a label not in the map is not lowered either.
         */
        public Builder vertexLabels(FacetId labelFacet, Map<String, Integer> vertexLabelClassids) {
            this.labelFacet = java.util.Objects.requireNonNull(labelFacet, "labelFacet");
            this.vertexLabelClassids = java.util.Objects.requireNonNull(vertexLabelClassids,
                    "vertexLabelClassids");
            return this;
        }

        /**
         * Registers a vertex property key as the payload of a facet, so that
         * {@code has(key, value)} with a Long or Integer value can be lowered. A row has the
         * property value {@code v} exactly when the low 64 bits of that facet's 12 byte register
         * equal {@code v}.
         *
         * <p>The high 32 bits of the register are NOT compared: the native filter is
         * {@code maskOfFacetTernaryMatch(facet, value, 0, careLo64 = all ones, careHi32 = 0)}, and
         * with a zero high care the high 32 bits are a don't-care. This is the honest choice
         * because the facade documents {@code payloadLow64At} as "the low 64 payload bits" and
         * {@code payloadHi32At} as a separate field that is, for ordinary rows, unrelated noise;
         * a property whose value is the 64 bit low payload would otherwise match only rows whose
         * high part happens to be zero. The consequence: the caller must hold the property in the
         * low 64 bits, and two rows that differ only in the high 32 bits both match.
         * The facet's class id is not compared either.
         */
        public Builder vertexProperty(String key, FacetId facet) {
            vertexProperties.put(java.util.Objects.requireNonNull(key, "key"),
                    java.util.Objects.requireNonNull(facet, "facet"));
            return this;
        }

        public LanceHopStrategy build() {
            return new LanceHopStrategy(store, edgeClassids, vertexIdToRow, rowToVertexId,
                    labelFacet, vertexLabelClassids, vertexProperties);
        }
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
            return "too few steps for V().out()..dedup().count()/id()";
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
        boolean ids = steps.get(last) instanceof IdStep;
        if (!ids && !(steps.get(last) instanceof CountGlobalStep)) {
            return "does not end with count() or id()";
        }
        if (ids && rowToVertexId == null) {
            return "id() needs the row-to-vertex-id mapping";
        }
        if (!(steps.get(last - 1) instanceof DedupGlobalStep<?> dedup)
                || !dedup.getLocalChildren().isEmpty()
                || !dedup.getScopeKeys().isEmpty()) {
            return ids ? "id() without a plain dedup() lists walks, not vertices"
                    : "count() without a plain dedup() counts walks, not vertices";
        }
        // A HasStep right before dedup() is the candidate label filter. At this point it is still
        // a plain TinkerPop HasStep: JanusGraph's strategies, which would fold it into a
        // JanusGraphStep, run after this one.
        int hopsEnd = last - 1;
        HopFilter filter = null;
        if (steps.size() > 4 && steps.get(last - 2) instanceof HasStep<?> has) {
            hopsEnd = last - 2;
            Object reasonOrFilter = filterOf(has);
            if (reasonOrFilter instanceof String reason) {
                return reason;
            }
            filter = (HopFilter) reasonOrFilter;
        }
        int[] hops = new int[hopsEnd - 1];
        for (int i = 1; i < hopsEnd; i++) {
            if (steps.get(i) instanceof HasContainerHolder) {
                return "has()/hasLabel() at step " + i + " (a label filter is only lowered after the last hop)";
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
        traversal.addStep(ids
                ? new LanceHopIdsStep(traversal, store, seeds, hops, rowToVertexId, filter)
                : new LanceHopCountStep(traversal, store, seeds, hops, filter));
        lowered.incrementAndGet();
        return null;
    }

    /**
     * The filter a HasStep carries, as a {@link HopFilter}, or the reason (a String) why this
     * HasStep is not lowerable: at most one label equality and at most one property equality on
     * a registered key with a Long or Integer value.
     */
    private Object filterOf(HasStep<?> has) {
        if (!has.getLabels().isEmpty()) {
            return "as() labels would be lost";
        }
        List<HasContainer> containers = has.getHasContainers();
        if (containers.size() > 2) {
            return "has step carries " + containers.size()
                    + " conditions; at most one hasLabel with one label and one property equality are lowered";
        }
        FacetId lFacet = null;
        int lClassid = 0;
        FacetId pFacet = null;
        long pValue = 0;
        for (HasContainer hc : containers) {
            boolean isLabel = T.label.getAccessor().equals(hc.getKey());
            P<?> p = hc.getPredicate();
            boolean plainEq = p.getClass() == P.class && p.getBiPredicate() == Compare.eq;
            if (isLabel) {
                if (labelFacet == null) {
                    return "hasLabel() needs the vertex label mapping (label facet and label class ids)";
                }
                if (lFacet != null) {
                    return "has step carries " + containers.size()
                            + " conditions; exactly one hasLabel with one label is lowered";
                }
                if (!plainEq || !(hc.getValue() instanceof String)) {
                    return "hasLabel() with " + p + " is not a single-label equality";
                }
                Integer classid = vertexLabelClassids.get((String) hc.getValue());
                if (classid == null) {
                    return "vertex label '" + hc.getValue() + "' has no class id in the label facet";
                }
                lFacet = labelFacet;
                lClassid = classid;
            } else {
                FacetId facet = vertexProperties.get(hc.getKey());
                if (facet == null) {
                    return "has('" + hc.getKey() + "', ...) is a property filter on a key that is not"
                            + " registered with a facet, not a label filter";
                }
                if (pFacet != null) {
                    return "has step carries " + containers.size()
                            + " conditions; exactly one property equality is lowered";
                }
                if (!plainEq) {
                    return "property filter on '" + hc.getKey() + "' with " + p
                            + " is not an equality";
                }
                if (!(hc.getValue() instanceof Long || hc.getValue() instanceof Integer)) {
                    return "property filter on '" + hc.getKey() + "' needs a Long or Integer value, got "
                            + (hc.getValue() == null ? "null" : hc.getValue().getClass().getSimpleName());
                }
                pFacet = facet;
                pValue = ((Number) hc.getValue()).longValue();
            }
        }
        return new HopFilter(lFacet, lClassid, pFacet, pValue);
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
