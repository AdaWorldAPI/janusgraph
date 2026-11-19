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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** The mask hop chain shared by the lowered steps. Every Graph in the chain is closed. */
final class HopChain {

    private HopChain() {}

    /**
     * Runs seed, one hop per classid, then {@code terminal} on the last Graph, and closes every
     * Graph in the chain afterwards.
     */
    static <T> T run(RowStore store, long[] seeds, int[] hops, Function<Graph, T> terminal) {
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
            return terminal.apply(g);
        } finally {
            for (Graph g : chain) {
                g.close();
            }
        }
    }

    /**
     * As {@link #run}, then intersects the last frontier with the mask of the rows whose
     * {@code labelFacet} carries {@code labelClassid}, and runs {@code terminal} on the result.
     *
     * <p>Native calls: seed 1, one per hop, the label mask 2 (create, class match), the
     * intersection 1 ({@code ternlog} with the truth table 0xC0, {@code a & b}), the terminal 1:
     * k + 5 in all. {@code Graph} has no public {@code and}; {@code a.minus(a.minus(b))} would
     * cost two calls for the intersection, and {@code Graph} does not expose its frontier
     * {@code Mask}, so the chain here runs on {@code Mask}s directly. Every Mask is closed.
     */
    static <T> T runFiltered(RowStore store, long[] seeds, int[] hops, FacetId labelFacet,
                             int labelClassid, Function<Mask, T> terminal) {
        List<Mask> open = new ArrayList<>();
        try {
            Mask m = store.importRows(seeds);
            open.add(m);
            for (int classid : hops) {
                m = store.hop(classid, m);
                open.add(m);
            }
            Mask label = store.maskOfFacetClass(labelFacet, labelClassid);
            open.add(label);
            Mask both = m.ternlog(label, label, 0xC0);
            open.add(both);
            return terminal.apply(both);
        } finally {
            RuntimeException failure = null;
            for (Mask m : open) {
                try {
                    m.close();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
