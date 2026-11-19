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
import com.adaworldapi.lancegraph.RowStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;

/**
 * The one step a lowered traversal consists of: it answers
 * {@code V(ids).out(label)^k.dedup().count()} from the row store as a chain of mask hops and
 * emits the count once. It reads nothing from JanusGraph.
 */
final class LanceHopCountStep<S> extends AbstractStep<S, Long> {

    private final transient RowStore store;
    private final long[] seeds;
    private final int[] hops;
    private boolean done;

    LanceHopCountStep(Traversal.Admin<?, ?> traversal, RowStore store, long[] seeds, int[] hops) {
        super(traversal);
        this.store = store;
        this.seeds = seeds.clone();
        this.hops = hops.clone();
    }

    @Override
    protected Traverser.Admin<Long> processNextStart() {
        if (done) {
            throw new NoSuchElementException();
        }
        done = true;
        @SuppressWarnings({"rawtypes", "unchecked"})
        Traverser.Admin<Long> t = getTraversal().getTraverserGenerator()
                .generate(count(), (org.apache.tinkerpop.gremlin.process.traversal.Step) this, 1L);
        return t;
    }

    private long count() {
        List<Graph> chain = new ArrayList<>();
        try {
            Graph g = Graph.open(store);
            chain.add(g);
            g = g.from(seeds);
            chain.add(g);
            for (int classid : hops) {
                g = g.hop(classid);
                chain.add(g);
            }
            return g.count();
        } finally {
            for (Graph g : chain) {
                g.close();
            }
        }
    }

    @Override
    public void reset() {
        super.reset();
        done = false;
    }

    @Override
    public String toString() {
        return "LanceHopCountStep(seeds=" + seeds.length + ", hops=" + Arrays.toString(hops) + ")";
    }
}
