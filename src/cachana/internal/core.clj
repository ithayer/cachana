(ns cachana.internal.core
  (:require [clojure.string :as str])
  (:import [net.spy.memcached
            MemcachedClient
            ConnectionFactoryBuilder
            ConnectionFactoryBuilder$Protocol
            AddrUtil]
           [net.spy.memcached.auth
            AuthDescriptor
            PlainCallbackHandler]
           [java.net InetSocketAddress])
  (:use [cachana.config :only [options]]))

(def client (atom nil))
(def stats (atom {}))

(def ^{:dynamic true} *thread-local-cache* nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Util
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ->string [[bucket key]]
  (str (name bucket) (get @options :bucket-separator) key))

(defn inc-stat [ks & [val]]
  (when-not (and (= :local (last ks))
                 (not *thread-local-cache*))
    (swap! stats update-in ks (fn [x] (inc (or x 0)))))
  val)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Network cache
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-factory
  "Use the PLAIN auth descriptor and build the connection factory."
  [user password]
  (let [auth (AuthDescriptor. (into-array ["PLAIN"])
                              (PlainCallbackHandler. user password))]
    (.. (ConnectionFactoryBuilder.)
        (setProtocol ConnectionFactoryBuilder$Protocol/BINARY)
        (setAuthDescriptor auth)
        build)))

(defn initialize-network!
  [{:keys [host port user password] :as options}]
  (let [host (or host "localhost")
        port (if (not (nil? port)) (Integer. port) 11211)]
    (reset! client (if (and (nil? user) (nil? password))
                     (MemcachedClient. (list (InetSocketAddress. host port)))
                     (MemcachedClient.
                      (create-factory user password)
                      (AddrUtil/getAddresses (str host ":" port)))))))

(defn network-set! [key value {:keys [expiration] :or {expiration 900}}]
  (.. @client (set (name key) expiration value)))

(defn network-get [key & {:keys [default] :or {default nil}}]
  (if-let [res (.. @client (get (name key)))] res default))

(defn network-delete [key]
  (.. @client (delete key)))

(defn test-network [n]
  (let [write-start (System/currentTimeMillis)]
    (dotimes [i n]
      (network-set! (str "benchmark-" i) i {:expiration 60}))
    (println n "writes took" (- (System/currentTimeMillis) write-start) "ms")
    (let [read-start (System/currentTimeMillis)]
      (dotimes [i n]
        (network-get (str "benchmark-" i)))
      (println n "reads took" (- (System/currentTimeMillis) read-start) "ms"))))

(defmacro try-network [location & on-miss]
  `(let [location-string# (->string ~location)
         [pack-fn# unpack-fn#] (get ~(deref options) :serialization)]
     (if-let [cache-result# (network-get location-string#)]
       (inc-stat (cons (first ~location) [:hit :network]) cache-result#)
       (let [result# (inc-stat (cons (first ~location) [:miss :network]) (do ~@on-miss))]
         (network-set! location-string# result# {})
         result#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Local cache
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn local-set! [location val]
  (when *thread-local-cache*
    (swap! *thread-local-cache* assoc-in [location] val))
  val)

(defmacro try-local [location & on-miss]
  `(let [location-string# (->string ~location)]
     (if *thread-local-cache*
       (if-let [result# (get @*thread-local-cache* location-string#)]
         (inc-stat (cons (first ~location) [:hit :local]) result#)
         (local-set! location-string#
                     (inc-stat (cons (first ~location) [:miss :local]) (do ~@on-miss))))
       (do (println (str "cachana: No thread local cache in lookup for: " ~location))
           ~@on-miss))))

