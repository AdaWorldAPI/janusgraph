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
}
