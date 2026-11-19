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

import com.adaworldapi.lancegraph.Checks;
import com.adaworldapi.lancegraph.NativeRuntime;
import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.idmanagement.IDManager;

import org.janusgraph.lgj.MeasureSupport.Shape;

/**
 * Java heap bytes allocated by one execution of a lowered query, measured with
 * {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes} for the current thread.
 *
 * <p><b>Where the method comes from.</b> It is the method of lance-graph-java's
 * {@code GraphHopTest.checkAllocationIndependence} (consumers/graph), which took it from
 * {@code TradesAllocationTest} and the valhalla-lab {@code Lab.allocatedBytes}: the instrument is
 * touched once so its own class initialisation is not attributed to the body; a warm-up of
 * repeated executions lets the JIT and the allocator settle; then several samples are taken and
 * the <em>smallest</em> is the floor. The minimum is used because noise (JIT work, a class
 * loaded late, a profile growing) only adds bytes, it never removes them. Two floors are
 * compared with a slack, not with equality, for the same reason: the allocation count is real,
 * and some jitter survives the warm-up. GraphHopTest uses 30 warm-up executions, 10 samples and a
 * slack of 4,096 bytes ("not zero, small next to the ceiling, large next to plausible jitter").
 * The same numbers are used here. The spread of the samples (minimum, median, maximum) is printed
 * so the slack can be judged against what was seen.
 *
 * <p><b>What is measured.</b> One execution: the traversal is built and its strategies are
 * applied before the instrument is read, so planning is excluded. The sample is the run of that
 * traversal to exhaustion. This measures the Java heap of the thread that runs the query. It does
 * not say whether native code allocates, and there is no storage backend in the lowered path.
 *
 * <p>The count shape returns one number, so its allocation should not depend on the store or the
 * frontier. The id shape returns one Java {@code Long} per reached vertex, so its allocation
 * does depend on the result; that is where rows become Java values. Only what is true is asserted.
 */
public final class JanusAllocationTest {

    private JanusAllocationTest() {}

    private static final long ROWS = 2000L;
    private static final long BIG_ROWS = 64000L;
    private static final int K = 2;

    /** Same as GraphHopTest: warm-up executions, samples, slack in bytes. */
    private static final int WARMUP = 30;
    private static final int SAMPLES = 10;
    private static final long SLACK_BYTES = 4_096;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

    /** Bytes this thread allocated while running {@code body}. */
    private static long allocatedBytes(Runnable body) {
        long tid = Thread.currentThread().threadId();
        // Touch the instrument once so its own one-time class initialisation is not attributed
        // to the body.
        THREADS.getThreadAllocatedBytes(tid);
        long before = THREADS.getThreadAllocatedBytes(tid);
        body.run();
        long after = THREADS.getThreadAllocatedBytes(tid);
        return after - before;
    }

    /** The samples of one measurement, sorted ascending, and the size of the answer. */
    private record Sample(long[] bytes, long answer) {
        long floor() {
            return bytes[0];
        }

        long median() {
            return bytes[bytes.length / 2];
        }

        long max() {
            return bytes[bytes.length - 1];
        }

        String spread() {
            return "min " + floor() + ", median " + median() + ", max " + max() + " bytes";
        }
    }

    /**
     * Warms up, then takes {@link #SAMPLES} samples. {@code prepare} builds and plans one fresh
     * traversal, outside the instrument; only running it to exhaustion is measured.
     */
    private static Sample sample(Supplier<Traversal.Admin<?, ?>> prepare, Shape shape) {
        for (int i = 0; i < WARMUP; i++) {
            MeasureSupport.drain(prepare.get());
        }
        long[] bytes = new long[SAMPLES];
        long answer = -1;
        for (int i = 0; i < SAMPLES; i++) {
            Traversal.Admin<?, ?> t = prepare.get();
            @SuppressWarnings("unchecked")
            List<Object>[] out = new List[1];
            bytes[i] = allocatedBytes(() -> out[0] = MeasureSupport.drain(t));
            answer = MeasureSupport.answerSize(shape, out[0]);
        }
        Arrays.sort(bytes);
        return new Sample(bytes, answer);
    }

