(defproject cachana "0.1.0-SNAPSHOT"
  :description "Cachana makes it easy to memoize function calls in clojure to a thread-local or network based cache (memcache)."
  :repositories {"spy" "http://files.couchbase.com/maven2/"}
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [spy/spymemcached "2.7.3"]
                 [clj-json "0.3.2"]]
  :main cachana.core)

