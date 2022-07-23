(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b] ; for b/git-count-revs
            [clojure.edn :as edn]
            [org.corfield.build :as bb]))

(def release (edn/read-string (slurp "release.edn")))

(def lib (symbol (:group-id release) (:artifact-id release)))
(def version (:version release))

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/run-tests)
      (bb/clean)
      (assoc :src-pom "template/pom.xml")
      (bb/jar)))

(defn install "Install the JAR locally." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/install)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy)))

(defn tag-version
  "Creates a new git lightweight tag with the current version."
  [_]
  (b/git-process {:git-args ["tag" (str "v" version)]}))
