# janusgraph-lgj: JanusGraph traversals answered by lance-graph-java

A proof of concept, not a JanusGraph module. It is not in the root `pom.xml`:
it needs JDK 28 with preview features, and the rest of JanusGraph compiles for
Java 11. It is built and run by `run.sh` with plain `javac`.

## What it does

`LanceHopStrategy` is a TinkerPop provider strategy for a JanusGraph traversal
source. It answers four traversal shapes from a lance-graph-java row store, as a
chain of mask hops, and leaves everything else to JanusGraph:

```
g.V(ids).out(label)^k.dedup().count()                  k >= 1
g.V(ids).out(label)^k.dedup().id()                     k >= 1
g.V(ids).out(label)^k.hasLabel(L).dedup().count()      k >= 1
g.V(ids).out(label)^k.hasLabel(L).dedup().id()         k >= 1
```

The `id()` shape returns the JanusGraph vertex ids of the reached vertices,
one traverser each, in ascending row order. It is answered by
`LanceHopIdsStep`: the hop chain, then `Graph.materializeRows()`, then a
mapping from row number to vertex id. Without `dedup()` it is not lowered,
because it would list walks, not vertices.

### hasLabel

One `hasLabel(L)` with one label, after the last hop, is answered as the last
frontier intersected with the mask of the rows whose label facet carries the
class id mapped to L. The intersection is a native mask operation. Java checks
no vertex and reads nothing from JanusGraph.

The caller says how a vertex label maps to the row store: one `FacetId` and a
`Map<String, Integer>` from label to class id in that facet. They are given
through a builder, so the constructors stay as they were:

```java
LanceHopStrategy.builder(store, edgeClassids, vertexIdToRow)
        .rowToVertexId(rowToVertexId)
        .vertexLabels(labelFacet, vertexLabelClassids)
        .build();
```

Without the mapping, `hasLabel` is not lowered and the reason says so. A label
that is not in the map is not lowered either; the reason names it.

In TinkerPop, `hasLabel("x")` is a `HasStep` with one `HasContainer`, key `~label`
(`T.label.getAccessor()`), predicate `eq`, value `"x"`. Only that is lowered.
These fall back, each with its own reason: `hasLabel("a", "b")` (a `within`),
`hasLabel(P.neq(..))`, `has("prop", ..)`, a `hasLabel` between hops, and two
`hasLabel` steps (TinkerPop folds them into one `HasStep` with two conditions).
`dedup().hasLabel(L)` is reordered by TinkerPop's own `FilterRankingStrategy`
before this strategy runs, so it reaches the lowered shape. The `HasStep` is still
a plain TinkerPop step when this strategy sees it, because JanusGraph's strategies,
which fold it into a `JanusGraphStep`, run after.

`repeat(out(label)).times(k)` reaches the same shape. The caller's Gremlin is
unchanged; the strategy is registered with `withStrategies(...)`.

It is the JanusGraph counterpart of the strategy in lance-graph-java's
`interop/tinkerpop`. Two things differ: a JanusGraph vertex id is not a row
number, so the caller supplies the mapping (vertex id to row, and for the
`id()` shape also row to vertex id, as the fourth constructor argument; the
three-argument constructor is count-only and the `id()` shape falls back with
the reason "id() needs the row-to-vertex-id mapping"); and the strategy declares
JanusGraph's own provider strategies as coming after it, so JanusGraph starts
no storage read for a traversal it answers.

## What the test shows

`JanusLoweringTest` holds one synthetic graph twice: as a row store, and copied
edge by edge into JanusGraph's in-memory backend. The copy is test code and is
the oracle. Measured on 2026-11-19, JanusGraph `1.3.0-SNAPSHOT` at `0ce834a`
(TinkerPop 3.8.2), lance-graph-java at `0286573` (abi 0.14), Temurin
`28+16-ea`: 236 checks pass.

