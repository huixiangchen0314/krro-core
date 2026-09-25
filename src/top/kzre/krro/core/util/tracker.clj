(ns top.kzre.krro.core.util.tracker
  "Java Tracker 的 Clojure 包装。

   语义与 Java 版一致：
     - track!  : 登记资源；已关闭则立即释放，返回资源本身
     - close!  : 标记关闭并释放所有已登记资源，幂等
     - closed? : 查询当前是否已进入关闭态

   前提：release-fn 幂等、可重入。"
  (:import (top.kzre.krro.core.util Tracker)
           (java.util.function Consumer)))

(defn auto-closeable-tracker
  "创建释放动作为 AutoCloseable.close() 的追踪器。
  释放抛出的异常被吞掉，不掩盖调用方原始异常。
  严格接口契约——资源必须实现 AutoCloseable。"
  []
  (Tracker/autoCloseableTracker))

(defn tracker
  "创建资源追踪器。
   release-fn 接收单个资源执行释放，要求幂等、可重入。"
  [release-fn]
  (when-not (fn? release-fn)
    (throw (IllegalArgumentException. "release-fn must be a function")))
  (Tracker. (reify Consumer
              (accept [_ r] (release-fn r)))))

(defn closeable-tracker
  "创建释放动作为 (.close resource) 的追踪器。
  宽松契约——资源只需有 close 方法，不要求实现 AutoCloseable。
  释放抛出的异常被吞掉，不掩盖调用方原始异常。"
  []
  (tracker
    (fn [resource]
      (try
        (when resource
          (.close resource))
        (catch Throwable _
          )))))
(defn track!
  "登记资源。已关闭则立即释放。返回 resource 本身；nil 不做任何事。"
  [^Tracker t resource]
  (.track t resource))

(defn close!
  "标记关闭并释放所有已登记资源。幂等。"
  [^Tracker t]
  (.close t))

(defn closed?
  "当前是否已进入关闭态。"
  [^Tracker t]
  (.isClosed t))