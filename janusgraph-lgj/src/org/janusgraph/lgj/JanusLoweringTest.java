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
import com.adaworldapi.lancegraph.Checks;
import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.RowStore;

import java.util.Map;
import java.util.function.Function;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.idmanagement.IDManager;

/**
 * The lowering strategy on a real JanusGraph traversal source (in-memory backend).
 *
 * <p>One synthetic graph is held twice: as a lance-graph-java row store, and copied edge by edge
 * into JanusGraph. The copy is test code; it is the oracle. Then:
 *
 * <ul>
 *   <li><b>Same answers.</b> For k = 1, 2, 3 the lowered traversal equals plain JanusGraph.
 *   <li><b>The comparison can fail.</b> With the id mapping off by one row the counts differ.
 *   <li><b>Really lowered.</b> The traversal that ran is one {@link LanceHopCountStep}.
 *   <li><b>Provenance.</b> Against an EMPTY JanusGraph the lowered traversal still returns the
 *       row store's answer, and plain JanusGraph returns 0. The answer came from the row store.
 *   <li><b>Fallbacks.</b> Shapes that are not lowered are counted and answer as JanusGraph does.
 * </ul>
 */
public final class JanusLoweringTest {

    private JanusLoweringTest() {}

    private static final String KNOWS = "knows";
    private static final int FACETS_PER_ROW = 32;
    private static final long ROWS = 2000L;
    private static final long[] SEED_ROWS = {5, 42, 79, 116, 153, 190, 227, 264, 301, 338};

