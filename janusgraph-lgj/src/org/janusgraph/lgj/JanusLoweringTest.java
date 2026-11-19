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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
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
 *   <li><b>id().</b> The same checks for {@code dedup().id()}, answered by {@link
 *       LanceHopIdsStep}, compared as sets, with an off-by-one falsifier on the row-to-id
 *       mapping.
 *   <li><b>hasLabel.</b> {@code out^k.hasLabel(L).dedup().count()} and {@code ...id()}, answered
 *       as the last frontier intersected natively with the rows whose label facet carries the
 *       class id mapped to L. Parity with JanusGraph for two labels, provenance against an empty
 *       graph, two falsifiers (a wrong label to class id mapping, a wrong label facet), the
 *       fallbacks, and a clone after running.
 *   <li><b>has(key, value).</b> {@code out^k.has(p, v).dedup().count()} and {@code ...id()}, with
 *       a Long or Integer value, answered as the last frontier intersected natively with the rows
 *       whose property facet has v in its low 64 payload bits. The JanusGraph copy holds that
 *       payload as vertex property {@code p}. Parity, provenance, a wrong-facet falsifier, the
 *       combination with hasLabel, and the fallbacks.
 * </ul>
 */
public final class JanusLoweringTest {

    private JanusLoweringTest() {}

    private static final String KNOWS = "knows";
    private static final int FACETS_PER_ROW = 32;
    private static final long ROWS = 2000L;
    /** The facet whose class id is a row's vertex label: label "c" + classid. */
    private static final int LABEL_FACET = 3;
    /** Labels with non-trivial answers at k = 1, 2 and 3 on this graph. */
    private static final String[] LABELS = {"c0", "c10"};
    /** The vertex property key under test, and the facet whose low 64 payload bits hold it. */
    private static final String PROP = "p";
    private static final int PROP_FACET = 7;
    /** Another facet, for the falsifier. */
    private static final int WRONG_PROP_FACET = 8;
    /** A value no row of the store holds in any facet (checked in the test). */
    private static final long ABSENT = 0x7777_7777_7777_7777L;
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
            idParity(c, store, jg, ids);
            idProvenance(c, store, ids);
            idFallbacks(c, store, jg, ids);
            labelRepresentation(c, jg, ids);
            labelParity(c, store, jg, ids);
            labelIdParity(c, store, jg, ids);
            labelProvenance(c, store, ids);
            labelFalsifiers(c, store, jg, ids);
            labelFallbacks(c, store, jg, ids);
            labelReordered(c, store, jg, ids);
            labelClones(c, store, jg, ids);
            propParity(c, store, jg, ids);
            propIntegerAndForms(c, store, jg, ids);
            propWithLabel(c, store, jg, ids);
            propProvenance(c, store, ids);
            propFalsifier(c, store, jg, ids);
            propFallbacks(c, store, jg, ids);
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

    /** The label mapping the row store and JanusGraph agree on, for the given facet. */
    private static Map<String, Integer> labelMap() {
        return Map.of("c0", 0, "c10", 10);
    }

    /** Strategy with both id mappings and the vertex label mapping. */
    private static LanceHopStrategy labelStrategy(RowStore store, IDManager ids, FacetId facet,
                                                  Map<String, Integer> labels) {
        return LanceHopStrategy.builder(store, Map.of(KNOWS, Edge.KNOWS),
                        id -> ids.fromVertexId(id) - 1)
                .rowToVertexId(row -> vid(ids, row))
                .vertexLabels(facet, labels)
                .build();
    }

    private static LanceHopStrategy labelStrategy(RowStore store, IDManager ids) {
        return labelStrategy(store, ids, FacetId.of(LABEL_FACET), labelMap());
    }