    private static Supplier<Traversal.Admin<?, ?>> lowered(RowStore store, JanusGraph jg,
                                                          IDManager ids, Shape shape,
                                                          long[] seedRows) {
        Object[] seeds = MeasureSupport.seedIds(ids, seedRows);
        return () -> {
            Traversal.Admin<?, ?> t = shape.build(
                    jg.traversal().withStrategies(MeasureSupport.strategy(store, ids)), seeds, K);
            t.applyStrategies();
            return t;
        };
    }

    public static void main(String[] args) {
        System.out.println("JanusAllocationTest");
        if (!NativeRuntime.isAvailable()) {
            System.exit(Checks.reportUnavailable("JanusAllocationTest"));
        }
        Checks c = new Checks("JanusAllocationTest");
        try (RowStore store = MeasureSupport.openStore(ROWS);
                RowStore big = MeasureSupport.openStore(BIG_ROWS)) {
            JanusGraph jg = MeasureSupport.open();
            IDManager ids = ((StandardJanusGraph) jg).getIDManager();
            long t0 = System.nanoTime();
            long edges = MeasureSupport.copyInto(jg, ids, store);
            long copyMillis = (System.nanoTime() - t0) / 1_000_000;
            c.section("fixture");
            c.note("JDK " + System.getProperty("java.runtime.version") + ", " + edges
                    + " edges copied into JanusGraph in " + copyMillis + " ms; " + WARMUP
                    + " warm-up executions, " + SAMPLES + " samples, slack " + SLACK_BYTES
                    + " bytes");
            c.note("instrument's own overhead: " + allocatedBytes(() -> {})
                    + " bytes (reported, not subtracted)");
            countShape(c, store, big, jg, ids);
            idShape(c, store, big, jg, ids);
            plainJanusGraph(c, store, jg, ids);
            jg.tx().rollback();
            jg.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.exit(c.report());
    }

    private static void countShape(Checks c, RowStore store, RowStore big, JanusGraph jg,
                                   IDManager ids) {
        c.section("count shape, k = 2: allocation of one execution does not grow with the store"
                + " or the frontier");
        long[] few = MeasureSupport.SEED_ROWS;
        long[] many = MeasureSupport.strideRows(5000, 12);
        Sample a = sample(lowered(store, jg, ids, Shape.COUNT, few), Shape.COUNT);
        Sample b = sample(lowered(big, jg, ids, Shape.COUNT, few), Shape.COUNT);
        Sample m = sample(lowered(big, jg, ids, Shape.COUNT, many), Shape.COUNT);
        c.note("2,000 rows, 10 seeds, count " + a.answer + ": " + a.spread());
        c.note("64,000 rows, 10 seeds, count " + b.answer + ": " + b.spread());
        c.note("64,000 rows, 5,000 seeds, count " + m.answer + ": " + m.spread());
        c.that("the frontier is materially larger in the third run (" + m.answer + " against "
                + a.answer + ")", m.answer > 100 * a.answer);
        c.that("64,000 rows, same seeds: floor within " + SLACK_BYTES + " bytes of the 2,000-row"
                + " floor (" + b.floor() + " against " + a.floor() + ")",
                Math.abs(b.floor() - a.floor()) <= SLACK_BYTES);
        c.that("64,000 rows, 5,000 seeds: floor within " + SLACK_BYTES + " bytes of the 2,000-row"
                + " floor (" + m.floor() + " against " + a.floor() + ")",
                Math.abs(m.floor() - a.floor()) <= SLACK_BYTES);
        c.that("the floor is not zero (" + a.floor() + "): one execution allocates some Java "
                + "objects, and this is not a claim of zero allocation", a.floor() > 0);
    }

    private static void idShape(Checks c, RowStore store, RowStore big, JanusGraph jg,
                                IDManager ids) {
        c.section("id shape, k = 2: allocation grows with the ids returned (Longs at the API edge)");
        long[][] seedSets = {
                MeasureSupport.SEED_ROWS,
                MeasureSupport.SEED_ROWS,
                MeasureSupport.strideRows(500, 100),
                MeasureSupport.strideRows(5000, 12)};
        RowStore[] stores = {store, big, big, big};
        String[] names = {"2,000 rows, 10 seeds", "64,000 rows, 10 seeds",
                "64,000 rows, 500 seeds", "64,000 rows, 5,000 seeds"};
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < stores.length; i++) {
            Sample s = sample(lowered(stores[i], jg, ids, Shape.IDS, seedSets[i]), Shape.IDS);
            out.add(s);
            c.note(names[i] + ", " + s.answer + " ids: " + s.spread() + "; floor per id "
                    + String.format("%.1f", (double) s.floor() / s.answer) + " bytes");
        }
        Sample first = out.get(0);
        Sample last = out.get(out.size() - 1);
        double marginal = (double) (last.floor() - first.floor()) / (last.answer - first.answer);
        c.note("between the first and the last run: " + (last.answer - first.answer)
                + " more ids, " + (last.floor() - first.floor()) + " more bytes, "
                + String.format("%.1f", marginal) + " bytes per additional id");
        for (int i = 1; i < out.size(); i++) {
            Sample prev = out.get(i - 1);
            Sample cur = out.get(i);
            if (cur.answer > 2 * prev.answer) {
                c.that(names[i] + " (" + cur.answer + " ids) allocates more than " + names[i - 1]
                        + " (" + prev.answer + " ids): " + cur.floor() + " against "
                        + prev.floor(), cur.floor() > prev.floor() + SLACK_BYTES);
            }
        }
        c.that("the largest result allocates at least 8 bytes per id over the smallest (the long[]"
                + " of rows alone is 8 bytes per row)", last.floor() - first.floor()
                >= 8 * (last.answer - first.answer));
    }

