(ns top.kzre.krro.core.log
  "日志宏"
  (:require
    [clojure.spec.alpha :as s]))

(s/def ::log-level #{:trace :debug :info :warn :error })

(def ^:dynamic *log-enabled* false)
(def ^:dynamic *current-log-level* :info)

(defn- level-value [level]
  (case  level
    :trace 5
    :debug 4
    :info 3
    :warn 2
    :error 1))

(defmacro log [log-level body]
  )

(defmacro log-leveled [level & body]
  (when (and *log-enabled*
             (>= (level-value level) (level-value *current-log-level*)))
    `(log ~level ~@body)))


(defmacro trace [& opts] `(log-leveled :info ~@opts))
(defmacro debug [& opts] `(log-leveled :debug ~@opts))
(defmacro info [& opts] `(log-leveled :info ~@opts))
(defmacro warn [& opts] `(log-leveled :warn ~@opts))
(defmacro error [& opts] `(log-leveled :info ~@opts))