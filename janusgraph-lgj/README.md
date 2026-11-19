# janusgraph-lgj: JanusGraph traversals answered by lance-graph-java

A proof of concept, not a JanusGraph module. It is not in the root `pom.xml`:
it needs JDK 28 with preview features, and the rest of JanusGraph compiles for
Java 11. It is built and run by `run.sh` with plain `javac`.

## What it does

`LanceHopStrategy` is a TinkerPop provider strategy for a JanusGraph traversal
source. It answers one traversal shape from a lance-graph-java row store, as a
chain of mask hops, and leaves everything else to JanusGraph:

```
g.V(ids).out(label)^k.dedup().count()        k >= 1
```

`repeat(out(label)).times(k)` reaches the same shape. The caller's Gremlin is
unchanged; the strategy is registered with `withStrategies(...)`.

It is the JanusGraph counterpart of the strategy in lance-graph-java's
`interop/tinkerpop`. Two things differ: a JanusGraph vertex id is not a row
number, so the caller supplies the mapping; and the strategy declares
JanusGraph's own provider strategies as coming after it, so JanusGraph starts
no storage read for a traversal it answers.

## What the test shows

`JanusLoweringTest` holds one synthetic graph twice: as a row store, and copied
edge by edge into JanusGraph's in-memory backend. The copy is test code and is
the oracle. Measured on 2026-11-19, JanusGraph `1.3.0-SNAPSHOT` at `0ce834a`
(TinkerPop 3.8.2), lance-graph-java at `0286573` (abi 0.14), Temurin
`28+16-ea`: 42 checks pass.

| Check | Result |
|---|---|
| Same answers, k = 1, 2, 3 | 19, 29, 55 from both |
| The comparison can fail | with the id mapping off by one row the counts differ |
| Really lowered | the traversal that ran is one `LanceHopCountStep` |
| Provenance | against an empty JanusGraph, plain Gremlin answers 0 and the lowered traversal answers 29 |
| Fallbacks | five shapes that are not lowered are counted, give a reason and answer as JanusGraph does |

Also established on the way: JanusGraph's core and in-memory backend run on
the JDK 28 preview build unchanged.

## What it does not show

Against the checks in bardioc's `docs/profiles/PROFILE_DEFINITION.md`:

| Check | State |
|---|---|
| Same answers | shown, for this one shape |
| Provenance | shown |
| No host evaluation | holds for a lowered traversal (JanusGraph is not consulted); not instrumented |
| One crossing per request | **not met**: a k-hop count makes k + 2 native calls (seed, one per hop, count) |
| No host bytes | not measured |
| No copy at ingest | not applicable: there is no ingest. The row store is generated from a seed |
| Refusals are named | shown for the five fallbacks |

Not here at all: writes, schema (the test maps one edge label to one class id
by hand), property filters, any step other than `out`, a JanusGraph storage
backend, and any timing.

The id mapping in the test is arithmetic (`fromVertexId(id) - 1`) because the
test sets its own vertex ids. A graph whose ids JanusGraph assigned needs a
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
