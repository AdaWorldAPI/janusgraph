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

import com.adaworldapi.graph.Graph;
import com.adaworldapi.lancegraph.Mask;
import com.adaworldapi.lancegraph.RowStore;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.function.LongUnaryOperator;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;

/**
 * The one step a lowered id traversal consists of: it answers
 * {@code V(ids).out(label)^k.dedup().id()}, or the same with one {@code hasLabel(L)} and/or one
 * property equality before {@code dedup()}, from the row store. The rows of the last frontier are
 * materialized once, mapped to JanusGraph vertex ids, and emitted one traverser each, in
 * ascending row order. It reads nothing from JanusGraph.
 */
final class LanceHopIdsStep<S> extends AbstractStep<S, Long> {

    private final transient RowStore store;
    private final long[] seeds;
    private final int[] hops;
    private final transient LongUnaryOperator rowToVertexId;
    private final HopFilter filter;
    private long[] rows;
    private int next;

    LanceHopIdsStep(Traversal.Admin<?, ?> traversal, RowStore store, long[] seeds, int[] hops,
                    LongUnaryOperator rowToVertexId, HopFilter filter) {
        super(traversal);
        this.store = store;
        this.seeds = seeds.clone();
        this.hops = hops.clone();
        this.rowToVertexId = rowToVertexId;
        this.filter = filter;
    }

    @Override
    protected Traverser.Admin<Long> processNextStart() {
        if (rows == null) {
            rows = filter == null
                    ? HopChain.run(store, seeds, hops, Graph::materializeRows)
                    : HopChain.runFiltered(store, seeds, hops, filter, Mask::materializeRows);
        }
        if (next >= rows.length) {
            throw new NoSuchElementException();
        }
        long id = rowToVertexId.applyAsLong(rows[next++]);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Traverser.Admin<Long> t = getTraversal().getTraverserGenerator()
                .generate(id, (org.apache.tinkerpop.gremlin.process.traversal.Step) this, 1L);
        return t;
    }

    @Override
    public void reset() {
        super.reset();
        rows = null;
        next = 0;
    }

    @Override
    public LanceHopIdsStep<S> clone() {
        LanceHopIdsStep<S> copy = (LanceHopIdsStep<S>) super.clone();
        copy.rows = null;
        copy.next = 0;
        return copy;
    }

    @Override
    public String toString() {
        return "LanceHopIdsStep(seeds=" + seeds.length + ", hops=" + Arrays.toString(hops)
                + (filter == null ? "" : filter.toString()) + ")";
    }
}
