# janusgraph-lgj: JanusGraph traversals answered by lance-graph-java

A proof of concept, not a JanusGraph module. It is not in the root `pom.xml`:
it needs JDK 28 with preview features, and the rest of JanusGraph compiles for
Java 11. It is built and run by `run.sh` with plain `javac`.

## What it does

`LanceHopStrategy` is a TinkerPop provider strategy for a JanusGraph traversal
source. It answers six traversal shapes from a lance-graph-java row store, as a
chain of mask hops, and leaves everything else to JanusGraph:

```
g.V(ids).out(label)^k.dedup().count()                  k >= 1
g.V(ids).out(label)^k.dedup().id()                     k >= 1
g.V(ids).out(label)^k.hasLabel(L).dedup().count()      k >= 1
g.V(ids).out(label)^k.hasLabel(L).dedup().id()         k >= 1
g.V(ids).out(label)^k.has(key, value).dedup().count()  k >= 1
g.V(ids).out(label)^k.has(key, value).dedup().id()     k >= 1
```

The last two need the property key registered with a facet (see "has(key, value)"),
and the `has` may be combined with one `hasLabel`.

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

### has(key, value)

One property equality, `has(key, value)` or `has(key, P.eq(value))`, with a `Long`
or `Integer` value, on a key registered with a facet, after the last hop, is
answered as the last frontier intersected with the mask of the rows whose facet
has that value in its low 64 payload bits. The native filter is
`RowStore.maskOfFacetTernaryMatch(facet, value, 0, -1L, 0)`: the pattern is the
value, all 64 low care bits are set. Java compares nothing and loops over no row.

```java
LanceHopStrategy.builder(store, edgeClassids, vertexIdToRow)
        .rowToVertexId(rowToVertexId)
        .vertexProperty("p", FacetId.of(7))
        .build();
```

The care mask of the high 32 bits is 0, so the high 32 bits of the register are
not compared (the facade documents the match as
`((register ^ pattern) & care) == 0` over the 12 byte register, split as low 64
and high 32 bits). A row matches when `payloadLow64At(row, facet) == value`,
whatever its `payloadHi32At` and its class id are. This is the same reading as the
property the test writes into JanusGraph, the low 64 bits. Two rows that differ
only in the high 32 bits both match.

`hasLabel(L)` and `has(key, value)` in one traversal (either order; TinkerPop
folds them into one `HasStep` with two conditions) are answered with both masks
intersected with the frontier. These fall back, each with its own reason: a key
not registered, a predicate other than equality (`gt`, `within`, `neq`), a value
that is not a `Long` or `Integer`, two property filters, more than two conditions,
a `has` between hops, no `dedup()`. A `String` value on a `Long` property is not
lowered; JanusGraph itself rejects it, and it still does.

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

## What the tests show

