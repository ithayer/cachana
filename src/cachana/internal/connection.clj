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
           [java.net InetSocketAddress]))

(def mcd (atom nil))

(defn- key->str [key]
  (if (keyword? key) (name key) key))

(defn- make-connection-factory [user password]
  (let [ad (AuthDescriptor. (into-array ["PLAIN"]) (PlainCallbackHandler. user password))]
    (.. (ConnectionFactoryBuilder.) (setProtocol ConnectionFactoryBuilder$Protocol/BINARY)
      (setAuthDescriptor ad) build)))

(defn memcached! [& {:keys [host port user password] :or {host "localhost", port 11211, user "", password ""}}]
  (reset! mcd (if (and (str/blank? user) (str/blank? password))
                (MemcachedClient. (list (InetSocketAddress. host port)))
                (MemcachedClient.
                  (make-connection-factory user password)
                  (AddrUtil/getAddresses (str host ":" port))))))

(defn cache-set [key value & {:keys [expiration] :or {expiration 3600}}]
  (.set @mcd (key->str key) expiration value))

(defn cache-get [key & {:keys [default] :or {default nil}}]
  (if-let [res (.get @mcd (key->str key))] res default))