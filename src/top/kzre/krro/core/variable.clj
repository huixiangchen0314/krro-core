(ns top.kzre.krro.core.variable)

;; 全局 DEBUG 模式,线程独立
(def ^:dynamic *debug* true)

;; 全局命令禁用
(defonce command-enabled (atom true))