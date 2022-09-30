# graphcom
[![Clojars Project](https://img.shields.io/clojars/v/io.github.bortexz/graphcom.svg)](https://clojars.org/io.github.bortexz/graphcom) [![Cljdoc badge](https://cljdoc.org/badge/io.github.bortexz/graphcom)](https://cljdoc.org/d/io.github.bortexz/graphcom)

Composable incremental computations engine for Clojure(Script).

## Rationale

A **computation**, as described on Wikipedia, can be thought as any type of arithmetic or non-arithmetic calculation that follows a well-defined model (e.g., an algorithm). In Clojure, one might define a computation as a pure function that given a set of inputs, returns a specific output.

An **incremental** computation is a kind of computation that uses it's previous output as one of the inputs when new data is available. Thus, the accumulated output needs to be 'stored' to be used on each iteration. It can also be thought as a 'stateful' computation. Normally, one could store the output outside the function and pass it around as an argument along the new inputs, to keep the function pure and without side effects.

This approach works well for independent and scattered computations, but falls short when making these computations **composable** with each other (i.e the output of a computation is the input for another). When a computation is used as the input for many other, we want to ensure that it's output is only calculated once, and used on every dependant. We also want to be able to access the outputs (also called **values**) of intermediary computations. 

To achieve the above, we make the **directed acyclic graph** of computations explicit, by defining each computation in a **compute-node**, and using **input-node**s to introduce new values into the graph. Each **compute-node** has sources, a map of other nodes it depends upon, as well as a **handler**, a function accepting the value calculated on the previous iteration, and a map of inputs containing the currently calculated value for each source.

A **graph** only contains the nodes and it's relationship (an adjacency map), and needs to be wrapped within a **context** to be executed. A context contains a graph, as well as the accumulated values for all nodes in the graph, and compilations of the graph to be traversed by the **processor** to compute new values. This **context** is also immutable, and each time the context is processed with new inputs, returns a new context containing the new values of the graph. This allows the context to be thread-safe, containing a snapshot of computed values, and can be queried and processed by different threads (e.g speculative computations of future inputs).

**Processors** are responsible of compiling and traversing the graph in the correct order and calling the compute node's handlers appropiately acumulating the new context values. By allowing customization of the topological compiling and traversing of the graph, we can build different processors adapted by use case. As an example, two different processor implementations are offered: A sequential one that traverses a flattened topological sort, and a parallel one that computes nodes at the same depth level in the graph in parallel using pmap.

With this, we have achieved a very similar ergonomics to the described in the **incremental** paragraph; we call a function that accepts a **context** (new or previosly computed value) and a new **set of inputs**, and returns an updated context, to be used on further iterations, in a pure way.

## Use case

The main motivation for building graphcom was to serve as the computation engine for [tacos](https://github.com/bortexz/tacos), a library of timeseries technical indicators that are incrementally computed with each new data point, each node keeping it's own accumulated timeseries. Having it extracted into a library instead of a utility inside tacos has other advantages, as indicators can be composed with other types of computations that might not be timeseries-related, or are outside the scope of tacos.

## Install

### Clojure CLI/deps.edn
```clojure
io.github.bortexz/graphcom {:mvn/version "0.1.1"}
```

### Leiningen/Boot
```clojure
[io.github.bortexz/graphcom "0.1.1"]
```

## Quick Example
You can find the namespace for this example [here](./examples/quick_example.clj). 

```clojure
(ns quick-example
  (:require [bortexz.graphcom :as g]))

(defn incremental-timeseries
  "Node that accumulates new timestamp values into a sorted-map up to max-size items."
  [input max-size]
  (g/compute-node
   {:input input}
   (fn [sc {:keys [input]}]
     (into (sorted-map) (take-last max-size (merge (or sc (sorted-map)) input))))))

(defn moving-average
  "Node that computes a moving average of timseries node `source` with given `period`, only for the timestamps specified in `input`."
  [source input period]
  (g/compute-node
   {:input input
    :source source}
   (fn [ma {:keys [input source]}]
     (let [new-timestamps (keys input)]
       (reduce (fn [acc ts]
                 (let [src-tail  (->> (subseq source <= ts)
                                      (map val)
                                      (take-last period))
                       tail-size (count src-tail)]
                   (if (>= tail-size period)
                     (assoc acc ts (/ (reduce + 0 src-tail) tail-size))
                     acc)))
               (or ma (sorted-map))
               new-timestamps)))))

(let [input (g/input-node)
      timeseries (incremental-timeseries input 10)
      moving-avg (moving-average timeseries input 3)

      ctx (g/context
            (g/graph {:input input
                      :avg moving-avg}))]
  (-> ctx
      (g/process {:input (sorted-map 1 1 2 2)})
      (g/process {:input (sorted-map 3 3 4 4)})
      (g/values))) 
      
; => {:input nil, :avg {3 2, 4 3}} ; Inputs do not store their value
```

## Detailed usage
### **Node**
A node of the graph. Nodes can be sources (dependencies) of other nodes when building the graph.

There are two types of nodes:

### Input node
A node without sources, also called `root` nodes. Their use is to introduce new values into the graph for downstream computations.

```clojure
(g/input-node)
```

### Compute node
A node that has dependencies on other nodes, and computes it's value from its sources. Sources can be other compute-nodes or input-nodes. In the case of input-nodes, the values will be nil if the inputs are not specified as part of the current graph computation.

```clojure
(g/compute-node {:a-source a-source-node}
                (fn [current-value {:keys [a-source] :as _sources-values}]
                  ;; Return new value of this node,
                  ;; current-value contains the previously computed value of this node
                  ;; a-source contains the current value of a-source-node
                  ))
```

### **Graph**
For nodes to be used, they must be added to a graph. Nodes are added to a graph with a certain label, that can be later used to retrieve their values (compute-nodes) or to introduce new values into the graph (input-nodes). 

When adding a node to a graph with a label, all sources are also added recursively, without a label. This allows compute-nodes to create their own internal nodes, when needed.

```clojure
(def input (g/input-node))
(def compute (g/compute-node {:input1 input1} handler))

(def graph (g/graph {:input input :compute compute})) 
;; Same as:
(def graph (-> (g/graph)
               (g/add :input input)
               (g/add :compute compute)))

;; The graph is immutable, so every step returns the updated graph

```

### **Processor**
To be able to execute a graph, we need a graph processor. Two default processors are offered:

```clojure
(g/sequential-processor)
(g/parallel-processor) ;; Only CLJ
```

`sequential-processor` Will process a flattened version of the topological sort of the graph sequentially.

`parallel-processor` Uses a topological sort where each step consists of nodes that can be executed in parallel (all their sources have already been executed), and executes each step using `pmap`.

### **Context**
The context glues the graph with a specific processor, the current values, and processor compilations. A context is also immutable, and functions that change it's value return an updated context.

```Clojure
;; Create context
(g/context graph) ; Uses sequential-processor by default
(g/context graph processor)

;; Process the context with given inputs
(g/process context {:input1 value1 :input2 value2})
;; Get current graph values as a map of {<label> <value>}
(g/values context) 
;; Get current value for node node under <label> 
;; (faster than (-> (g/values context) label) )
(g/value context label) 

;; Adds a compilation to context to process graph when :input1 and :input2 are specified in `process`. 
;; If a compilation doesn't exist for the input set when executing `process`, it will be added automatically and stored in the context for future `process` calls.
(g/precompile context #{:input1 :input2}) 

```


## Custom nodes

All nodes implement the g/Node protocol:

```clojure
(defprotocol Node
  (-id [this] "Returns id of node. Must be unique inside a graph."))
```

Nodes created with `g/input-node` or `g/compute-node` have an id randomly created.

For input nodes, a protocol marker exists, although no function needs to be implemented. When you process the context or precompile it, the inputs must satisfy Input.

```clojure
(defprotocol Input)
```

Compute protocol:

```clojure
(defprotocol Compute
  (-sources [this] "Returns map of {<source-label> <source-node>}")
  (-compute [this current-value sources-values] "Returns new value of node"))
```

## Custom Processors

A processor implements the g/Processor protocol. This protocol consists of two functions:
```clojure
(defprotocol Processor
  (-compile [this graph inputs])
  (-process [this graph compilation values input-map]))
```
`-compile` generates a processor-specific data structure that will allow to traverse the graph, for a certain set of input ids. Usually it will be some kind of topological sort. A `-topological-sort` function is offered in case it might be useful for custom Processor implementation.

`-process` must return a new map of `{<compute-id> <value>}`, that must contain all nodes of the graph (Not all nodes need to be executed all the time, depending on the input set, but `-process` must return all values either way. Usually you would merge new executed values to current values). `input-map` is a map of `{<input-id> <value>}`, and `compilation` is the return value of `-compile` for the inputs that are going to be propagated through the graph.


## License

Copyright © 2022 Alberto Fernández

Distributed under the Eclipse Public License version 1.0.