| Check | Result |
|---|---|
| Same answers, k = 1, 2, 3 | 19, 29, 55 from both |
| The comparison can fail | with the id mapping off by one row the counts differ |
| Really lowered | the traversal that ran is one `LanceHopCountStep` |
| Clone after running | a clone of a lowered traversal that already ran answers afresh, with the same count |
| Provenance | against an empty JanusGraph, plain Gremlin answers 0 and the lowered traversal answers 29 |
| Fallbacks | five shapes that are not lowered are counted, give a reason and answer as JanusGraph does |
| Same ids, k = 1, 2, 3 | the id sets equal JanusGraph's, same sizes, no duplicates, and the size equals the count shape's answer |
| The id comparison can fail | with the row-to-id mapping off by one row the id sets differ |
| Really lowered, ids | the traversal that ran is one `LanceHopIdsStep` |
| Order | the lowered ids come out in ascending row order |
| Provenance, ids | against an empty JanusGraph, plain Gremlin returns no ids and the lowered traversal returns the ids from the row store |
| Fallbacks, ids | `out().id()` without `dedup()`, and the count-only constructor, are counted, give a reason and answer as JanusGraph does |
| Clone after running, ids | a clone of a lowered id traversal that already ran answers afresh, with the same set |
| hasLabel, same answers | labels `c0` and `c10` on facet 3, k = 1, 2, 3: counts 1, 3, 6 and 2, 6, 6 from both, against 19, 29, 55 unfiltered. Id sets equal JanusGraph's, same sizes |
| The filter narrows | the filtered count is above 0 and below the unfiltered count for both labels at k = 1 and 2 |
| Really lowered, hasLabel | the traversal that ran is one `LanceHopCountStep`, or one `LanceHopIdsStep` |
| Provenance, hasLabel | against an empty JanusGraph, plain Gremlin answers 0 and no ids; the lowered traversal answers 6 for `c10` at k = 2, with that many ids |
| hasLabel falsifier, mapping | with `c0` and `c10` mapped to each other's class id the answers differ from JanusGraph's |
| hasLabel falsifier, facet | with the label read from facet 4 instead of 3 the answers differ from JanusGraph's |
| Fallbacks, hasLabel | no mapping given, a label not in the map, `within`, `neq`, `has("n", ..)`, `hasLabel` between hops, two `hasLabel`, no `dedup()`: counted, a reason each, answers as JanusGraph. `within` and an unmapped label also on the `id()` shape |
| Clone after running, hasLabel | a clone of a filtered count or id traversal that already ran answers afresh, with the same result |

Also established on the way: JanusGraph's core and in-memory backend run on
the JDK 28 preview build unchanged.

## What it does not show

Against the checks in bardioc's `docs/profiles/PROFILE_DEFINITION.md`:

| Check | State |
|---|---|
| Same answers | shown, for these four shapes (ids compared as sets) |
| Provenance | shown |
| No host evaluation | holds for a lowered traversal (JanusGraph is not consulted, and the label filter is a native mask intersection, not a Java check); not instrumented |
| One crossing per request | **not met**: a k-hop count makes k + 2 native calls (seed, one per hop, count); the `id()` shape makes k + 2 as well (seed, one per hop, materialization); the `hasLabel` shape makes k + 5 (seed, one per hop, two for the label mask, one intersection, count or materialization) |
| No host bytes | not measured |
| No copy at ingest | not applicable: there is no ingest. The row store is generated from a seed |
| Refusals are named | shown for the five count fallbacks, the two id fallbacks and the hasLabel fallbacks |

Materialization happens at the API edge only: the ids are read out of the
store once, after the last hop, to hand them to the caller. Nothing in the
chain materializes rows between hops. The cost of that call grows with the
number of reached rows; it was not measured.

The label filter is label equality only. It is mapped by hand to one facet's
class id. It is not a general `has()` or `where()`, and there is no property
filter. It does not go through lance-graph's Quack query surface, which
lance-graph-java does not call today; it is `maskOfFacetClass` plus an
intersection. The intersection is one `Mask.ternlog` call (truth table `0xC0`,
`a & b`). `Graph` has `minus` but no `and`, and does not expose its frontier
mask, so the chain for this shape runs on `Mask` directly. With `Graph` alone,
`a.minus(a.minus(b))` would be two calls.

The test derives each JanusGraph vertex label from the row store (`"c" + classid`
of facet 3), so the two stores agree by construction. A real graph needs a real
agreement, which is not shown.

Not here at all: writes, schema (the test maps one edge label to one class id
by hand, and vertex labels the same way), property filters, any step other than
`out` and the one `hasLabel`, any terminal step other than `count()` and `id()`, a
JanusGraph storage backend, and any timing.

The id mappings in the test are arithmetic (`fromVertexId(id) - 1` and its
inverse) because the test sets its own vertex ids. A graph whose ids JanusGraph assigned needs a
real mapping, which is the open question of one ordinal axis.

## Running it

```
mvn -q -pl janusgraph-inmemory -am -DskipTests install
mvn -q -pl janusgraph-inmemory dependency:build-classpath -Dmdep.outputFile=/tmp/jg.cp
( cd $LGJ_HOME/native/lgj-abi && cargo build --release )
LGJ_HOME=... JG_CP=/tmp/jg.cp janusgraph-lgj/run.sh
```

`LGJ_JDK`, `LGJ_SO` and `LGJ_OUT` override the JDK, the native library and the
output directory.
