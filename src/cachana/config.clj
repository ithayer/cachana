(ns cachana.config)

(def options (atom {:bucket-separator "/"
                    :serialization [pr-str read-string]}))