    /**
     * For contrast only: plain JanusGraph (no strategy), the same count query over the 2,000-row
     * graph. A reported number, not a claim about speed or about which is better. A 64,000-vertex
     * JanusGraph is not built; the 2,000-row copy already takes the time printed under "fixture".
     */
    private static void plainJanusGraph(Checks c, RowStore store, JanusGraph jg, IDManager ids) {
        c.section("contrast: plain JanusGraph, count shape, k = 2, 2,000 rows, 10 seeds");
        Object[] seeds = MeasureSupport.seedIds(ids, MeasureSupport.SEED_ROWS);
        Supplier<Traversal.Admin<?, ?>> warmTx = () -> {
            Traversal.Admin<?, ?> t = Shape.COUNT.build(jg.traversal(), seeds, K);
            t.applyStrategies();
            return t;
        };
        // Same open transaction every time: the transaction's vertex cache is warm after the
        // first execution.
        Sample warm = sample(warmTx, Shape.COUNT);
        // A new transaction for each execution: the rollback comes before the build, so the
        // transaction's cache is empty and the backend is read again.
        Supplier<Traversal.Admin<?, ?>> coldTx = () -> {
            jg.tx().rollback();
            return warmTx.get();
        };
        Sample cold = sample(coldTx, Shape.COUNT);
        jg.tx().rollback();
        Sample lowered = sample(lowered(store, jg, ids, Shape.COUNT, MeasureSupport.SEED_ROWS),
                Shape.COUNT);
        c.note("plain JanusGraph, same transaction, count " + warm.answer + ": " + warm.spread());
        c.note("plain JanusGraph, new transaction each time, count " + cold.answer + ": "
                + cold.spread());
        c.note("lowered, same query, count " + lowered.answer + ": " + lowered.spread());
        c.eq("plain JanusGraph and the lowered traversal give the same count", warm.answer,
                lowered.answer);
        c.eq("plain JanusGraph with a new transaction gives the same count", cold.answer,
                lowered.answer);
        c.that("plain JanusGraph allocates a measurable amount on this thread (" + warm.floor()
                + " bytes); other threads are not counted", warm.floor() > 0);
    }
}
