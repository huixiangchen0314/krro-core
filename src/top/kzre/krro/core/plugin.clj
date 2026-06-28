(ns top.kzre.krro.core.plugin
  "插件注册分发中心。插件用向量存储，通过多方法 :type 分派初始化。
   错误信息通过 message 系统输出，不再使用 println。"
  (:require [top.kzre.krro.core.message :as msg]))

(defonce plugin-registry (atom []))

(defmulti apply-plugin!
          "根据插件的 :type 进行类型特定的初始化。默认调用 :init 函数。"
          (fn [plugin] (:type plugin)))

(defmethod apply-plugin! :default [plugin]
  (try
    (when-let [init-fn (:init plugin)]
      (init-fn))
    (catch Exception e
      (msg/error (str "Failed to initialize plugin: " (:name plugin) " - " (.getMessage e))))))

(defn register-plugin!
  "注册一个插件：先执行 apply-plugin! 进行类型初始化，再添加到全局向量。
   返回插件的 :name，若注册过程出现严重异常则返回 nil。"
  [plugin]
  (try
    (apply-plugin! plugin)
    (swap! plugin-registry conj plugin)
    (:name plugin)
    (catch Exception e
      (msg/error (str "Failed to register plugin: " (:name plugin) " - " (.getMessage e))))))

(defn unregister-plugin
  "从向量中移除所有 :name 等于 plugin-name 的插件。"
  [plugin-name]
  (swap! plugin-registry
         (fn [v] (vec (remove #(= (:name %) plugin-name) v)))))

(defn registered-plugins []
  @plugin-registry)

(defn define-plugin*
  "高阶函数：注册插件类型行为。handler 接收插件 map。
   若 handler 抛出异常，错误会通过 message 系统输出。"
  [type handler]
  (defmethod apply-plugin! type [p]
    (try
      (handler p)
      (catch Exception e
        (msg/error (str "Plugin handler error for type " type ": " (.getMessage e)))))))

(defmacro define-plugin
  "定义插件类型的行为，自动解包插件属性。
   用法：(define-plugin :krro.plugin/resource-loader [resource-type loader]
           (resource/register-resource-loader! resource-type loader))"
  [type bindings & body]
  (let [keys (mapv (fn [sym] (keyword (name sym))) bindings)]
    `(define-plugin* ~type
                     (fn [plugin#]
                       (let [~bindings (mapv #(get plugin# %) ~keys)]
                         ~@body)))))