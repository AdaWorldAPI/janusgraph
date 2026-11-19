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
import com.adaworldapi.lancegraph.Diagnostics;
import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.Mask;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.idmanagement.IDManager;

import org.janusgraph.lgj.MeasureSupport.Shape;

/**
 * How many native calls (membrane crossings) one execution of each lowered shape makes, counted
 * with {@link Diagnostics#crossings()}.
 *
 * <p>What is counted is one execution: the traversal is built and its strategies are applied
 * first, a snapshot is taken, the traversal is run to exhaustion, a snapshot is taken. Planning
 * is measured on its own and must make no call. Handle releases ({@code close}) are native calls
 * and are counted, because a lowered traversal closes every mask it made.
 *
 * <p>The numbers are not predicted from the README. The primitives (import, hop, count,
 * materialize, class mask, intersection, close) are measured one by one on the row store, the
 * shapes are measured end to end, and the check is that the end-to-end number is the sum of the
 * primitives, as a formula in k. Then the same shapes are measured over a bigger row store and
 * other seed sets, and over traversals that are not lowered.
 */
public final class JanusCrossingsTest {

    private JanusCrossingsTest() {}

    private static final long ROWS = 2000L;
    private static final long BIG_ROWS = 20000L;
    private static final int[] KS = {1, 2, 3};

