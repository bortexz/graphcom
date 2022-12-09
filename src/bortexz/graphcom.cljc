(ns bortexz.graphcom
  (:refer-clojure :exclude [random-uuid])
  (:require [medley.core :refer [queue random-uuid map-keys map-vals]]
            [clojure.set :as set]))

(defprotocol Node
  (-id [this] "Returns id of this node. ID must be unique on a graph."))

(defprotocol Input)

(defprotocol Compute
  (-sources [this] "Returns a map of {<source-label> <source-node>}")
  (-compute [this curr-val sources] "Computes the new value of a compute-node"))

(defrecord InputNode [id]
  Node
  (-id [_] id)
  Input)

(defrecord ComputeNode [id sources handler]
  Node
  (-id [_] id)
  Compute
  (-sources [_] sources)
  (-compute [_ curr-val sources] (handler curr-val sources)))

(defrecord Graph [labels nodes adjacency-map])

(defrecord Context [graph values compilations processor])

(defprotocol Processor
  "Compiles and processes a graph context for the given inputs."
  (-compile [this graph input-set]
    "Creates a compilation to traverse the `graph` for the given `input-set` (set of input-nodes ids).
     The result will be passed to -process as `compilation`.")
  (-process [this graph compilation values inputs-map]
    "Processes the `graph` traversing the given `compilation`, 
     using the current `values` of the context and the given 
     `inputs-map` as {<input-id> <value>}. Returns the new values
     of the context as {<compute-id> <value>}."))

(defn node?
  "Checks if x is any type of graph node"
  [x]
  (satisfies? Node x))

(defn input-node?
  "Checks if x is an input node"
  [x]
  (and (satisfies? Input x) (satisfies? Node x)))

(defn compute-node?
  "Returns true iff x is a compute node"
  [x]
  (and (satisfies? Node x) (satisfies? Compute x)))

(defn- cycles?
  "Checks if there are cycles on `adjacency-map` starting from start-id.
  Returns true if there are cycles, false otherwise."
  ([adj-m start-id]
   (cycles? #{} start-id adj-m))
  ([path adj-m start-id]
   (if (contains? path start-id)
     true
     (boolean (some (partial cycles? (conj path start-id) adj-m) (get adj-m start-id))))))

(defn- reverse-adjacency-map
  "Given an adjacency map, returns a reversed adjacency map (dependencies to dependants or viceversa)"
  [adjacency-map]
  (->> adjacency-map
       (map key)
       (map (fn [id]
              [id (->> adjacency-map
                       (filter (fn [[_ s]] (contains? s id)))
                       (map key)
                       (into #{}))]))
       (into {})))

(defn- labelled-paths
  [{ls :labels src-map :adjacency-map nodes :nodes} node-id]
  (let [id->label (set/map-invert ls)
        sink-map (reverse-adjacency-map src-map)
        get-paths (fn -get-paths [path nid]
                    (if-let [l (id->label nid)]
                      [(vec (conj path l))]
                      (let [sinks (get sink-map nid)]
                        (set
                         (mapcat
                          (fn [sink-id]
                            (let [sink-sources (-sources (get nodes sink-id))
                                  sink-label (get (set/map-invert
                                                   (map-vals -id sink-sources))
                                                  nid)]
                              (-get-paths (conj path sink-label) sink-id)))
                          sinks)))))]
    (get-paths (list) node-id)))

(defn- add-recursive
  [{:keys [nodes] :as graph} node]
  (let [id (-id node)]
    (if-let [existing (get nodes id)]
      (do
        (assert (identical? node existing) "A different node with same ID already exists in the graph")
        graph)
      (let [source-nodes (if (compute-node? node) (vals (-sources node)) [])
            graph (reduce add-recursive graph source-nodes)
            graph (-> graph
                      (update :nodes assoc id node)
                      (update :adjacency-map assoc id (into #{} (map -id) source-nodes)))]
        graph))))

(defn add
  "Adds `node` to `graph` with the given `label`.
   Recursively adds all sources of `node` that do not exist yet on `graph`, without label."
  [graph label node]
  (assert (node? node) "node must satisty the Node protocol")
  (assert (not (get (:labels graph) label)) "A node already exists with this label.")
  (let [id (-id node)
        graph (-> graph
                  (update :labels assoc label id)
                  (add-recursive node))]
    (assert (not (cycles? (:adjacency-map graph) id))
            (str "Resulting graph contains cycles when adding node with label" label))
    graph))

(defn input-node
  "Returns a graph node that can be used to input values when processing the graph context.
   input-nodes do not hold their values through calls to [[[process]]], and only have a non-nil value
   when they are specified as inputs to [[process]]."
  []
  (->InputNode (random-uuid)))

(defn compute-node
  "Returns a graph node that computes a new value from the values of its `sources` using `handler`.
   `sources` is a map as {<source-label> <soure-node>}.
   `handler` is a 2-arity function accepting the current node value and a map of {<source-label> <source-value>}.
   Sources that are inputs to the graph will be nil unless specified as inputs to [[process]]."
  [sources handler]
  (assert (seq sources) "Sources of a compute-node cannot be empty.")
  (->ComputeNode (random-uuid) sources handler))

(defn graph
  "Returns a graph, empty or from a map of {label node}."
  ([]
   (->Graph {} {} {}))
  ([nodes-map]
   (reduce-kv add (graph) nodes-map)))

(defn- build-topology-depths
  "Returns a map of node-ids and a set of depths as the values."
  [depths-map depth next-ids adjacency-map]
  (if (not (seq next-ids))
    depths-map
    (let [updated-depths (reduce (fn [acc id]
                                   (update acc id (fnil conj #{}) depth))
                                 depths-map
                                 next-ids)
          next-ids (->> (select-keys adjacency-map next-ids)
                        (mapcat second))]
      (build-topology-depths updated-depths (inc depth) next-ids adjacency-map))))

(defn -topological-sort
  "Returns a parallel topological sort of the graph, as a collection of steps,
   where each step is a collection of node ids that can be run in parallel.
   Useful as a base for certain processor compilations."
  [graph input-ids]
  (let [depths-map (build-topology-depths {} 0 input-ids (reverse-adjacency-map (:adjacency-map graph)))]
    (->> (map-vals (fn [depths] (apply max depths)) depths-map)
         (group-by second)
         (sort)
         (map (comp #(map first %) second)))))

;; Graph inputs

(defn- input-ids
  "Returns ids of input nodes of g"
  [g]
  (->> g
       :nodes
       (map second)
       (filter input-node?)
       (map -id)))

;; Process helpers

(defn -base-compilation
  "Base compilation used by sequential and parallel processors. 
   When parallel? is false, the topology is flattened to be 1-dimensional.
   When no inputs specified, it will create a compilation for all input nodes of the graph."
  ([graph parallel?]
   (-base-compilation graph (input-ids graph) parallel?))
  ([graph input-ids parallel?]
   (let [inputs (set input-ids)]
     (cond-> (rest (-topological-sort graph inputs))
       (not parallel?) (flatten)))))

(defn -sources-values
  "Returns a map of {<source-label> <source-value>}, given a `node`, current processor accumulated `values`
   and currently processing `inputs`.
   Useful for implementing graph-processors."
  [compute-node values inputs]
  (map-vals (fn [src]
              (let [id (-id src)]
                (or (get values id) (get inputs id))))
            (-sources compute-node)))

(defn values
  "Returns current `context` values as {label value}."
  [{:keys [graph values]}]
  (map-vals (fn [id] (get values id)) (:labels graph)))

(defn value
  "Returns the value of node identified by `label` in `context`.
   Slightly faster than `(get (values context) label)`, as it only needs to translate label->id
   for the requested label."
  [{:keys [graph values]} label]
  (let [id (get (:labels graph) label)]
    (assert id (str "Node " label " does not exist on graph"))
    (get values id)))

(defn- ensure-graph-compilation
  [{:keys [processor compilations graph] :as ctx} input-labels-set]
  (if-let [existing-compilation (get compilations input-labels-set)]
    [ctx existing-compilation]
    (let [label-mapping (:labels graph)
          input-ids-set (into #{}
                              (map (fn [l]
                                     (let [id (get label-mapping l)]
                                       (assert id (str "Node with label " l " does not exist."))
                                       (assert (input-node? (get (:nodes graph) id))
                                               (str "Node with label " l " is not an input node"))
                                       id)))
                              input-labels-set)
          new-compilation (-compile processor graph input-ids-set)]
      [(update ctx :compilations assoc input-labels-set new-compilation) new-compilation])))

(defn precompile
  "Creates a compilation for the given `inputs-labels`, and stores the result into `context` to
   be used when processing the graph with the same `input-labels` in future calls to `process`."
  [context input-labels]
  (first (ensure-graph-compilation context (set input-labels))))

(defn- translate-labelled-inputs
  [labels-mapping labelled-inputs]
  (map-keys (fn [k]
              (let [id (get labels-mapping k)]
                (assert id (str "Input " k " does not exist on graph"))
                id))
            labelled-inputs))

(defn process
  "Processes the given `context` using `labelled-inputs` as {<input-node-label> <input-value>}.
   If a compilation didn't exist for the current set of inputs, it will create one and store it in `context`.
   Returns updated `context`."
  [{:keys [processor graph values] :as context} labelled-inputs]
  (let [[context compilation] (ensure-graph-compilation context (set (keys labelled-inputs)))
        input-map (translate-labelled-inputs (:labels graph) labelled-inputs)
        new-values (-process processor graph compilation values input-map)]
    (assoc context :values new-values)))

(defn -process-node
  "Processor impl: processes a single node on a graph with given accumulated values and inputs from processor process.
   If node throws exception, wraps the exception in ex-info containing `paths`."
  [graph node values inputs]
  (try (-compute node
                 (get values (-id node))
                 (-sources-values node values inputs))
       (catch Exception e 
         (throw (ex-info "Exception computing node"
                         {:paths (labelled-paths graph (-id node))}
                         e)))))

(defn sequential-processor
  "Returns a sequential processor that processes nodes sequentially."
  []
  (reify Processor
    (-compile [_ graph input-ids] (-base-compilation graph input-ids false))
    (-process [_ {:keys [nodes] :as g} compilation values inputs]
      (loop [-values (transient values)
             remaining (queue compilation)]
        (if (empty? remaining)
          (persistent! -values)
          (let [node-id (peek remaining)
                node (get nodes node-id)
                node-value (-process-node g node -values inputs)]
            (recur (assoc! -values node-id node-value)
                   (pop remaining))))))))
#?(:clj
   (defn parallel-processor
     "Only CLJ: Returns a parallel processor that will execute each parallel step of the topological sort using pmap."
     []
     (reify Processor
       (-compile [_ graph input-ids] (-base-compilation graph input-ids true))
       (-process [_ {:keys [nodes] :as g} compilation values inputs]
         (loop [-values values
                remaining (queue compilation)]
           (if-not (seq remaining)
             -values
             (let [node-ids (peek remaining)
                   node-values (into {} (pmap
                                         (fn [id]
                                           (let [node (get nodes id)
                                                 node-value (-process-node g node -values inputs)]
                                             [id node-value]))
                                         node-ids))]
               (recur (merge -values node-values)
                      (pop remaining)))))))))

(defn context
  "Returns a context to execute the given `graph` with `processor`.
   If `processor` is not specified, it will use a `sequential-processor`."
  ([graph]
   (context graph (sequential-processor)))
  ([graph processor]
   (->Context graph {} {} processor)))
