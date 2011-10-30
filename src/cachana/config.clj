(ns cachana.config)

(def options (atom {:bucket-separator "___/___"
                    :serialization [pr-str read-string]}))