    private static GraphTraversal<Vertex, Vertex> labelled(GraphTraversalSource g, Object[] seeds,
                                                          int k, String label) {
        return outN(g.V(seeds), k).hasLabel(label);
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
            vs[(int) row] = jg.addVertex(T.id, vid(ids, row), T.label,
                    "c" + store.classidAt(row, FacetId.of(LABEL_FACET)),
                    PROP, store.payloadLow64At(row, FacetId.of(PROP_FACET)));
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

    // ---- the id() shape: g.V(ids).out(label)^k.dedup().id() ----

    /** Strategy with both mappings, so the id() shape can be lowered. */
    private static LanceHopStrategy idStrategy(RowStore store, IDManager ids) {
        return new LanceHopStrategy(store, Map.of(KNOWS, Edge.KNOWS),
                id -> ids.fromVertexId(id) - 1, row -> vid(ids, row));
    }

    private static boolean ranAsOneIdsStep(Traversal.Admin<?, ?> t) {
        return t.getSteps().size() == 1 && t.getSteps().get(0) instanceof LanceHopIdsStep;
    }

    private static List<Object> sorted(List<Object> in) {
        List<Object> out = new ArrayList<>(in);
        out.sort(null);
        return out;
    }

    private static void idParity(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("id(): lowered ids equal plain JanusGraph's ids");
        Object[] seeds = seedIds(ids);
        boolean anyDiffers = false;
        for (int k = 1; k <= 3; k++) {
            Set<Object> plain = outN(jg.traversal().V(seeds), k).dedup().id().toSet();
            long counted = outN(jg.traversal().withStrategies(strategy(store, ids)).V(seeds), k)
                    .dedup().count().next();
            LanceHopStrategy s = idStrategy(store, ids);
            Traversal.Admin<?, Object> t =
                    outN(jg.traversal().withStrategies(s).V(seeds), k).dedup().id().asAdmin();
            List<Object> got = t.toList();
            Set<Object> gotSet = new HashSet<>(got);
            c.that("k=" + k + ": the id sets are equal", plain.equals(gotSet));
            c.eq("k=" + k + ": same number of ids as JanusGraph", (long) plain.size(),
                    (long) got.size());
            c.eq("k=" + k + ": no duplicate ids", (long) gotSet.size(), (long) got.size());
            c.eq("k=" + k + ": as many ids as the count shape counts", counted, (long) got.size());
            c.that("k=" + k + ": the traversal ran as one LanceHopIdsStep (" + t + ")",
                    ranAsOneIdsStep(t));
            c.eq("k=" + k + ": counted as lowered", 1L, s.lowered());
            c.eq("k=" + k + ": not counted as a fallback", 0L, s.fellBack());
            c.that("k=" + k + ": the answer is not trivially empty (" + plain.size() + ")",
                    !plain.isEmpty());
            // Falsifier: a row-to-id mapping off by one row must not reproduce JanusGraph's ids.
            LanceHopStrategy wrong = new LanceHopStrategy(store, Map.of(KNOWS, Edge.KNOWS),
                    id -> ids.fromVertexId(id) - 1, row -> ids.toVertexId(row + 2));
            Set<Object> wrongSet =
                    outN(jg.traversal().withStrategies(wrong).V(seeds), k).dedup().id().toSet();
            anyDiffers |= !plain.equals(wrongSet);
        }
        c.that("falsifier: a row-to-id mapping off by one row does not reproduce JanusGraph's ids",
                anyDiffers);
        // Order: ascending row order.
        List<Object> list = outN(jg.traversal().withStrategies(idStrategy(store, ids)).V(seeds), 2)
                .dedup().id().toList();
        boolean ascending = true;
        for (int i = 1; i < list.size(); i++) {
            ascending &= ids.fromVertexId((Long) list.get(i - 1))
                    < ids.fromVertexId((Long) list.get(i));
        }
        c.that("lowered ids come out in ascending row order (" + list.size() + " ids)",
                ascending && list.size() > 1);
        // Clone of a lowered id traversal that has already run answers afresh.
        Traversal.Admin<?, Object> ran = outN(
                jg.traversal().withStrategies(idStrategy(store, ids)).V(seeds), 2)
                .dedup().id().asAdmin();
        Set<Object> first = new HashSet<>(ran.toList());
        c.that("an id traversal that has run is exhausted", !ran.hasNext());
        Set<Object> again = new HashSet<>(ran.clone().toList());
        c.that("clone of a run id traversal: same ids (" + again.size() + ")",
                !again.isEmpty() && first.equals(again));
        jg.tx().rollback();
    }

    private static void idProvenance(Checks c, RowStore store, IDManager ids) {
        c.section("id() provenance: the ids come from the row store, not from JanusGraph");
        JanusGraph empty = open();
        IDManager emptyIds = ((StandardJanusGraph) empty).getIDManager();
        Object[] seeds = seedIds(emptyIds);
        Set<Object> plain = outN(empty.traversal().V(seeds), 2).dedup().id().toSet();
        LanceHopStrategy s = idStrategy(store, emptyIds);
        Set<Object> lowered =
                outN(empty.traversal().withStrategies(s).V(seeds), 2).dedup().id().toSet();
        c.eq("plain JanusGraph over an empty graph returns no ids", 0L, (long) plain.size());
        c.that("the lowered traversal over the same empty graph returns ids from the row store ("
                + lowered.size() + ")", !lowered.isEmpty());
        c.eq("and it was lowered", 1L, s.lowered());
        empty.close();
    }

    private static void idFallbacks(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("id() fallbacks: counted, and answered exactly as JanusGraph answers");
        Object[] seeds = seedIds(ids);
        List<Object> plainWalks = sorted(jg.traversal().V(seeds).out(KNOWS).out(KNOWS).id().toList());
        LanceHopStrategy a = idStrategy(store, ids);
        List<Object> walks = sorted(
                jg.traversal().withStrategies(a).V(seeds).out(KNOWS).out(KNOWS).id().toList());
        c.that("no dedup(): answer unchanged (" + walks.size() + " ids, walks)",
                plainWalks.equals(walks) && !walks.isEmpty());
        c.eq("no dedup(): counted as a fallback", 1L, a.fellBack());
        c.eq("no dedup(): not counted as lowered", 0L, a.lowered());
        c.that("no dedup(): a reason is recorded ('" + a.lastFallbackReason() + "')",
                a.lastFallbackReason().contains("dedup"));

        Set<Object> plain = outN(jg.traversal().V(seeds), 2).dedup().id().toSet();
        LanceHopStrategy b = strategy(store, ids);
        Set<Object> got = outN(jg.traversal().withStrategies(b).V(seeds), 2).dedup().id().toSet();
        c.that("3-argument constructor: answer unchanged (" + got.size() + " ids)",
                plain.equals(got) && !got.isEmpty());
        c.eq("3-argument constructor: counted as a fallback", 1L, b.fellBack());
        c.eq("3-argument constructor: not counted as lowered", 0L, b.lowered());
        c.that("3-argument constructor: the reason names the missing mapping ('"
                + b.lastFallbackReason() + "')",
                b.lastFallbackReason().contains("row-to-vertex-id"));
        jg.tx().rollback();
    }

    // ---- the hasLabel shape: g.V(ids).out(label)^k.hasLabel(L).dedup().count() / id() ----

    private static void labelRepresentation(Checks c, JanusGraph jg, IDManager ids) {
        c.section("hasLabel: what the strategy sees before JanusGraph's strategies run");
        Traversal.Admin<?, ?> t = jg.traversal().V(seedIds(ids)).out(KNOWS).hasLabel("c0")
                .dedup().count().asAdmin();
        System.out.println("  steps before any strategy: " + t.getSteps());
        HasStep<?> has = (HasStep<?>) t.getSteps().get(2);
        c.eq("hasLabel is a plain HasStep with one condition", 1L,
                (long) has.getHasContainers().size());
        HasContainer hc = has.getHasContainers().get(0);
        c.that("its key is T.label's accessor '~label' ('" + hc.getKey() + "')",
                T.label.getAccessor().equals(hc.getKey()));
        c.that("its predicate is eq on the String 'c0' (" + hc.getPredicate() + ")",
                hc.getPredicate().getBiPredicate()
                        == org.apache.tinkerpop.gremlin.process.traversal.Compare.eq
                        && "c0".equals(hc.getValue()));
        Traversal.Admin<?, ?> two = jg.traversal().V(seedIds(ids)).out(KNOWS)
                .hasLabel("c0").hasLabel("c10").dedup().count().asAdmin();
        c.eq("two hasLabel steps fold into ONE HasStep with two conditions", 2L,
                (long) ((HasStep<?>) two.getSteps().get(2)).getHasContainers().size());
        Traversal.Admin<?, ?> within = jg.traversal().V(seedIds(ids)).out(KNOWS)
                .hasLabel("c0", "c10").dedup().count().asAdmin();
        c.that("hasLabel with two labels is a within, not an eq",
                ((HasStep<?>) within.getSteps().get(2)).getHasContainers().get(0).getPredicate()
                        .getBiPredicate()
                        == org.apache.tinkerpop.gremlin.process.traversal.Contains.within);
    }

    private static void labelParity(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("hasLabel count: lowered equals plain JanusGraph");
        Object[] seeds = seedIds(ids);
        boolean narrows = false;
        for (String label : LABELS) {
            for (int k = 1; k <= 3; k++) {
                long unfiltered = outN(jg.traversal().V(seeds), k).dedup().count().next();
                long plain = labelled(jg.traversal(), seeds, k, label).dedup().count().next();
                LanceHopStrategy s = labelStrategy(store, ids);
                Traversal.Admin<?, Long> t =
                        labelled(jg.traversal().withStrategies(s), seeds, k, label)
                                .dedup().count().asAdmin();
                long lowered = t.next();
                System.out.println("  k=" + k + " " + label + ": unfiltered " + unfiltered
                        + ", JanusGraph " + plain + ", lowered " + lowered);
                String what = "k=" + k + " " + label + ": ";
                c.eq(what + "lowered count equals JanusGraph's", plain, lowered);
                c.that(what + "the traversal ran as one LanceHopCountStep (" + t + ")",
                        ranAsOneLoweredStep(t));
                c.eq(what + "counted as lowered", 1L, s.lowered());
                c.eq(what + "not counted as a fallback", 0L, s.fellBack());
                c.that(what + "the answer is not trivially zero (" + plain + ")", plain > 0);
                narrows |= plain > 0 && plain < unfiltered;
                if (k <= 2) {
                    c.that(what + "the filter narrows: " + plain + " is below the unfiltered "
                            + unfiltered, plain > 0 && plain < unfiltered);
                }
            }
        }
        c.that("the filter really narrows for at least one (k, label)", narrows);
        jg.tx().rollback();
    }

    private static void labelIdParity(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("hasLabel id(): lowered ids equal plain JanusGraph's ids");
        Object[] seeds = seedIds(ids);
        for (String label : LABELS) {
            for (int k = 1; k <= 3; k++) {
                Set<Object> plain = labelled(jg.traversal(), seeds, k, label).dedup().id().toSet();
                long counted = labelled(jg.traversal().withStrategies(labelStrategy(store, ids)),
                        seeds, k, label).dedup().count().next();
                LanceHopStrategy s = labelStrategy(store, ids);
                Traversal.Admin<?, Object> t =
                        labelled(jg.traversal().withStrategies(s), seeds, k, label)
                                .dedup().id().asAdmin();
                List<Object> got = t.toList();
                Set<Object> gotSet = new HashSet<>(got);
                String what = "k=" + k + " " + label + ": ";
                c.that(what + "the id sets are equal", plain.equals(gotSet));
                c.eq(what + "same number of ids as JanusGraph", (long) plain.size(),
                        (long) got.size());
                c.eq(what + "no duplicate ids", (long) gotSet.size(), (long) got.size());
                c.eq(what + "as many ids as the count shape counts", counted, (long) got.size());
                c.that(what + "the traversal ran as one LanceHopIdsStep (" + t + ")",
                        ranAsOneIdsStep(t));
                c.eq(what + "counted as lowered", 1L, s.lowered());
                c.eq(what + "not counted as a fallback", 0L, s.fellBack());
                c.that(what + "the answer is not trivially empty (" + plain.size() + ")",
                        !plain.isEmpty());
            }
        }
        jg.tx().rollback();
    }

    private static void labelProvenance(Checks c, RowStore store, IDManager ids) {
        c.section("hasLabel provenance: the answer comes from the row store, not from JanusGraph");
        JanusGraph empty = open();
        IDManager emptyIds = ((StandardJanusGraph) empty).getIDManager();
        Object[] seeds = seedIds(emptyIds);
        long plain = labelled(empty.traversal(), seeds, 2, "c10").dedup().count().next();
        LanceHopStrategy s = labelStrategy(store, emptyIds);
        long lowered = labelled(empty.traversal().withStrategies(s), seeds, 2, "c10")
                .dedup().count().next();
        c.eq("plain JanusGraph over an empty graph answers 0", 0L, plain);
        c.that("the lowered traversal over the same empty graph answers from the row store ("
                + lowered + ")", lowered > 0);
        c.eq("and it was lowered", 1L, s.lowered());
        Set<Object> plainIds = labelled(empty.traversal(), seeds, 2, "c10").dedup().id().toSet();
        LanceHopStrategy si = labelStrategy(store, emptyIds);
        Set<Object> loweredIds = labelled(empty.traversal().withStrategies(si), seeds, 2, "c10")
                .dedup().id().toSet();
        c.eq("plain JanusGraph over an empty graph returns no ids", 0L, (long) plainIds.size());
        c.that("the lowered id traversal returns ids from the row store (" + loweredIds.size()
                + ")", !loweredIds.isEmpty());
        c.eq("and the ids are as many as the count", lowered, (long) loweredIds.size());
        empty.close();
    }

    private static void labelFalsifiers(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("hasLabel falsifiers: the comparison can fail");
        Object[] seeds = seedIds(ids);
        // 1. A label mapped to a different class id.
        Map<String, Integer> swapped = Map.of("c0", 10, "c10", 0);
        boolean mappingDiffers = false;
        // 2. The right mapping read from a different facet.
        FacetId otherFacet = FacetId.of(LABEL_FACET + 1);
        boolean facetDiffers = false;
        for (String label : LABELS) {
            for (int k = 1; k <= 3; k++) {
                long plain = labelled(jg.traversal(), seeds, k, label).dedup().count().next();
                long viaMapping = labelled(jg.traversal().withStrategies(
                        labelStrategy(store, ids, FacetId.of(LABEL_FACET), swapped)),
                        seeds, k, label).dedup().count().next();
                long viaFacet = labelled(jg.traversal().withStrategies(
                        labelStrategy(store, ids, otherFacet, labelMap())),
                        seeds, k, label).dedup().count().next();
                mappingDiffers |= plain != viaMapping;
                facetDiffers |= plain != viaFacet;
            }
        }
        c.that("falsifier 1: a label mapped to a different class id does not reproduce "
                + "JanusGraph's counts", mappingDiffers);
        c.that("falsifier 2: the wrong label facet does not reproduce JanusGraph's counts",
                facetDiffers);
        boolean idsDiffer = false;
        for (String label : LABELS) {
            for (int k = 1; k <= 3; k++) {
                Set<Object> plain = labelled(jg.traversal(), seeds, k, label).dedup().id().toSet();
                idsDiffer |= !plain.equals(labelled(jg.traversal().withStrategies(
                        labelStrategy(store, ids, FacetId.of(LABEL_FACET), swapped)),
                        seeds, k, label).dedup().id().toSet());
                idsDiffer |= !plain.equals(labelled(jg.traversal().withStrategies(
                        labelStrategy(store, ids, otherFacet, labelMap())),
                        seeds, k, label).dedup().id().toSet());
            }
        }
        c.that("falsifiers on the id() shape: both wrong mappings change the id sets", idsDiffer);
        jg.tx().rollback();
    }

    private static void labelFallback(Checks c, JanusGraph jg, LanceHopStrategy s, String what,
                                      String reasonPart,
                                      Function<GraphTraversalSource, GraphTraversal<?, Long>> q) {
        long plain = q.apply(jg.traversal()).next();
        Traversal.Admin<?, Long> t = q.apply(jg.traversal().withStrategies(s)).asAdmin();
        long got = t.next();
        c.eq(what + ": answer unchanged (" + plain + ")", plain, got);
        c.eq(what + ": counted as a fallback", 1L, s.fellBack());
        c.eq(what + ": not counted as lowered", 0L, s.lowered());
        c.that(what + ": the reason mentions '" + reasonPart + "' ('" + s.lastFallbackReason()
                + "')", s.lastFallbackReason().contains(reasonPart));
        c.that(what + ": no lowered step ran", !(t.getSteps().size() == 1
                && t.getSteps().get(0) instanceof LanceHopCountStep));
    }

    /** TinkerPop's own FilterRankingStrategy moves hasLabel before dedup(), so this still lowers. */
    private static void labelReordered(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("hasLabel written after dedup(): TinkerPop reorders it, so it is lowered");
        Object[] seeds = seedIds(ids);
        long plain = outN(jg.traversal().V(seeds), 2).dedup().hasLabel("c10").count().next();
        LanceHopStrategy s = labelStrategy(store, ids);
        Traversal.Admin<?, Long> t = outN(jg.traversal().withStrategies(s).V(seeds), 2)
                .dedup().hasLabel("c10").count().asAdmin();
        long got = t.next();
        c.eq("dedup().hasLabel(): answer equals JanusGraph's (" + plain + ")", plain, got);
        c.that("dedup().hasLabel(): ran as one lowered step (" + t + ")",
                ranAsOneLoweredStep(t) && s.lowered() == 1L);
        jg.tx().rollback();
    }

    private static void labelFallbacks(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("hasLabel fallbacks: counted, with a reason, answered as JanusGraph answers");
        Object[] seeds = seedIds(ids);
        labelFallback(c, jg, strategy(store, ids), "no label mapping given", "label mapping",
                g -> g.V(seeds).out(KNOWS).hasLabel("c10").dedup().count());
        labelFallback(c, jg, labelStrategy(store, ids), "label not in the mapping (c7)", "'c7'",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).hasLabel("c7").dedup().count());
        labelFallback(c, jg, labelStrategy(store, ids), "hasLabel with two labels (within)",
                "within",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).hasLabel("c0", "c10").dedup().count());
        labelFallback(c, jg, labelStrategy(store, ids), "has() on a property", "property",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has("n", "c0").dedup().count());
        labelFallback(c, jg, labelStrategy(store, ids), "hasLabel between hops", "step 2",
                g -> g.V(seeds).out(KNOWS).hasLabel("c10").out(KNOWS).dedup().count());
        labelFallback(c, jg, labelStrategy(store, ids), "two hasLabel steps", "2 conditions",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).hasLabel("c0").hasLabel("c10")
                        .dedup().count());
        labelFallback(c, jg, labelStrategy(store, ids), "hasLabel(P.neq)", "neq",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).hasLabel(P.neq("c0")).dedup().count());
        labelFallback(c, jg, labelStrategy(store, ids), "hasLabel without dedup()", "dedup",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).hasLabel("c10").count());
        // The same two refusals on the id() shape.
        for (Object[] cs : new Object[][] {{"within", "within"}, {"c7", "'c7'"}}) {
            String l = (String) cs[0];
            LanceHopStrategy s = labelStrategy(store, ids);
            Set<Object> plain = (l.equals("within")
                    ? outN(jg.traversal().V(seeds), 2).hasLabel("c0", "c10")
                    : outN(jg.traversal().V(seeds), 2).hasLabel(l)).dedup().id().toSet();
            Set<Object> got = (l.equals("within")
                    ? outN(jg.traversal().withStrategies(s).V(seeds), 2).hasLabel("c0", "c10")
                    : outN(jg.traversal().withStrategies(s).V(seeds), 2).hasLabel(l))
                    .dedup().id().toSet();
            c.that("id() " + l + ": answer unchanged (" + got.size() + " ids)",
                    plain.equals(got));
            c.eq("id() " + l + ": counted as a fallback", 1L, s.fellBack());
            c.eq("id() " + l + ": not counted as lowered", 0L, s.lowered());
            c.that("id() " + l + ": the reason mentions '" + cs[1] + "' ('"
                    + s.lastFallbackReason() + "')", s.lastFallbackReason().contains((String) cs[1]));
        }
        jg.tx().rollback();
    }

    private static void labelClones(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("hasLabel: a clone of a traversal that already ran answers afresh");
        Object[] seeds = seedIds(ids);
        Traversal.Admin<?, Long> ran = labelled(
                jg.traversal().withStrategies(labelStrategy(store, ids)), seeds, 2, "c10")
                .dedup().count().asAdmin();
        long first = ran.next();
        c.that("a filtered count traversal that has run is exhausted", !ran.hasNext());
        Traversal.Admin<?, Long> copy = ran.clone();
        c.that("clone: has a result", copy.hasNext());
        long again = copy.hasNext() ? copy.next() : -1L;
        c.that("clone: same count (" + first + ")", first > 0 && first == again);
        Traversal.Admin<?, Object> ranIds = labelled(
                jg.traversal().withStrategies(labelStrategy(store, ids)), seeds, 2, "c10")
                .dedup().id().asAdmin();
        Set<Object> firstIds = new HashSet<>(ranIds.toList());
        c.that("a filtered id traversal that has run is exhausted", !ranIds.hasNext());
        Set<Object> againIds = new HashSet<>(ranIds.clone().toList());
        c.that("clone: same ids (" + againIds.size() + ")",
                !againIds.isEmpty() && firstIds.equals(againIds));
        jg.tx().rollback();
    }

    // ---- the property equality shape: g.V(ids).out(label)^k.has(key, value).dedup().count() / id() ----

    private static LanceHopStrategy propStrategy(RowStore store, IDManager ids, FacetId facet) {
        return LanceHopStrategy.builder(store, Map.of(KNOWS, Edge.KNOWS),
                        id -> ids.fromVertexId(id) - 1)
                .rowToVertexId(row -> vid(ids, row))
                .vertexLabels(FacetId.of(LABEL_FACET), labelMap())
                .vertexProperty(PROP, facet)
                .build();
    }

    private static LanceHopStrategy propStrategy(RowStore store, IDManager ids) {
        return propStrategy(store, ids, FacetId.of(PROP_FACET));
    }

    private static GraphTraversal<Vertex, Vertex> withProp(GraphTraversalSource g, Object[] seeds,
                                                          int k, Object value) {
        return outN(g.V(seeds), k).has(PROP, value);
    }

    /** Row of a JanusGraph vertex id. */
    private static long rowOf(IDManager ids, Object vertexId) {
        return ids.fromVertexId((Long) vertexId) - 1;
    }

    /**
     * Test-side choice of values to filter on: the property of the first, a middle and the last
     * row (in row order) of the plain JanusGraph frontier, so that each value occurs.
     */
    private static long[] occurringValues(RowStore store, JanusGraph jg, IDManager ids,
                                          Object[] seeds, int k) {
        java.util.TreeSet<Long> values = new java.util.TreeSet<>();
        for (Object id : outN(jg.traversal().V(seeds), k).dedup().id().toList()) {
            values.add(store.payloadLow64At(rowOf(ids, id), FacetId.of(PROP_FACET)));
        }
        Long[] all = values.toArray(new Long[0]);
        if (all.length == 0) {
            return new long[0];
        }
        return new long[] {all[0], all[all.length / 2], all[all.length - 1]};
    }

    private static void propParity(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("has(key, value): lowered equals plain JanusGraph");
        Object[] seeds = seedIds(ids);
        boolean absentInStore = true;
        for (long row = 0; row < store.rowCount(); row++) {
            for (int f = 0; f < FACETS_PER_ROW; f++) {
                absentInStore &= store.payloadLow64At(row, FacetId.of(f)) != ABSENT;
            }
        }
        c.that("the 'never occurs' value occurs in no facet of the store", absentInStore);
        boolean narrows = false;
        for (int k = 1; k <= 3; k++) {
            long unfiltered = outN(jg.traversal().V(seeds), k).dedup().count().next();
            long[] values = occurringValues(store, jg, ids, seeds, k);
            c.eq("k=" + k + ": three occurring values chosen", 3L, (long) values.length);
            long[] tried = {values[0], values[1], values[2], ABSENT};
            for (long v : tried) {
                String what = "k=" + k + " p=" + v + ": ";
                long plain = withProp(jg.traversal(), seeds, k, v).dedup().count().next();
                Set<Object> plainIds = withProp(jg.traversal(), seeds, k, v).dedup().id().toSet();
                LanceHopStrategy s = propStrategy(store, ids);
                Traversal.Admin<?, Long> t = withProp(jg.traversal().withStrategies(s), seeds, k, v)
                        .dedup().count().asAdmin();
                long lowered = t.next();
                c.eq(what + "lowered count equals JanusGraph's", plain, lowered);
                c.that(what + "ran as one LanceHopCountStep (" + t + ")", ranAsOneLoweredStep(t));
                c.eq(what + "counted as lowered", 1L, s.lowered());
                c.eq(what + "not counted as a fallback", 0L, s.fellBack());
                LanceHopStrategy si = propStrategy(store, ids);
                Traversal.Admin<?, Object> ti = withProp(jg.traversal().withStrategies(si), seeds, k, v)
                        .dedup().id().asAdmin();
                List<Object> got = ti.toList();
                c.that(what + "id sets are equal (" + plainIds.size() + ")",
                        plainIds.equals(new HashSet<>(got)));
                c.eq(what + "no duplicate ids", (long) new HashSet<>(got).size(), (long) got.size());
                c.that(what + "ran as one LanceHopIdsStep (" + ti + ")", ranAsOneIdsStep(ti));
                if (v == ABSENT) {
                    c.eq(what + "a value that never occurs gives 0 in JanusGraph", 0L, plain);
                    c.eq(what + "and 0 lowered", 0L, lowered);
                } else {
                    c.that(what + "the answer is not trivially zero (" + plain + ")", plain > 0);
                    narrows |= plain < unfiltered;
                }
            }
        }
        c.that("the filter really narrows for at least one (k, value)", narrows);
        jg.tx().rollback();
    }

    private static void propIntegerAndForms(Checks c, RowStore store, JanusGraph jg,
                                            IDManager ids) {
        c.section("has(key, value): Integer values and has(key, P.eq(value)) are lowered");
        Object[] seeds = seedIds(ids);
        // An Integer value: look for an occurring value that fits an int (rows holding an edge
        // target in that facet).
        boolean found = false;
        for (int k = 1; k <= 3 && !found; k++) {
            for (Object id : outN(jg.traversal().V(seeds), k).dedup().id().toList()) {
                long v = store.payloadLow64At(rowOf(ids, id), FacetId.of(PROP_FACET));
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    continue;
                }
                found = true;
                long plain = withProp(jg.traversal(), seeds, k, v).dedup().count().next();
                LanceHopStrategy s = propStrategy(store, ids);
                Traversal.Admin<?, Long> t = withProp(jg.traversal().withStrategies(s), seeds, k,
                        (Object) Integer.valueOf((int) v)).dedup().count().asAdmin();
                long lowered = t.next();
                c.eq("Integer " + v + " at k=" + k + ": lowered count equals JanusGraph's (" + plain
                        + ")", plain, lowered);
                c.that("Integer value: ran as one LanceHopCountStep", ranAsOneLoweredStep(t));
                c.that("Integer value: the answer is not trivially zero", plain > 0);
                LanceHopStrategy s2 = propStrategy(store, ids);
                Traversal.Admin<?, Long> t2 = outN(jg.traversal().withStrategies(s2).V(seeds), k)
                        .has(PROP, P.eq(v)).dedup().count().asAdmin();
                c.eq("has(p, P.eq(" + v + ")): lowered count equals JanusGraph's", plain, t2.next().longValue());
                c.that("has(p, P.eq(..)): ran as one LanceHopCountStep", ranAsOneLoweredStep(t2));
                break;
            }
        }
        c.that("an occurring value that fits an Integer was found", found);
        jg.tx().rollback();
    }

    private static void propWithLabel(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("has(key, value) together with one hasLabel: both masks intersect the frontier");
        Object[] seeds = seedIds(ids);
        int k = 3;
        boolean any = false;
        boolean otherLabelEmpties = false;
        for (String label : LABELS) {
            String other = label.equals("c0") ? "c10" : "c0";
            for (Object id : outN(jg.traversal().V(seeds), k).hasLabel(label).dedup().id().toList()) {
                long v = store.payloadLow64At(rowOf(ids, id), FacetId.of(PROP_FACET));
                any = true;
                String what = label + " p=" + v + ": ";
                for (boolean propFirst : new boolean[] {true, false}) {
                    java.util.function.BiFunction<GraphTraversalSource, String, GraphTraversal<Vertex, Vertex>> q =
                            (g, l) -> propFirst
                                    ? outN(g.V(seeds), k).has(PROP, v).hasLabel(l)
                                    : outN(g.V(seeds), k).hasLabel(l).has(PROP, v);
                    String form = what + (propFirst ? "has.hasLabel " : "hasLabel.has ");
                    long plain = q.apply(jg.traversal(), label).dedup().count().next();
                    Set<Object> plainIds = q.apply(jg.traversal(), label).dedup().id().toSet();
                    LanceHopStrategy s = propStrategy(store, ids);
                    Traversal.Admin<?, Long> t = q.apply(jg.traversal().withStrategies(s), label)
                            .dedup().count().asAdmin();
                    c.eq(form + "lowered count equals JanusGraph's", plain, t.next().longValue());
                    c.that(form + "ran as one LanceHopCountStep (" + t + ")",
                            ranAsOneLoweredStep(t) && s.lowered() == 1L);
                    c.that(form + "the answer is not trivially zero (" + plain + ")", plain > 0);
                    LanceHopStrategy si = propStrategy(store, ids);
                    Traversal.Admin<?, Object> ti = q.apply(jg.traversal().withStrategies(si), label)
                            .dedup().id().asAdmin();
                    c.that(form + "id sets equal", plainIds.equals(new HashSet<>(ti.toList()))
                            && ranAsOneIdsStep(ti));
                    // The same value with the other label: the intersection must drop the row.
                    long plainOther = q.apply(jg.traversal(), other).dedup().count().next();
                    LanceHopStrategy so = propStrategy(store, ids);
                    long loweredOther = q.apply(jg.traversal().withStrategies(so), other)
                            .dedup().count().next();
                    c.eq(form + "with the other label: equals JanusGraph's (" + plainOther + ")",
                            plainOther, loweredOther);
                    otherLabelEmpties |= plainOther < plain;
                }
                break;
            }
        }
        c.that("rows for both labels were found at k=3", any);
        c.that("the label filter is applied as well: the other label gives fewer rows",
                otherLabelEmpties);
        jg.tx().rollback();
    }

    private static void propProvenance(Checks c, RowStore store, IDManager ids) {
        c.section("has(key, value) provenance: the answer comes from the row store");
        JanusGraph empty = open();
        IDManager emptyIds = ((StandardJanusGraph) empty).getIDManager();
        Object[] seeds = seedIds(emptyIds);
        // A value that occurs in the store's frontier, chosen from the store through the same
        // native chain with no filter.
        long[] rows;
        try (com.adaworldapi.graph.Graph g = com.adaworldapi.graph.Graph.open(store)) {
            try (com.adaworldapi.graph.Graph g1 = g.from(SEED_ROWS);
                    com.adaworldapi.graph.Graph g2 = g1.hop(Edge.KNOWS);
                    com.adaworldapi.graph.Graph g3 = g2.hop(Edge.KNOWS)) {
                rows = g3.materializeRows();
            }
        }
        long v = store.payloadLow64At(rows[rows.length / 2], FacetId.of(PROP_FACET));
        long plain = withProp(empty.traversal(), seeds, 2, v).dedup().count().next();
        LanceHopStrategy s = propStrategy(store, emptyIds);
        long lowered = withProp(empty.traversal().withStrategies(s), seeds, 2, v)
                .dedup().count().next();
        c.eq("plain JanusGraph over an empty graph answers 0", 0L, plain);
        c.that("the lowered traversal over the same empty graph answers from the row store ("
                + lowered + ")", lowered > 0);
        c.eq("and it was lowered", 1L, s.lowered());
        Set<Object> plainIds = withProp(empty.traversal(), seeds, 2, v).dedup().id().toSet();
        LanceHopStrategy si = propStrategy(store, emptyIds);
        Set<Object> loweredIds = withProp(empty.traversal().withStrategies(si), seeds, 2, v)
                .dedup().id().toSet();
        c.eq("plain JanusGraph over an empty graph returns no ids", 0L, (long) plainIds.size());
        c.eq("the lowered id traversal returns as many ids as the count", lowered,
                (long) loweredIds.size());
        empty.close();
        // A JanusGraph copy whose property differs from the store: the store's answer wins.
        JanusGraph other = open();
        IDManager otherIds = ((StandardJanusGraph) other).getIDManager();
        for (long row = 0; row < store.rowCount(); row++) {
            other.addVertex(T.id, vid(otherIds, row), T.label,
                    "c" + store.classidAt(row, FacetId.of(LABEL_FACET)), PROP, -1L);
        }
        other.tx().commit();
        long plainOther = withProp(other.traversal(), seedIds(otherIds), 2, v).dedup().count().next();
        long loweredOther = withProp(other.traversal().withStrategies(propStrategy(store, otherIds)),
                seedIds(otherIds), 2, v).dedup().count().next();
        c.eq("a graph whose property is -1 everywhere: plain JanusGraph answers 0", 0L, plainOther);
        c.eq("and the lowered traversal still returns the store's answer", lowered, loweredOther);
        other.close();
    }

    private static void propFalsifier(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("has(key, value) falsifier: the wrong facet does not reproduce JanusGraph");
        Object[] seeds = seedIds(ids);
        boolean countDiffers = false;
        boolean idsDiffer = false;
        for (int k = 1; k <= 3; k++) {
            for (long v : occurringValues(store, jg, ids, seeds, k)) {
                long plain = withProp(jg.traversal(), seeds, k, v).dedup().count().next();
                long wrong = withProp(jg.traversal().withStrategies(
                        propStrategy(store, ids, FacetId.of(WRONG_PROP_FACET))), seeds, k, v)
                        .dedup().count().next();
                countDiffers |= plain != wrong;
                Set<Object> plainIds = withProp(jg.traversal(), seeds, k, v).dedup().id().toSet();
                idsDiffer |= !plainIds.equals(withProp(jg.traversal().withStrategies(
                        propStrategy(store, ids, FacetId.of(WRONG_PROP_FACET))), seeds, k, v)
                        .dedup().id().toSet());
            }
        }
        c.that("the property read from facet " + WRONG_PROP_FACET + " instead of " + PROP_FACET
                + " changes the counts", countDiffers);
        c.that("and changes the id sets", idsDiffer);
        jg.tx().rollback();
    }

    /** A not-lowered shape that JanusGraph itself rejects: both must fail the same way. */
    private static void propFallbackThrows(Checks c, JanusGraph jg, RowStore store, IDManager ids,
                                           String what, String reasonPart,
                                           Function<GraphTraversalSource, GraphTraversal<?, Long>> q) {
        Class<?> plainFailure = null;
        try {
            q.apply(jg.traversal()).next();
        } catch (RuntimeException e) {
            plainFailure = e.getClass();
        }
        LanceHopStrategy s = propStrategy(store, ids);
        Traversal.Admin<?, Long> t = q.apply(jg.traversal().withStrategies(s)).asAdmin();
        Class<?> failure = null;
        try {
            t.next();
        } catch (RuntimeException e) {
            failure = e.getClass();
        }
        c.that(what + ": plain JanusGraph rejects it (" + (plainFailure == null ? "no" : plainFailure.getSimpleName())
                + ")", plainFailure != null);
        c.that(what + ": with the strategy it fails the same way, not with a count",
                plainFailure == failure);
        c.eq(what + ": counted as a fallback", 1L, s.fellBack());
        c.eq(what + ": not counted as lowered", 0L, s.lowered());
        c.that(what + ": the reason mentions '" + reasonPart + "' ('" + s.lastFallbackReason() + "')",
                s.lastFallbackReason().contains(reasonPart));
        c.that(what + ": no lowered step ran", !(t.getSteps().size() == 1
                && t.getSteps().get(0) instanceof LanceHopCountStep));
    }

    private static void propFallbacks(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("has(key, value) fallbacks: counted, with a reason, answered as JanusGraph answers");
        Object[] seeds = seedIds(ids);
        long v = occurringValues(store, jg, ids, seeds, 2)[1];
        long w = occurringValues(store, jg, ids, seeds, 2)[2];
        labelFallback(c, jg, propStrategy(store, ids), "unregistered key", "not registered",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has("n", v).dedup().count());
        labelFallback(c, jg, labelStrategy(store, ids), "no property registered at all",
                "not registered",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, v).dedup().count());
        labelFallback(c, jg, propStrategy(store, ids), "P.gt", "not an equality",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, P.gt(v)).dedup().count());
        labelFallback(c, jg, propStrategy(store, ids), "P.within", "not an equality",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, P.within(v, w)).dedup().count());
        labelFallback(c, jg, propStrategy(store, ids), "P.neq", "not an equality",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, P.neq(v)).dedup().count());
        // JanusGraph itself refuses a value of the wrong type for a Long key, so for these two the
        // answer to match is the same refusal: the strategy must not turn it into a count.
        propFallbackThrows(c, jg, store, ids, "String value", "Long or Integer",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, "abc").dedup().count());
        propFallbackThrows(c, jg, store, ids, "Double value", "Long or Integer",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, 1.5d).dedup().count());
        labelFallback(c, jg, propStrategy(store, ids), "two has steps", "2 conditions",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, v).has(PROP, w).dedup().count());
        labelFallback(c, jg, propStrategy(store, ids), "two has steps, one unregistered",
                "not registered",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, v).has("n", w).dedup().count());
        labelFallback(c, jg, propStrategy(store, ids), "has + two hasLabel", "3 conditions",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, v).hasLabel("c0").hasLabel("c10")
                        .dedup().count());
        labelFallback(c, jg, propStrategy(store, ids), "has between hops", "step 2",
                g -> g.V(seeds).out(KNOWS).has(PROP, v).out(KNOWS).dedup().count());
        labelFallback(c, jg, propStrategy(store, ids), "has without dedup()", "dedup",
                g -> g.V(seeds).out(KNOWS).out(KNOWS).has(PROP, v).count());
        // The id() shape.
        for (Object[] cs : new Object[][] {{"gt", "not an equality"}, {"unregistered", "not registered"}}) {
            boolean gt = cs[0].equals("gt");
            LanceHopStrategy s = propStrategy(store, ids);
            Set<Object> plain = (gt ? outN(jg.traversal().V(seeds), 2).has(PROP, P.gt(v))
                    : outN(jg.traversal().V(seeds), 2).has("n", v)).dedup().id().toSet();
            Traversal.Admin<?, Object> t = (gt
                    ? outN(jg.traversal().withStrategies(s).V(seeds), 2).has(PROP, P.gt(v))
                    : outN(jg.traversal().withStrategies(s).V(seeds), 2).has("n", v))
                    .dedup().id().asAdmin();
            Set<Object> got = t.toSet();
            c.that("id() " + cs[0] + ": answer unchanged (" + got.size() + " ids)",
                    plain.equals(got));
            c.eq("id() " + cs[0] + ": counted as a fallback", 1L, s.fellBack());
            c.eq("id() " + cs[0] + ": not counted as lowered", 0L, s.lowered());
            c.that("id() " + cs[0] + ": no lowered step ran", !ranAsOneIdsStep(t));
            c.that("id() " + cs[0] + ": the reason mentions '" + cs[1] + "' ('"
                    + s.lastFallbackReason() + "')", s.lastFallbackReason().contains((String) cs[1]));
        }
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