    public static void main(String[] args) {
        System.out.println("JanusLoweringTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("JanusLoweringTest"));
        }
        Checks c = new Checks("JanusLoweringTest");
        try (RowStore store = RowStore.openWithEdges(ROWS, 0xF00D_CAFEL, Edge.KNOWS, 0x0L, 25)) {
            JanusGraph jg = open();
            IDManager ids = ((StandardJanusGraph) jg).getIDManager();
            long edges = copyInto(jg, ids, store);
            c.section("fixture");
            c.that("JanusGraph copy has edges (" + edges + "), so the comparison is not vacuous",
                    edges > 0);
            parity(c, store, jg, ids);
            provenance(c, store, ids);
            fallbacks(c, store, jg, ids);
            jg.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.exit(c.report());
    }

    private static JanusGraph open() {
        return JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .set("graph.set-vertex-id", true)
                .open();
    }

    /** JanusGraph vertex id of a row. Ids must be positive, so row r is user id r + 1. */
    private static long vid(IDManager ids, long row) {
        return ids.toVertexId(row + 1);
    }

    private static LanceHopStrategy strategy(RowStore store, IDManager ids) {
        return new LanceHopStrategy(store, Map.of(KNOWS, Edge.KNOWS),
                id -> ids.fromVertexId(id) - 1);
    }

    private static Object[] seedIds(IDManager ids) {
        Object[] out = new Object[SEED_ROWS.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = vid(ids, SEED_ROWS[i]);
        }
        return out;
    }

    /** Test-oracle copy: every KNOWS facet becomes one JanusGraph edge, duplicates kept. */
    private static long copyInto(JanusGraph jg, IDManager ids, RowStore store) {
        long n = store.rowCount();
        Vertex[] vs = new Vertex[(int) n];
        for (long row = 0; row < n; row++) {
            vs[(int) row] = jg.addVertex(T.id, vid(ids, row));
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

    private static GraphTraversal<Vertex, Vertex> outN(GraphTraversal<Vertex, Vertex> t, int k) {
        for (int i = 0; i < k; i++) {
            t = t.out(KNOWS);
        }
        return t;
    }

    private static boolean ranAsOneLoweredStep(Traversal.Admin<?, ?> t) {
        return t.getSteps().size() == 1 && t.getSteps().get(0) instanceof LanceHopCountStep;
    }

    private static void parity(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("same answers: lowered equals plain JanusGraph");
        Object[] seeds = seedIds(ids);
        for (int k = 1; k <= 3; k++) {
            long plain = outN(jg.traversal().V(seeds), k).dedup().count().next();
            LanceHopStrategy s = strategy(store, ids);
            Traversal.Admin<?, Long> t =
                    outN(jg.traversal().withStrategies(s).V(seeds), k).dedup().count().asAdmin();
            long lowered = t.next();
            c.eq("k=" + k + ": lowered count equals JanusGraph's", plain, lowered);
            c.that("k=" + k + ": the traversal ran as one LanceHopCountStep (" + t + ")",
                    ranAsOneLoweredStep(t));
            c.eq("k=" + k + ": counted as lowered", 1L, s.lowered());
            c.eq("k=" + k + ": not counted as a fallback", 0L, s.fellBack());
            c.that("k=" + k + ": the answer is not trivially zero (" + plain + ")", plain > 0);
        }
        LanceHopStrategy r = strategy(store, ids);
        long viaRepeat = jg.traversal().withStrategies(r).V(seeds)
                .repeat(org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.out(KNOWS))
                .times(2).dedup().count().next();
        long plain2 = outN(jg.traversal().V(seeds), 2).dedup().count().next();
        c.eq("repeat(out).times(2): equals JanusGraph's", plain2, viaRepeat);
        c.eq("repeat(out).times(2): counted as lowered", 1L, r.lowered());
        // A clone of a lowered traversal that has already run answers afresh, as any Gremlin
        // traversal clone does.
        LanceHopStrategy cs = strategy(store, ids);
        Traversal.Admin<?, Long> ran =
                outN(jg.traversal().withStrategies(cs).V(seeds), 2).dedup().count().asAdmin();
        long first = ran.next();
        c.that("a traversal that has run is exhausted", !ran.hasNext());
        Traversal.Admin<?, Long> copy = ran.clone();
        c.that("clone of a run traversal: has a result", copy.hasNext());
        long again = copy.hasNext() ? copy.next() : -1L;
        c.eq("clone of a run traversal: same count", first, again);
        // Falsifier: the comparison above can fail. With the id mapping off by one row the
        // lowered traversal starts from other vertices and must not agree on every k.
        boolean differs = false;
        for (int k = 1; k <= 3; k++) {
            long plain = outN(jg.traversal().V(seeds), k).dedup().count().next();
            LanceHopStrategy wrong = new LanceHopStrategy(store, Map.of(KNOWS, Edge.KNOWS),
                    id -> ids.fromVertexId(id));
            long lowered =
                    outN(jg.traversal().withStrategies(wrong).V(seeds), k).dedup().count().next();
            differs |= plain != lowered;
        }
        c.that("falsifier: an id mapping off by one row does not reproduce JanusGraph's counts",
                differs);
        jg.tx().rollback();
    }

    private static void provenance(Checks c, RowStore store, IDManager ids) {
        c.section("provenance: the answer comes from the row store, not from JanusGraph");
        JanusGraph empty = open();
        IDManager emptyIds = ((StandardJanusGraph) empty).getIDManager();
        Object[] seeds = seedIds(emptyIds);
        long plain = outN(empty.traversal().V(seeds), 2).dedup().count().next();
        LanceHopStrategy s = strategy(store, emptyIds);
        long lowered = outN(empty.traversal().withStrategies(s).V(seeds), 2).dedup().count().next();
        c.eq("plain JanusGraph over an empty graph answers 0", 0L, plain);
        c.that("the lowered traversal over the same empty graph answers from the row store ("
                + lowered + ")", lowered > 0);
        c.eq("and it was lowered", 1L, s.lowered());
        empty.close();
    }

    private static void fallbacks(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("fallbacks: counted, and answered exactly as JanusGraph answers");
        Object[] seeds = seedIds(ids);
        fallback(c, store, jg, ids, "no dedup() (counts walks)",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).count());
        fallback(c, store, jg, ids, "unmapped edge label",
                g -> g.V(seeds).out("likes").dedup().count());
        fallback(c, store, jg, ids, "in() direction",
                g -> g.V(seeds).in(KNOWS).dedup().count());
        fallback(c, store, jg, ids, "V() without ids",
                g -> g.V().out(KNOWS).dedup().count());
        fallback(c, store, jg, ids, "has() between hops",
                g -> g.V(seeds).out(KNOWS).has("n", P.gt(1)).out(KNOWS).dedup().count());
        jg.tx().rollback();
    }

    private static void fallback(Checks c, RowStore store, JanusGraph jg, IDManager ids,
                                 String what,
                                 Function<GraphTraversalSource, GraphTraversal<?, Long>> query) {
        long plain = query.apply(jg.traversal()).next();
        LanceHopStrategy s = strategy(store, ids);
        Traversal.Admin<?, Long> t = query.apply(jg.traversal().withStrategies(s)).asAdmin();
        long got = t.next();
        c.eq(what + ": answer unchanged", plain, got);
        c.eq(what + ": counted as a fallback", 1L, s.fellBack());
        c.eq(what + ": not counted as lowered", 0L, s.lowered());
        c.that(what + ": a reason is recorded ('" + s.lastFallbackReason() + "')",
                !s.lastFallbackReason().isEmpty());
    }
}
