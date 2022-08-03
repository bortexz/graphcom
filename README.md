# graphcom
![Clojars Project](https://img.shields.io/clojars/v/io.github.bortexz/graphcom.svg)

Dependency graph computations for Clojure(Script). Build composable computations as nodes of a graph, that can depend on other nodes of the graph as their inputs. You can also refeed the previous graph result into the next computation, allowing nodes to use their previously calculated values on next computation.

Example use case:  Rolling moving averages over timeseries where you only want to calculate the latest value added, but keep up to N latest values to be used to calculate the next value (e.g Exponential moving averages depend on their previous values), or be used in downstream computations.

## Install

### Clojure CLI/deps.edn
```clojure
io.github.bortexz/graphcom {:mvn/version "0.1.0"}
```

### Leiningen/Boot
```clojure
[io.github.bortexz/graphcom "0.0.1"]
```

## Quick Example
You can find the namespace for this example [here](./examples/quick_example.clj) 

```clojure
(ns quick-example
  (:require [bortexz.graphcom :as g]))

(defn latest-n-vals-node
  "Node that will emit as value latest up to n numbers received from input"
  [input n]
  (g/compute-node
   {:input input}
   (fn [val {:keys [input]}]
     (vec (take-last n (conj (or val []) input))))))

(defn sum-values-node
  "Node that sums all values received"
  [vals-node]
  (g/compute-node
   {:vals vals-node}
   (fn [_ {:keys [vals]}]
     (reduce + 0 vals))))

(defn mean-node
  "Node that computes the mean of the numbers of numbers-node"
  [numbers-node]
  (g/compute-node 
   {:numbers numbers-node
    :summed (sum-values-node numbers-node)}
   (fn [_ {:keys [numbers summed]}]
     (/ summed (count numbers)))))

(let [;; Node to introduce new values into the computation
      latest-val (g/input-node)

      ;; Node that returns latest 2 values introduced from latest-val
      latest-2   (latest-n-vals-node latest-val 2)

      ;; Node that computes the mean of values
      mean-node  (mean-node latest-2)

      ;; Only nodes we are interested need to be labeled when creating the graph, 
      ;; either to input a value or to retrieve their value.
      ;; Intermediary hidden nodes are recursively added without label
      graph      (g/graph {:latest-val latest-val :mean mean-node})

      ;; A graph needs to be wrapped in a context to be executed
      ctx        (g/context graph)]
  
  [(-> ctx
       (g/process {:latest-val 3})
       (g/process {:latest-val 7})
       (g/process {:latest-val 9})
       (g/value :mean)) ; => 8

   ; Immutable context 
   (-> ctx
       (g/process {:latest-val 3})
       (g/value :mean))] ; => 3
  ) ; => [8 3]
```

## Concepts
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
