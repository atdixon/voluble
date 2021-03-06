(ns io.mdrogalis.voluble.interop
  (:require [clojure.java.io :as io]
            [io.mdrogalis.voluble.core :as c])
  (:import [java.util HashMap]
           [java.util ArrayList]
           [java.util Properties]
           [org.apache.kafka.connect.source SourceRecord]
           [org.apache.kafka.connect.data SchemaBuilder]
           [org.apache.kafka.connect.data Schema]
           [org.apache.kafka.connect.data Struct]
           [org.apache.kafka.connect.data Field]))

(defn pom-version []
  (-> (doto (Properties.)
        (.load (-> "META-INF/maven/io.mdrogalis/voluble/pom.properties"
                   (io/resource)
                   (io/reader))))
      (.get "version")))

(defn make-context [props]
  (atom (c/make-context (into {} props))))

(defn primitive-schema [t]
  (get
   {nil Schema/OPTIONAL_BYTES_SCHEMA
    java.lang.Integer Schema/OPTIONAL_INT32_SCHEMA
    java.lang.Long Schema/OPTIONAL_INT64_SCHEMA
    java.lang.Float Schema/OPTIONAL_FLOAT32_SCHEMA
    java.lang.Double Schema/OPTIONAL_FLOAT64_SCHEMA
    java.lang.String Schema/OPTIONAL_STRING_SCHEMA
    java.lang.Boolean Schema/OPTIONAL_BOOLEAN_SCHEMA}
   (type t)))

(defn build-schema [x depth]
  ;; `depth` evades nested namespace collisions when
  ;; Connect's format converts to Avro.
  (cond (map? x)
        (let [builder (.optional (.name (SchemaBuilder/struct) (str "io.mdrogalis.Gen" depth)))]
          (.build
           ^SchemaBuilder
           (reduce-kv
            (fn [^SchemaBuilder b k v]
              (.field b k (build-schema v (inc depth))))
            builder
            x)))

        (not (nil? x))
        (primitive-schema x)))

(defn build-converted-obj [x ^Schema schema]
  (if (map? x)
    (let [s (Struct. schema)]
      (doseq [[^String k v] x]
        (let [field ^Field (.field schema k)
              inner-schema ^Schema (.schema field)]
          (.put s k (build-converted-obj v inner-schema))))
      s)
    x))

(defn generate-source-record [state]
  (swap! state c/advance-until-success)
  (let [context @state
        generated (:generated context)
        status (:status generated)]
    (cond (= status :success)
          (let [records (ArrayList.)
                topic (get-in generated [:topic])
                k (get-in generated [:event :key])
                k-schema (build-schema k 0)
                k-obj (build-converted-obj k k-schema)
                v (get-in generated [:event :value])
                v-schema (build-schema v 0)
                v-obj (build-converted-obj v v-schema)
                record (SourceRecord. (HashMap.) (HashMap.) topic nil k-schema k-obj v-schema v-obj)]
            (.add records record)
            records)

          (= status :drained)
          (ArrayList.)

          :else
          (throw (ex-info "State machine returned an unusable status." {:status status})))))
