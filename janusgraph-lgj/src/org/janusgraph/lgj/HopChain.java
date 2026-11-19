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
     * As {@link #run}, then intersects the last frontier with each filter of {@code filter} and
     * runs {@code terminal} on the result.
     *
     * <p>The label filter is the mask of the rows whose label facet carries the class id (2
     * native calls: create, class match). The property filter is the mask of the rows whose
     * property facet register matches the value in its low 64 bits, high 32 bits not compared
     * ({@code maskOfFacetTernaryMatch}, 2 calls: create, match). Each intersection is 2 calls
     * ({@code ternlog} with the truth table 0xC0, {@code a & b}: create the destination, run it).
     * Native calls, measured by {@code JanusCrossingsTest}: the seed import 2, two per hop, the
     * terminal 1, one per mask closed, and per filter 4 plus 2 closes: 3k + 10 with one filter,
     * 3k + 16 with both. {@code Graph} has no public {@code and}; {@code a.minus(a.minus(b))}
     * would cost four calls for the intersection, and {@code Graph} does not expose its frontier
     * {@code Mask}, so the chain here runs on {@code Mask}s directly. Every Mask is closed.
     */
    static <T> T runFiltered(RowStore store, long[] seeds, int[] hops, HopFilter filter,
                             Function<Mask, T> terminal) {
        List<Mask> open = new ArrayList<>();
        Throwable primary = null;
        try {
            Mask m = store.importRows(seeds);
            open.add(m);
            for (int classid : hops) {
                m = store.hop(classid, m);
                open.add(m);
            }
            if (filter.labelFacet() != null) {
                Mask label = store.maskOfFacetClass(filter.labelFacet(), filter.labelClassid());
                open.add(label);
                // a & b in one native call: ternlog(a, b, b) with truth table 0xC0.
                m = m.ternlog(label, label, 0xC0);
                open.add(m);
            }
            if (filter.propertyFacet() != null) {
                // All 64 low bits care, value is the pattern; the high 32 bits are don't-care.
                Mask prop = store.maskOfFacetTernaryMatch(filter.propertyFacet(),
                        filter.propertyValue(), 0, -1L, 0);
                open.add(prop);
                m = m.ternlog(prop, prop, 0xC0);
                open.add(m);
            }
            return terminal.apply(m);
        } catch (RuntimeException | Error e) {
            primary = e;
            throw e;
        } finally {
            // Close every mask. A failure of the chain itself is never replaced by a close
            // failure; close failures are attached to it, or thrown if there was none.
            RuntimeException closeFailure = null;
            for (Mask m : open) {
                try {
                    m.close();
                } catch (RuntimeException e) {
                    if (primary != null) {
                        primary.addSuppressed(e);
                    } else if (closeFailure == null) {
                        closeFailure = e;
                    } else {
                        closeFailure.addSuppressed(e);
                    }
                }
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
