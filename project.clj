(defproject cachana "1.0.0-SNAPSHOT"
  :description "Cachana makes it easy to memoize function calls in clojure to memcache."
  :repositories {"spy" "http://files.couchbase.com/maven2/"}
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [spy/spymemcached "2.7.3"]]
  :main cachana.core)
