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