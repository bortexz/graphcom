(ns bortexz.graphcom-test
  (:require [clojure.test :refer [deftest is testing]]
            [bortexz.graphcom :as g]))

(deftest base-test
  (let [input (g/input-node)
        sum-node (g/compute-node {:source input}
                                 (fn [val {:keys [source]}]
                                   (+ (or val 0) source)))
        graph (g/graph {:input input
                        :sum sum-node})
        context (g/context graph)]
    (testing "Correct value after processing graph context"
      (is (= 10 (g/value (g/process context {:input 10}) :sum))))

    (testing "Correctly inputs current value on handler"
      (is (= 20 (-> context
                    (g/process {:input 10})
                    (g/process {:input 10})
                    (g/value :sum)))))

    (testing "Precompile works"
      (is (= (:compilations (g/precompile context #{:input}))
             (:compilations (g/process context {:input 10})))))))

(defn relay-node
  [source]
  (g/compute-node {:source source} (fn [_ {:keys [source]}] source)))

(deftest hidden-nodes
  (let [input (g/input-node)
        hidden-node (relay-node input)
        final-node (relay-node hidden-node)
        graph (g/graph {:input input
                        :node final-node})
        context (g/context graph)]
    (testing "Doesn't ignore hidden nodes"
      (is (= 10 (g/value (g/process context {:input 10}) :node))))))

(defn test-processor
  [processor]
  (let [input1 (g/input-node)
        input2 (g/input-node)
        relay1 (relay-node input1)
        relay2 (relay-node input2)
        sum-node (g/compute-node {:input1 relay1
                                  :input2 relay2}
                                 (fn [_ {:keys [input1 input2]}] (+ input1 input2)))
        graph (g/graph {:input1 input1
                        :input2 input2
                        :sum sum-node})
        context (g/context graph processor)]
    (testing "Works fine on all inputs being passed"
      (is (= 20 (g/value (g/process context {:input1 10 :input2 10}) :sum))))

    (testing "Inputs that are not specified do not propagate nil value, and intermediary compute node keeps old value"
      (is (= 30 (-> context
                    (g/process {:input1 10 :input2 10})
                    (g/process {:input1 20})
                    (g/value :sum)))))

    (testing "Input values are ephemeral and cannot be retrieved with value or values"
      (let [processed (-> context
                          (g/process {:input1 10 :input2 10}))]
        (is (nil? (g/value processed :input1)))
        (is (nil? (:input1 (g/values processed))))))

    (testing "Exception paths is correct"
      (let [i (g/input-node)
            c (g/compute-node {:input i} (fn [_ _] (throw (Exception. ""))))
            c1 (g/compute-node {:source c} (fn [_ _]))
            c2 (g/compute-node {:source c} (fn [_ _]))
            c3 (g/compute-node {:c1 c1 :c2 c2} (fn [_ _]))
            ctx (g/context (g/graph {:input i :compute c3}))
            ex-paths (try (g/process ctx {:input true})
                          (catch Exception e
                            (:paths (ex-data e))))]
        (is (true? (contains? ex-paths [:compute :c1 :source])))
        (is (true? (contains? ex-paths [:compute :c2 :source])))))))

(deftest processor
  (testing "Sequential processor"
    (test-processor (g/sequential-processor))
    #?(:clj (test-processor (g/parallel-processor)))))
