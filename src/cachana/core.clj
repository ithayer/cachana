(ns cachana.core
  (:use [cachana.internal.core]))

(defn initialize
  "Initializes the network connection and optionally reads and writes to it."
  [& [options test-n]]
  (initialize-network! (or options {}))
  (when test-n
    (test-network test-n)))

(defmacro with-local-cache
  "Runs the body with a local cache initialized, allowing body to memoize locally."
  [& body]
  `(binding [cachana.internal.core/*thread-local-cache* (atom {})]
     ~@body))

(defn get-stats
  "Return hit/miss stats."
  []
  @stats)

(defn memoize
  "Memoize a function, optionally to network cache."
  ([wrapped]
     (memoize {} wrapped))
  ([call-options wrapped]
     (fn [& args]
       (let [location (or (:location call-options) [(str wrapped) args])]
         (try-local location
                    (if (= :network (:mode call-options))
                      (try-network location (apply wrapped args))
                      (apply wrapped args)))))))
