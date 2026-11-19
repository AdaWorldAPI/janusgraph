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
import com.adaworldapi.lancegraph.FacetId;
import com.adaworldapi.lancegraph.Mask;
import com.adaworldapi.lancegraph.RowStore;

import java.util.Arrays;
import java.util.NoSuchElementException;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;

/**
 * The one step a lowered traversal consists of: it answers
 * {@code V(ids).out(label)^k.dedup().count()}, or the same with one {@code hasLabel(L)} before
 * {@code dedup()}, from the row store as a chain of mask hops and emits the count once. It reads nothing from JanusGraph.
 */
final class LanceHopCountStep<S> extends AbstractStep<S, Long> {

    private final transient RowStore store;
    private final long[] seeds;
    private final int[] hops;
    private final FacetId labelFacet;
    private final int labelClassid;
    private boolean done;

    /** {@code labelFacet} is null for the unfiltered shape. */
    LanceHopCountStep(Traversal.Admin<?, ?> traversal, RowStore store, long[] seeds, int[] hops,
                      FacetId labelFacet, int labelClassid) {
        super(traversal);
        this.store = store;
        this.seeds = seeds.clone();
        this.hops = hops.clone();
        this.labelFacet = labelFacet;
        this.labelClassid = labelClassid;
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
        return labelFacet == null
                ? HopChain.run(store, seeds, hops, Graph::count)
                : HopChain.runFiltered(store, seeds, hops, labelFacet, labelClassid, Mask::count);
    }

    @Override
    public void reset() {
        super.reset();
        done = false;
    }

    @Override
    public String toString() {
        return "LanceHopCountStep(seeds=" + seeds.length + ", hops=" + Arrays.toString(hops)
                + (labelFacet == null ? "" : ", label=" + labelFacet + ":" + labelClassid) + ")";
    }
}
