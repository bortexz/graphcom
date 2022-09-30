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
  "Node that computes a moving average of timseries node `source` with given `period`, only for the timestamps
   specified in `input`."
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
            ; We label the needed nodes on the graph, to use them to introduce new values or retrieve their value from
            ; the context.
            (g/graph {:input input
                      :avg moving-avg}))]
  (-> ctx
      (g/process {:input (sorted-map 1 1 2 2)})
      (g/process {:input (sorted-map 3 3 4 4)})
      (g/values))) ; => {:input nil, :avg {3 2, 4 3}} ; Inputs do not store their value.
