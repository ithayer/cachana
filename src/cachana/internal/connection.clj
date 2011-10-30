(ns cachana.internal.connection
  (:require [clojure.contrib.accumulators :as acc]
            [clojure.string :as str])
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

(defn- initialize-client!
  [& {:keys [host port user password] :as options}]
  (let [host (or host "localhost")
        port (if (not (nil? port)) (Integer. port) 11211)]
    (reset! client (if (and (nil? user) (nil? password))
                     (MemcachedClient. (list (InetSocketAddress. host port)))
                     (MemcachedClient.
                      (create-factory user password)
                      (AddrUtil/getAddresses (str host ":" port)))))))

(defn cache-set! [key value & {:keys [expiration] :or {expiration 3600}}]
  (.set @client (name key) expiration value))

(defn cache-get [key & {:keys [default] :or {default nil}}]
  (if-let [res (.. @client (get (name key)))] res default))

(defn inc-stat [& ks]
  (swap! stats update-in ks (fn [x] (inc (or x 0)))))

(defn try-local [joined-key]
  (when *thread-local-cache*
    (get @*thread-local-cache* joined-key)))

(defn set-local! [joined-key val]
  (when *thread-local-cache*
    (swap! *thread-local-cache* assoc-in [joined-key] val))
  val)

(defmacro with-local-cache [& body]
  `(binding [*thread-local-cache* (atom {})]
     ~@body))

(defmacro with-caching* [call-options [bucket key] body]
  (let [[pack-fn unpack-fn] (get @options :serialization)]
    `(let [key# (str ~(name bucket) ~(get @options :bucket-separator) ~key)
           in-local-cache?# (~try-local key#)]
       (if in-local-cache?#
         (do (~inc-stat ~bucket :hit :local)
             in-local-cache?#)
         (do (and *thread-local-cache* (~inc-stat ~bucket :miss :local))
             (if-let [in-network-cache?# (and (not (:local-only ~call-options))
                                              (~cache-get key#))]
               (do (~inc-stat ~bucket :hit :network)
                   (set-local! key# (~unpack-fn in-network-cache?#)))
               (do (and (not (:local-only ~call-options))
                        (~inc-stat ~bucket :miss :network))
                   (let [result# ~@body]
                     (and (not (:local-only ~call-options))
                          (~cache-set! key# (~pack-fn result#)))
                     (~set-local! key# result#)
                     result#))))))))

(defmacro with-caching
  "Accepts <options map, key sequence, body>, or <key sequence, body>."
  [selector & remaining]
  (if (map? selector)
    `(with-caching* ~selector ~(first remaining) ~(rest remaining))
    `(with-caching* {} ~selector ~remaining)))


(def n 10000000)

(with-caching [:bigrange n] (reduce + (range 0 n)))

(with-local-cache
  (with-caching {:local-only true} [:bigrange n] (reduce + (range 0 n)))
  (with-caching {:local-only true} [:bigrange n] (reduce + (range 0 n)))) ;; Local cache hit.

(with-local-cache
  (with-caching [:accounts n] (reduce + (range 0 n)))
  (with-caching [:accounts n] (reduce + (range 0 n))))
  
(with-caching [:accounts n] (reduce + (range 0 n)))

         