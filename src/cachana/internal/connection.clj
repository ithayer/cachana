(ns cachana.internal.connection
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

;; TODO: rename
(defn cache-set! [key value {:keys [expiration] :or {expiration 900}}]
  (.. @client (set (name key) expiration value)))

(defn cache-get [key & {:keys [default] :or {default nil}}]
  (if-let [res (.. @client (get (name key)))] res default))

(defn cache-delete [key]
  (.. @client (delete key)))

(defn test-network [n]
  (let [write-start (System/currentTimeMillis)]
    (dotimes [i n]
      (cache-set! (str "benchmark-" i) i {:expiration 60}))
    (println n "writes took" (- (System/currentTimeMillis) write-start) "ms")
    (let [read-start (System/currentTimeMillis)]
      (dotimes [i n]
        (cache-get (str "benchmark-" i)))
      (println n "reads took" (- (System/currentTimeMillis) read-start) "ms"))))

(defn inc-stat [ks & [val]]
  (when-not (and (= :local (last ks))
                 (not *thread-local-cache*))
    (swap! stats update-in ks (fn [x] (inc (or x 0)))))
  val)

(defn set-local! [location val]
  (when *thread-local-cache*
    (swap! *thread-local-cache* assoc-in [location] val))
  val)

(defmacro with-local-cache [& body]
  `(binding [*thread-local-cache* (atom {})]
     ~@body))

(defn ->string [[bucket key]]
  (str (name bucket) (get @options :bucket-separator) key))

(defmacro try-local [location & on-miss]
  `(let [location-string# (->string ~location)]
     (if *thread-local-cache*
       (if-let [result# (get @*thread-local-cache* location-string#)]
         (inc-stat (cons (first ~location) [:hit :local]) result#)
         (set-local! location-string#
                     (inc-stat (cons (first ~location) [:miss :local]) (do ~@on-miss))))
       (do ~@on-miss))))

(defmacro try-network [location & on-miss]
  `(let [location-string# (->string ~location)
         [pack-fn# unpack-fn#] (get ~(deref options) :serialization)]
     (if-let [cache-result# (cache-get location-string#)]
       (inc-stat (cons (first ~location) [:hit :network]) cache-result#)
       (let [result# (inc-stat (cons (first ~location) [:miss :network]) (do ~@on-miss))]
         (cache-set! location-string# result#)
         result#))))

(defn memoize
  ([wrapped]
     (memoize {} wrapped))
  ([call-options wrapped]
       (fn [& args]
         (let [location (or (:location call-options) [(str wrapped) args])]
           (try-local location (if (= :network (:mode call-options))
                                 (try-network location (apply wrapped args))
                                 (apply wrapped args))))))))

(defn my-inc [x]
  (println "work done on: " x)
  (inc x))

((memoize {} my-inc) 2)

((memoize {:mode :network} my-inc) 2)
((memoize {:mode :network} my-inc) 2) ;; cached