    public static void main(String[] args) {
        System.out.println("JanusCrossingsTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("JanusCrossingsTest"));
        }
        Checks c = new Checks("JanusCrossingsTest");
        try (RowStore store = MeasureSupport.openStore(ROWS);
                RowStore big = MeasureSupport.openStore(BIG_ROWS)) {
            JanusGraph jg = MeasureSupport.open();
            IDManager ids = ((StandardJanusGraph) jg).getIDManager();
            long edges = MeasureSupport.copyInto(jg, ids, store);
            c.section("fixture");
            c.that("JanusGraph copy has edges (" + edges + "), so the fallbacks run on real data",
                    edges > 0);
            Primitives p = primitives(c, store);
            shapes(c, store, jg, ids, p);
            independence(c, store, big, jg, ids, p);
            propertyShapes(c, store, big, jg, ids, p);
            fallbacks(c, store, jg, ids);
            falsifiers(c, store, jg, ids);
            jg.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.exit(c.report());
    }

    // ---- what one measurement returns ----

    private record Run(long planning, long running, long answer, boolean oneLoweredStep,
                       long lowered, long fellBack) {}

    /**
     * Builds, plans (snapshot around planning), runs to exhaustion (snapshot around running).
     * A fresh strategy is used, so its counters belong to this traversal alone.
     */
    private static Run measure(RowStore store, JanusGraph jg, IDManager ids, Shape shape,
                               long[] seedRows, int k) {
        LanceHopStrategy s = MeasureSupport.strategy(store, ids);
        GraphTraversalSource g = jg.traversal().withStrategies(s);
        Traversal.Admin<?, ?> t = shape.build(g, MeasureSupport.seedIds(ids, seedRows), k);
        long a = Diagnostics.crossings();
        t.applyStrategies();
        long b = Diagnostics.crossings();
        List<Object> answer = MeasureSupport.drain(t);
        long d = Diagnostics.crossings();
        boolean one = MeasureSupport.isOneLoweredStep(t);
        long size = answer.isEmpty() ? -1 : MeasureSupport.answerSize(shape, answer);
        return new Run(b - a, d - b, size, one, s.lowered(), s.fellBack());
    }

    /** Measured twice; the two executions must agree before the number is used. */
    private static Run measureStable(Checks c, String what, RowStore store, JanusGraph jg,
                                     IDManager ids, Shape shape, long[] seedRows, int k) {
        Run first = measure(store, jg, ids, shape, seedRows, k);
        Run second = measure(store, jg, ids, shape, seedRows, k);
        c.eq(what + ": a second execution makes the same number of calls as the first",
                first.running, second.running);
        return second;
    }

    // ---- the primitives ----

    private record Primitives(long importRows, long hop, long count, long materialize,
                              long classMask, long ternlog, long close, long propertyMask) {

        /** Calls of one execution, composed from the primitives, for k hops. */
        long compose(Shape shape, int k) {
            long masks = k + 1;
            long total = importRows + k * hop + (shape.returnsIds() ? materialize : count);
            if (shape.filtered()) {
                total += classMask + ternlog;
                masks += 2;
            }
            return total + masks * close;
        }
    }

    private static <T> long delta(Function<Void, T> body) {
        long a = Diagnostics.crossings();
        body.apply(null);
        return Diagnostics.crossings() - a;
    }

    private static Primitives primitives(Checks c, RowStore store) {
        c.section("primitives: native calls of each store and mask operation, measured one by one");
        long[] seeds = MeasureSupport.SEED_ROWS;
        FacetId facet = FacetId.of(MeasureSupport.LABEL_FACET);
        List<Mask> open = new ArrayList<>();
        // One discarded pass so one-time setup is not attributed to an operation.
        Mask warm = store.importRows(seeds);
        warm.count();
        warm.close();

        long importOne = delta(x -> { Mask m = store.importRows(seeds[0]); open.add(m); return m; });
        long importTen = delta(x -> { Mask m = store.importRows(seeds); open.add(m); return m; });
        Mask src = open.get(open.size() - 1);
        Mask[] hopped = new Mask[1];
        long hop = delta(x -> hopped[0] = store.hop(Edge.KNOWS, src));
        open.add(hopped[0]);
        long count = delta(x -> hopped[0].count());
        long materializeFirst = delta(x -> hopped[0].materializeRows());
        long materializeSecond = delta(x -> hopped[0].materializeRows());
        Mask[] cls = new Mask[1];
        long classMask = delta(x -> cls[0] = store.maskOfFacetClass(facet, 10));
        open.add(cls[0]);
        Mask[] both = new Mask[1];
        long ternlog = delta(x -> both[0] = hopped[0].ternlog(cls[0], cls[0], 0xC0));
        open.add(both[0]);
        Mask[] prop = new Mask[1];
        long propertyMask = delta(x -> prop[0] = store.maskOfFacetTernaryMatch(
                FacetId.of(MeasureSupport.PROP_FACET), 1L, 0, -1L, 0));
        open.add(prop[0]);
        long closeOne = delta(x -> { open.remove(0).close(); return null; });

        c.note("importRows of 1 row: " + importOne + ", of 10 rows: " + importTen
                + ", hop: " + hop + ", count: " + count + ", materializeRows first call: "
                + materializeFirst + ", second call: " + materializeSecond
                + ", maskOfFacetClass: " + classMask + ", ternlog: " + ternlog
                + ", maskOfFacetTernaryMatch: " + propertyMask
                + ", close of one mask: " + closeOne);
        c.eq("importRows makes the same number of calls for 1 row and for 10 rows", importOne,
                importTen);
        c.eq("importRows: 2 calls (create the mask, describe its word lane; the words are "
                + "written in process)", 2L, importTen);
        c.eq("hop: 2 calls (create the destination mask, the hop)", 2L, hop);
        c.eq("count: 1 call", 1L, count);
        c.eq("materializeRows, first call: 1 call (describe the word lane)", 1L,
                materializeFirst);
        c.eq("materializeRows, second call: 1 call as well (the window is re-checked each call)",
                materializeFirst, materializeSecond);
        c.eq("maskOfFacetClass: 2 calls (create the mask, match the class)", 2L, classMask);
        c.eq("ternlog: 2 calls (create the destination mask, the operation)", 2L, ternlog);
        c.eq("maskOfFacetTernaryMatch: 2 calls (create the mask, match the register)", 2L,
                propertyMask);
        c.eq("close of one mask: 1 call, so releasing a handle is counted", 1L, closeOne);
        for (Mask m : open) {
            m.close();
        }
        return new Primitives(importTen, hop, count, materializeFirst, classMask, ternlog,
                closeOne, propertyMask);
    }

    // ---- the four shapes at k = 1, 2, 3 ----

    private static void shapes(Checks c, RowStore store, JanusGraph jg, IDManager ids,
                               Primitives p) {
        c.section("one execution of each lowered shape, k = 1, 2, 3 (2,000 rows, 10 seeds)");
        StringBuilder table = new StringBuilder();
        for (Shape shape : Shape.values()) {
            for (int k : KS) {
                String what = shape.text + " k=" + k;
                Run r = measureStable(c, what, store, jg, ids, shape, MeasureSupport.SEED_ROWS, k);
                table.append(' ').append(what).append(" -> ").append(r.running).append(';');
                c.eq(what + ": planning makes no native call", 0L, r.planning);
                c.that(what + ": the traversal that ran is one lowered step", r.oneLoweredStep);
                c.eq(what + ": counted as lowered", 1L, r.lowered);
                c.eq(what + ": not counted as a fallback", 0L, r.fellBack);
                c.that(what + ": the answer is not empty (" + r.answer + ")", r.answer > 0);
                c.eq(what + ": calls equal the sum of the primitives for k=" + k,
                        p.compose(shape, k), r.running);
                long formula = shape.filtered() ? 3L * k + 10 : 3L * k + 4;
                c.eq(what + ": calls equal " + (shape.filtered() ? "3k + 10" : "3k + 4"),
                        formula, r.running);
            }
        }
        c.note("measured:" + table);
    }

    // ---- independence from the data ----

    private static void independence(Checks c, RowStore store, RowStore big, JanusGraph jg,
                                     IDManager ids, Primitives p) {
        c.section("crossings do not grow with the result: 2,000 and 20,000 rows, other seed sets");
        // Seed sets: 1 row, 10 rows, 500 rows; and on the big store also 5,000 rows.
        long[][] seedSets = {
                {5},
                MeasureSupport.SEED_ROWS,
                MeasureSupport.strideRows(500, 3),
                MeasureSupport.strideRows(5000, 4)};
        for (Shape shape : Shape.values()) {
            for (int k : KS) {
                Set<Long> calls = new HashSet<>();
                Set<Long> answers = new HashSet<>();
                long planning = 0;
                int runs = 0;
                long smallest = Long.MAX_VALUE;
                long largest = 0;
                for (RowStore s : new RowStore[] {store, big}) {
                    for (long[] seeds : seedSets) {
                        if (seeds.length == 5000 && s == store) {
                            continue; // rows 0..19996 do not all exist in the 2,000-row store
                        }
                        Run r = measure(s, jg, ids, shape, seeds, k);
                        calls.add(r.running);
                        answers.add(r.answer);
                        planning += r.planning;
                        runs++;
                        if (r.answer >= 0) {
                            smallest = Math.min(smallest, r.answer);
                            largest = Math.max(largest, r.answer);
                        }
                    }
                }
                String what = shape.text + " k=" + k;
                c.eq(what + ": " + runs + " runs (2 stores, 3 or 4 seed sets) make the same number"
                        + " of calls", 1L, (long) calls.size());
                c.eq(what + ": and it is the formula's number",
                        p.compose(shape, k), (long) calls.iterator().next());
                c.eq(what + ": planning made no call in any of them", 0L, planning);
                c.that(what + ": the answers differ between runs, so this is not one answer "
                        + "measured 7 times (sizes " + smallest + " to " + largest + ")",
                        answers.size() > 1);
            }
        }
        // For the plainest statement: the same shape, k = 2, over both stores, one seed set.
        Run a = measure(store, jg, ids, Shape.COUNT, MeasureSupport.SEED_ROWS, 2);
        Run b = measure(big, jg, ids, Shape.COUNT, MeasureSupport.SEED_ROWS, 2);
        c.note("count k=2, 10 seeds: " + a.running + " calls over 2,000 rows (answer " + a.answer
                + "), " + b.running + " calls over 20,000 rows (answer " + b.answer + ")");
        c.eq("count k=2, the same seeds: same calls over 2,000 and 20,000 rows", a.running,
                b.running);
    }

    // ---- the property equality shapes ----

    /** One execution of a has(PROP, value) shape: planning calls, running calls, answer size. */
    private static Run measureProperty(RowStore store, JanusGraph jg, IDManager ids,
                                       long[] seedRows, int k, boolean withLabel, boolean idShape) {
        LanceHopStrategy s = MeasureSupport.strategy(store, ids);
        GraphTraversalSource g = jg.traversal().withStrategies(s);
        long value = MeasureSupport.propertyValue(store, seedRows, k);
        Traversal.Admin<?, ?> t = MeasureSupport.propertyShape(g,
                MeasureSupport.seedIds(ids, seedRows), k, value, withLabel, idShape);
        long a = Diagnostics.crossings();
        t.applyStrategies();
        long b = Diagnostics.crossings();
        List<Object> answer = MeasureSupport.drain(t);
        long d = Diagnostics.crossings();
        boolean one = MeasureSupport.isOneLoweredStep(t);
        long size = answer.isEmpty() ? -1 : (idShape ? answer.size() : (Long) answer.get(0));
        return new Run(b - a, d - b, size, one, s.lowered(), s.fellBack());
    }

    private static void propertyShapes(Checks c, RowStore store, RowStore big, JanusGraph jg,
                                       IDManager ids, Primitives p) {
        c.section("has(key, value) shapes: one filter 3k + 10 calls, with hasLabel as well 3k + 16");
        StringBuilder table = new StringBuilder();
        long[][] seedSets = {{5}, MeasureSupport.SEED_ROWS, MeasureSupport.strideRows(500, 3)};
        for (boolean withLabel : new boolean[] {false, true}) {
            for (boolean idShape : new boolean[] {false, true}) {
                for (int k : KS) {
                    String what = "has" + (withLabel ? "+hasLabel" : "") + (idShape ? "+id" : "+count")
                            + " k=" + k;
                    Set<Long> calls = new HashSet<>();
                    long planning = 0;
                    long lowest = Long.MAX_VALUE;
                    boolean allLowered = true;
                    for (RowStore s : new RowStore[] {store, big}) {
                        for (long[] seeds : seedSets) {
                            Run r = measureProperty(s, jg, ids, seeds, k, withLabel, idShape);
                            calls.add(r.running);
                            planning += r.planning;
                            allLowered &= r.oneLoweredStep && r.lowered == 1L && r.fellBack == 0L;
                            if (!withLabel) {
                                lowest = Math.min(lowest, r.answer);
                            }
                        }
                    }
                    Run r = measureProperty(store, jg, ids, MeasureSupport.SEED_ROWS, k, withLabel,
                            idShape);
                    table.append(' ').append(what).append(" -> ").append(r.running).append(';');
                    long masks = k + 1 + 2 + (withLabel ? 2 : 0);
                    long expected = p.importRows + k * p.hop + (idShape ? p.materialize : p.count)
                            + p.propertyMask + p.ternlog
                            + (withLabel ? p.classMask + p.ternlog : 0) + masks * p.close;
                    c.eq(what + ": 6 runs (2 stores, 3 seed sets) make the same number of calls", 1L,
                            (long) calls.size());
                    c.eq(what + ": calls equal the sum of the primitives", expected, r.running);
                    c.eq(what + ": calls equal " + (withLabel ? "3k + 16" : "3k + 10"),
                            withLabel ? 3L * k + 16 : 3L * k + 10, r.running);
                    c.eq(what + ": planning makes no native call", 0L, planning);
                    c.that(what + ": every run was one lowered step", allLowered);
                    if (!withLabel) {
                        c.that(what + ": the value is from the frontier, so every answer is "
                                + "non-empty (smallest " + lowest + ")", lowest > 0);
                    }
                }
            }
        }
        c.note("measured:" + table);
        // The same shape with a different value makes the same calls: the count does not depend
        // on the value or on the answer.
        Run a = measureProperty(store, jg, ids, MeasureSupport.SEED_ROWS, 2, false, false);
        LanceHopStrategy s = MeasureSupport.strategy(store, ids);
        Traversal.Admin<?, ?> absent = MeasureSupport.propertyShape(jg.traversal().withStrategies(s),
                MeasureSupport.seedIds(ids, MeasureSupport.SEED_ROWS), 2, 0x7777_7777_7777_7777L,
                false, false);
        absent.applyStrategies();
        long before = Diagnostics.crossings();
        List<Object> answer = MeasureSupport.drain(absent);
        long calls = Diagnostics.crossings() - before;
        c.eq("a value that occurs nowhere answers 0", 0L, ((Long) answer.get(0)).longValue());
        c.eq("and makes the same calls as a value that occurs", a.running, calls);
        long plain = measure(store, jg, ids, Shape.COUNT, MeasureSupport.SEED_ROWS, 2).running;
        c.eq("has adds 6 calls to the same k=2 count (mask 2, intersection 2, two closes)", 6L,
                a.running - plain);
    }

    // ---- not lowered ----

    private static void fallbacks(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("a traversal that is not lowered makes no native call");
        Object[] seeds = MeasureSupport.seedIds(ids, MeasureSupport.SEED_ROWS);
        fallback(c, store, jg, ids, "out().out().count() without dedup()",
                g -> g.V(seeds).out(MeasureSupport.KNOWS).out(MeasureSupport.KNOWS).count().asAdmin());
        fallback(c, store, jg, ids, "in().dedup().count()",
                g -> g.V(seeds).in(MeasureSupport.KNOWS).dedup().count().asAdmin());
        fallback(c, store, jg, ids, "out().hasLabel(two labels).dedup().count()",
                g -> g.V(seeds).out(MeasureSupport.KNOWS).hasLabel("c0", "c10").dedup().count()
                        .asAdmin());
        propertyFallback(c, store, jg, ids, "has(p, gt)",
                g -> g.V(seeds).out(MeasureSupport.KNOWS).has(MeasureSupport.PROP,
                        org.apache.tinkerpop.gremlin.process.traversal.P.gt(5L)).dedup().count()
                        .asAdmin());
        propertyFallback(c, store, jg, ids, "has(unregistered key)",
                g -> g.V(seeds).out(MeasureSupport.KNOWS).has("q", 5L).dedup().count().asAdmin());
        jg.tx().rollback();
    }

    private static void propertyFallback(Checks c, RowStore store, JanusGraph jg, IDManager ids,
                                         String what,
                                         Function<GraphTraversalSource, Traversal.Admin<?, ?>> build) {
        LanceHopStrategy s = MeasureSupport.strategy(store, ids);
        Traversal.Admin<?, ?> t = build.apply(jg.traversal().withStrategies(s));
        long a = Diagnostics.crossings();
        t.applyStrategies();
        MeasureSupport.drain(t);
        long calls = Diagnostics.crossings() - a;
        c.eq(what + ": planning and running make no native call", 0L, calls);
        c.eq(what + ": counted as a fallback", 1L, s.fellBack());
        c.eq(what + ": not counted as lowered", 0L, s.lowered());
    }

    private static void fallback(Checks c, RowStore store, JanusGraph jg, IDManager ids,
                                 String what, Function<GraphTraversalSource, Traversal.Admin<?, ?>> build) {
        LanceHopStrategy s = MeasureSupport.strategy(store, ids);
        Traversal.Admin<?, ?> t = build.apply(jg.traversal().withStrategies(s));
        long a = Diagnostics.crossings();
        t.applyStrategies();
        List<Object> answer = MeasureSupport.drain(t);
        long calls = Diagnostics.crossings() - a;
        c.eq(what + ": planning and running make no native call", 0L, calls);
        c.eq(what + ": counted as a fallback", 1L, s.fellBack());
        c.eq(what + ": not counted as lowered", 0L, s.lowered());
        c.that(what + ": JanusGraph answered, and not with zero (" + answer + ")",
                !answer.isEmpty() && (Long) answer.get(0) > 0);
    }

    // ---- the measurement can see a difference ----

    private static void falsifiers(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("the measurement can see a difference");
        for (Shape shape : Shape.values()) {
            Run k2 = measure(store, jg, ids, shape, MeasureSupport.SEED_ROWS, 2);
            Run k3 = measure(store, jg, ids, shape, MeasureSupport.SEED_ROWS, 3);
            c.notEq(shape.text + ": k=3 makes other calls than k=2 (" + k2.running + " and "
                    + k3.running + ")", k2.running, k3.running);
            c.eq(shape.text + ": one more hop costs 3 calls (hop 2, close 1)",
                    3L, k3.running - k2.running);
        }
        Run plain = measure(store, jg, ids, Shape.COUNT, MeasureSupport.SEED_ROWS, 2);
        Run filtered = measure(store, jg, ids, Shape.LABEL_COUNT, MeasureSupport.SEED_ROWS, 2);
        c.eq("hasLabel adds 6 calls to the same k=2 count (class mask 2, intersection 2, two "
                + "closes)", 6L, filtered.running - plain.running);
        long before = Diagnostics.crossings();
        Mask m = store.importRows(5);
        long afterImport = Diagnostics.crossings();
        m.close();
        c.that("the counter moves when a native call is made (" + (afterImport - before) + ")",
                afterImport > before);
    }
}
