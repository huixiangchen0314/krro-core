(ns top.kzre.krro.core.plugin
  "插件注册分发中心。插件用 map 存储，键为 :id，通过多方法 :type 分派初始化。
   错误信息通过 message 系统输出。"
  (:require [top.kzre.krro.core.message :as msg]))

(defonce plugin-registry (atom {}))

(defmulti apply-plugin!
          "根据插件的 :type 进行类型特定的初始化。默认调用 :init 函数。"
          (fn [plugin] (:type plugin)))

(defmethod apply-plugin! :default [plugin]
  (try
    (when-let [init-fn (:init plugin)]
      (init-fn))
    (catch Exception e
      (msg/error (str "Failed to initialize plugin: " (:id plugin) " - " (.getMessage e))))))

(defn register-plugin!
  "注册一个插件：先执行 apply-plugin! 进行类型初始化，再以 :id 为键存入全局 map。
   若注册过程出现严重异常则返回 nil。"
  [plugin]
  (try
    (apply-plugin! plugin)
    (swap! plugin-registry assoc (:id plugin) plugin)
    (:id plugin)
    (catch Exception e
      (msg/error (str "Failed to register plugin: " (:id plugin) " - " (.getMessage e))))))

(defn unregister-plugin
  "从 map 中移除指定 :id 的插件。"
  [plugin-id]
  (swap! plugin-registry dissoc plugin-id))

(defn registered-plugins []
  (vals @plugin-registry))

(defn define-plugin*
  "高阶函数：注册插件类型行为。handler 接收插件 map。"
  [type handler]
  (defmethod apply-plugin! type [p]
    (try
      (handler p)
      (catch Exception e
        (msg/error (str "Plugin handler error for type " type ": " (.getMessage e)))))))

(defmacro defplugin
  "定义插件类型的行为，自动解包插件属性。"
  [type bindings & body]
  (let [keys (mapv (fn [sym] (keyword (name sym))) bindings)]
    `(define-plugin* ~type
                     (fn [plugin#]
                       (let [~bindings (mapv #(get plugin# %) ~keys)]
                         ~@body)))))
