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

import com.adaworldapi.graph.Edge;
import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.janusgraph.graphdb.idmanagement.IDManager;

/**
 * Fixture and helpers shared by {@link JanusCrossingsTest} and {@link JanusAllocationTest}. The
 * fixture is the one {@link JanusLoweringTest} uses: seed {@code 0xF00D_CAFE}, gate 0, radius 25,
 * KNOWS as the edge class, the vertex label of a row read from facet 3 as {@code "c" + classid}.
 */
final class MeasureSupport {

    private MeasureSupport() {}

    static final String KNOWS = "knows";
    static final int FACETS_PER_ROW = 32;
    static final long SEED = 0xF00D_CAFEL;
    static final int RADIUS = 25;
    static final int LABEL_FACET = 3;
    /** The vertex property key {@link #strategy} registers, and the facet it is the payload of. */
    static final String PROP = "p";
    static final int PROP_FACET = 7;
    /** A label with a non-trivial answer at k = 1, 2 and 3 on the 2,000-row store. */
    static final String LABEL = "c10";
    /** The seed rows {@link JanusLoweringTest} uses. */
    static final long[] SEED_ROWS = {5, 42, 79, 116, 153, 190, 227, 264, 301, 338};

    /** The four lowered shapes. */
    enum Shape {
        COUNT("count"),
        IDS("id"),
        LABEL_COUNT("hasLabel+count"),
        LABEL_IDS("hasLabel+id");

        final String text;

        Shape(String text) {
            this.text = text;
        }

        boolean returnsIds() {
            return this == IDS || this == LABEL_IDS;
        }

        boolean filtered() {
            return this == LABEL_COUNT || this == LABEL_IDS;
        }

        /** {@code V(seeds).out(KNOWS)^k [.hasLabel(L)] .dedup() .count()|.id()}, not yet planned. */
        Traversal.Admin<?, ?> build(GraphTraversalSource g, Object[] seeds, int k) {
            GraphTraversal<Vertex, Vertex> t = outN(g.V(seeds), k);
            if (filtered()) {
                t = t.hasLabel(LABEL);
            }
            t = t.dedup();
            return returnsIds() ? t.id().asAdmin() : t.count().asAdmin();
        }
    }

    static RowStore openStore(long rows) {
        return RowStore.openWithEdges(rows, SEED, Edge.KNOWS, 0x0L, RADIUS);
    }

    static JanusGraph open() {
        return JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .set("graph.set-vertex-id", true)
                .open();
    }

    /** JanusGraph vertex id of a row. Ids must be positive, so row r is user id r + 1. */
    static long vid(IDManager ids, long row) {
        return ids.toVertexId(row + 1);
    }

    /** The strategy with every optional part: both id mappings and the label mapping. */
    static LanceHopStrategy strategy(RowStore store, IDManager ids) {
        return LanceHopStrategy.builder(store, Map.of(KNOWS, Edge.KNOWS),
                        id -> ids.fromVertexId(id) - 1)
                .rowToVertexId(row -> vid(ids, row))
                .vertexLabels(FacetId.of(LABEL_FACET), Map.of("c0", 0, "c10", 10))
                .vertexProperty(PROP, FacetId.of(PROP_FACET))
                .build();
    }

    /**
     * Test-side choice of a value to filter on: the property of the middle row of the frontier
     * {@code V(seedRows).out(KNOWS)^k}, so the lowered filter has something to find. Chosen
     * here; the lowered traversal does not use this.
     */
    static long propertyValue(RowStore store, long[] seedRows, int k) {
        int[] hops = new int[k];
        java.util.Arrays.fill(hops, Edge.KNOWS);
        long[] rows = HopChain.run(store, seedRows, hops, com.adaworldapi.graph.Graph::materializeRows);
        return rows.length == 0 ? 0L
                : store.payloadLow64At(rows[rows.length / 2], FacetId.of(PROP_FACET));
    }

    /** {@code V(seeds).out(KNOWS)^k [.hasLabel(LABEL)] .has(PROP, value) .dedup() .count()|.id()}. */
    static Traversal.Admin<?, ?> propertyShape(GraphTraversalSource g, Object[] seeds, int k,
                                               long value, boolean withLabel, boolean ids) {
        GraphTraversal<Vertex, Vertex> t = outN(g.V(seeds), k);
        if (withLabel) {
            t = t.hasLabel(LABEL);
        }
        t = t.has(PROP, value).dedup();
        return ids ? t.id().asAdmin() : t.count().asAdmin();
    }

    static Object[] seedIds(IDManager ids, long[] rows) {
        Object[] out = new Object[rows.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = vid(ids, rows[i]);
        }
        return out;
    }

    /** {@code count} rows, {@code 0, stride, 2 * stride, ...}. */
    static long[] strideRows(int count, long stride) {
        long[] rows = new long[count];
        for (int i = 0; i < count; i++) {
            rows[i] = i * stride;
        }
        return rows;
    }

    static GraphTraversal<Vertex, Vertex> outN(GraphTraversal<Vertex, Vertex> t, int k) {
        for (int i = 0; i < k; i++) {
            t = t.out(KNOWS);
        }
        return t;
    }

    /** Runs a planned traversal to exhaustion and returns what it produced. */
    static List<Object> drain(Traversal.Admin<?, ?> t) {
        List<Object> out = new ArrayList<>();
        while (t.hasNext()) {
            out.add(t.next());
        }
        return out;
    }

    /** The size of the answer: the count for a count shape, the number of ids for an id shape. */
    static long answerSize(Shape shape, List<Object> answer) {
        return shape.returnsIds() ? answer.size() : (Long) answer.get(0);
    }

    static boolean isOneLoweredStep(Traversal.Admin<?, ?> t) {
        return t.getSteps().size() == 1
                && (t.getSteps().get(0) instanceof LanceHopCountStep
                        || t.getSteps().get(0) instanceof LanceHopIdsStep);
    }

    /** Test-oracle copy of a row store into JanusGraph: every KNOWS facet becomes one edge. */
    static long copyInto(JanusGraph jg, IDManager ids, RowStore store) {
        long n = store.rowCount();
        Vertex[] vs = new Vertex[(int) n];
        for (long row = 0; row < n; row++) {
            vs[(int) row] = jg.addVertex(T.id, vid(ids, row), T.label,
                    "c" + store.classidAt(row, FacetId.of(LABEL_FACET)));
        }
        long edges = 0;
        for (long row = 0; row < n; row++) {
            for (int f = 0; f < FACETS_PER_ROW; f++) {
                FacetId facet = FacetId.of(f);
                if (store.classidAt(row, facet) != Edge.KNOWS) {
                    continue;
                }
                if (store.payloadHi32At(row, facet) != 0) {
                    continue;
                }
                long target = store.payloadLow64At(row, facet);
                if (target >= 0 && target < n) {
                    vs[(int) row].addEdge(KNOWS, vs[(int) target]);
                    edges++;
                }
            }
        }
        jg.tx().commit();
        return edges;
    }
}