There are three test classes. `JanusLoweringTest` (449 checks) compares answers;
`JanusCrossingsTest` (253 checks) and `JanusAllocationTest` (10 checks) are
described under "Native
calls, measured" and "Java heap, measured". `JanusLoweringTest` holds one synthetic graph twice: as a row store, and copied
edge by edge into JanusGraph's in-memory backend. The copy is test code and is
the oracle. Measured on 2026-11-19, JanusGraph `1.3.0-SNAPSHOT` at `0ce834a`
(TinkerPop 3.8.2), lance-graph-java at `0286573` (abi 0.14), Temurin
`28+16-ea`: 449 checks pass in `JanusLoweringTest`.

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
| has(key, value), same answers | property `p` is the low 64 payload bits of facet 7, written into JanusGraph by the test. k = 1, 2, 3, three values that occur (read from the store: first, middle and last of the frontier's values) and one that occurs nowhere: lowered counts and id sets equal JanusGraph's (1 for each occurring value, 0 for the absent one) |
| has, Integer and `P.eq` | an Integer value, and `has(p, P.eq(v))`, are lowered and equal JanusGraph's |
| has with hasLabel | both orders, k = 3, `c0` and `c10`: equal to JanusGraph's; the same value with the other label gives 0 in both |
| Provenance, has | against an empty JanusGraph, plain Gremlin answers 0 and no ids; the lowered traversal answers 1 with 1 id. Against a JanusGraph whose `p` is -1 on every vertex, plain answers 0 and the lowered traversal still answers 1 |
| has falsifier | with the property read from facet 8 instead of 7 the counts and the id sets differ from JanusGraph's |
| Fallbacks, has | unregistered key, no property registered, `gt`, `within`, `neq`, a `String` value, a `Double` value, two `has`, `has` with an unregistered `has`, `has` and two `hasLabel`, `has` between hops, no `dedup()`: counted, a reason each, answers as JanusGraph (for the `String` and `Double` values JanusGraph throws, and so does the traversal with the strategy). `gt` and an unregistered key also on the `id()` shape |

Also established on the way: JanusGraph's core and in-memory backend run on
the JDK 28 preview build unchanged.

## Native calls, measured

`JanusCrossingsTest` counts native calls with `Diagnostics.crossings()`, the
number of calls across the native boundary since JVM start. For each shape the
traversal is built and its strategies are applied, a snapshot is taken, the
traversal is run to exhaustion, a snapshot is taken. Planning makes no native
call (asserted for every measurement). Releasing a handle (`close`) is a native
call and is counted. Measured on 2026-11-19 on this machine, JDK Temurin
`28+16-ea`, lance-graph-java at `0286573`.

The README used to say k + 2 for the count and `id()` shapes and k + 5 for the
`hasLabel` shapes. That was read from the code and it was wrong: it counted one
call for a hop and none for releasing masks. A hop makes two calls (create the
destination mask, then hop), and every mask is closed. Measured on 2026-11-19:

| Operation | Native calls | What they are |
|---|---|---|
| `importRows` (seed) | 2 | create the mask, describe its word lane. The words are written in process, so 1 row and 10 rows cost the same |
| `hop` | 2 | create the destination mask, the hop |
| `count` | 1 | one popcount |
| `materializeRows` | 1 per call | describe the word lane; a second call on the same mask costs 1 again |
| `maskOfFacetClass` | 2 | create the mask, match the class |
| `ternlog` | 2 | create the destination mask, the operation |
| `maskOfFacetTernaryMatch` | 2 | create the mask, match the register |
| `close`, one mask | 1 | |

| Shape | Calls | k = 1 | k = 2 | k = 3 | Breakdown |
|---|---|---|---|---|---|
| `count` | 3k + 4 | 7 | 10 | 13 | seed 2, hops 2k, count 1, closes k + 1 |
| `id` | 3k + 4 | 7 | 10 | 13 | seed 2, hops 2k, materialize 1, closes k + 1 |
| `hasLabel` + `count` | 3k + 10 | 13 | 16 | 19 | as `count`, plus class mask 2, intersection 2, closes 2 |
| `hasLabel` + `id` | 3k + 10 | 13 | 16 | 19 | as `id`, plus the same 6 |
| `has` + `count` | 3k + 10 | 13 | 16 | 19 | as `count`, plus property mask 2, intersection 2, closes 2 |
| `has` + `id` | 3k + 10 | 13 | 16 | 19 | as `id`, plus the same 6 |
| `has` + `hasLabel` + `count` | 3k + 16 | 19 | 22 | 25 | as `count`, plus the 6 of each filter |
| `has` + `hasLabel` + `id` | 3k + 16 | 19 | 22 | 25 | as `id`, plus the same 12 |

Without the closes the numbers would be 2k + 3 and 2k + 7. One more hop costs
3 calls. `hasLabel` adds 6 to the same k, and so does `has`.

The test measures each operation alone, and checks that each shape equals the
sum of its operations and equals the formula in k. The count does not depend on
the data. The same four shapes, k = 1, 2, 3, over a 2,000-row and a 20,000-row
store and over seed sets of 1, 10, 500 and (on the larger store) 5,000 rows,
make the same number of calls. The answers in those runs differ (for count at
k = 2 with the 10 seeds: 29 over 2,000 rows and 25 over 20,000), so it is not
one answer measured repeatedly. A traversal that is not lowered makes no native
call; checked for three fallback shapes, run over the JanusGraph copy. The
measurement can see a difference: k = 2 and k = 3 give different counts for
each shape, 3 apart, and `hasLabel` adds 6. The `has` shapes were measured over
two stores (2,000 and 20,000 rows) and three seed sets (1, 10 and 500 rows), with
a value taken from each run's frontier: the same number of calls in all six runs
for each shape and k. A value that occurs nowhere (answer 0) makes the same
number of calls as one that occurs. A traversal with `has(p, gt(..))` or an
unregistered key makes no native call.

The same numbers came out in seven runs of the suite.

## Java heap, measured

`JanusAllocationTest` measures `ThreadMXBean#getThreadAllocatedBytes` for the
calling thread around one execution. The method is that of lance-graph-java's
`GraphHopTest.checkAllocationIndependence`: touch the instrument first, warm up
(30 executions), take 10 samples, use the smallest as the floor (noise only
adds bytes), compare floors with a slack of 4,096 bytes (not zero, because some
jitter survives warm-up; far below the sizes being compared). Planning is
excluded: each sample runs a traversal that was already built and planned.
Measured on 2026-11-19 on this machine (4 cores), JDK Temurin `28+16-ea`.

| Query, k = 2 | Rows in store | Seeds | Answer | Bytes allocated by one execution |
|---|---|---|---|---|
| `count` | 2,000 | 10 | 29 | 1,152 |
| `count` | 64,000 | 10 | 22 | 1,152 |
| `count` | 64,000 | 5,000 | 15,538 | 1,152 |
| `id` | 2,000 | 10 | 29 ids | 3,608 |
| `id` | 64,000 | 10 | 22 ids | 3,072 |
| `id` | 64,000 | 500 | 1,808 ids | 157,544 |
| `id` | 64,000 | 5,000 | 15,538 ids | 1,386,680 |

The count shape allocates the same 1,152 bytes with a store 32 times larger
and a frontier about 500 times larger. All ten samples were the same number in
every one of the seven runs, so the spread seen was zero and the check (floors within 4,096 bytes
of each other) has room. It is not zero allocation: one execution
makes the traverser, the steps' state and the mask objects.

The `id` shape grows with the result: 89 bytes per additional id between the
smallest and the largest answer (1,383,072 more bytes for 15,509 more ids). The ids become Java `Long`s at the API edge, in a `long[]` of rows
and then one traverser each; this is where rows become Java values. The check
asserts only that a larger answer allocates more than a smaller one, and at
least 8 bytes per additional id. It does not assert a constant per id, and it
does not break the 89 bytes down.

For contrast, plain JanusGraph (no strategy, in-memory backend), the same count
query over the 2,000-row copy, 10 seeds, answer 29. A reported number, not a
claim about speed:

| Run | Floor over 7 suite runs | Spread seen within a run |
|---|---|---|
| plain, same transaction every time | 36,800 to 37,024 bytes | up to 1,400 bytes |
| plain, new transaction for each execution | 91,792 to 93,040 bytes | up to 1,000 bytes |
| lowered, same query | 1,152 bytes | none |

A 64,000-vertex JanusGraph was not built: copying 2,000 vertices and their
3,924 edges already takes about 0.75 s, and the larger copy was not needed for
a number that is only reported. Only the calling thread is counted.

## What it does not show

Against the checks in bardioc's `docs/profiles/PROFILE_DEFINITION.md`:

| Check | State |
|---|---|
| Same answers | shown, for these six shapes (ids compared as sets) |
| Provenance | shown |
| No host evaluation | holds for a lowered traversal (JanusGraph is not consulted, and the label filter is a native mask intersection, not a Java check); not instrumented |
| One crossing per request | **not met**, now counted: 3k + 4 native calls for the count and `id()` shapes (7, 10, 13 for k = 1, 2, 3) and 3k + 10 for the `hasLabel` and `has` shapes (13, 16, 19), 3k + 16 for `has` with `hasLabel` (19, 22, 25). Planning makes none. See "Native calls, measured" |
| No host bytes | what was measured: the Java heap bytes one execution allocates on the calling thread. The count shape allocated 1,152 bytes at every store and frontier size tried (2,000 and 64,000 rows, 29 to 15,538 rows in the answer). Not zero. The `id()` shape allocates about 89 bytes per id returned, because ids become Java `Long`s. What it does not show: whether native code allocates, or what a storage call would allocate. It measures a query's Java heap, not the storage path, and there is still no storage backend. See "Java heap, measured" |
| No copy at ingest | not applicable: there is no ingest. The row store is generated from a seed |
| Refusals are named | shown for the five count fallbacks, the two id fallbacks and the hasLabel fallbacks |

Materialization happens at the API edge only: the ids are read out of the
store once, after the last hop, to hand them to the caller. Nothing in the
chain materializes rows between hops. What that costs in Java heap is measured
below: about 89 bytes per returned id. Its time was not measured.

The label filter is label equality only. It is mapped by hand to one facet's
class id. It is not a general `has()` or `where()`. The property filter is one
equality on the low 64 bits of one facet, mapped by hand; there is no range, no
`within`, no string value, and it does not look at the high 32 bits. It does not go through lance-graph's Quack query surface, which
lance-graph-java does not call today; it is `maskOfFacetClass` plus an
intersection. The intersection is one `Mask.ternlog` call (truth table `0xC0`,
`a & b`), which is two native calls. `Graph` has `minus` but no `and`, and does
not expose its frontier mask, so the chain for this shape runs on `Mask`
directly. With `Graph` alone, `a.minus(a.minus(b))` would be two `minus` calls
of two native calls each.

The test derives each JanusGraph vertex label from the row store (`"c" + classid`
of facet 3), so the two stores agree by construction. A real graph needs a real
agreement, which is not shown.

Not here at all: writes, schema (the test maps one edge label to one class id
by hand, and vertex labels and the one property the same way), any property filter
other than one equality, any step other than `out`, the one `hasLabel` and the one `has`, any terminal step other than `count()` and `id()`, a
JanusGraph storage backend, and any timing (calls and heap bytes are counted,
time is not).

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
