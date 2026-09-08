(ns top.kzre.krro.core.plugin
  "插件注册分发中心。插件用 map 存储，键为 :id，通过多方法 :type 分派初始化。
   错误信息通过 message 系统输出。"
  (:require [top.kzre.krro.core.message :as msg]))

(defonce plugin-registry (atom {}))

(defmulti mount-plugin!
          "根据插件的 :type 进行类型特定的挂载插件。默认调用 :init 函数。"
          (fn [plugin] (:type plugin)))

(defmulti unmount-plugin!
          "根据插件的 :type 进行类型特定的挂载插件。默认调用 :init 函数。"
          (fn [plugin] (:type plugin)))

(defmethod mount-plugin! :default [plugin]
  (try
    (when-let [mount-fn (or (:mount plugin) (:init plugin))]
      (mount-fn))
    (catch Exception e
      (msg/error (str "Failed to initialize plugin: " (:id plugin) " - " (.getMessage e))))))

(defmethod unmount-plugin! :default [plugin]
  (try
    (when-let [unmount-fn (:unmount plugin)]
      (unmount-fn))
    (catch Exception e
      (msg/error (str "Failed to initialize plugin: " (:id plugin) " - " (.getMessage e))))))


(defn get-plugin [plugin-id]
  (get @plugin-registry plugin-id))

(defn enable-plugin! [plugin-id]
  (if-let [plug (get-plugin plugin-id)]
    (do (mount-plugin! plug)
        (swap! plugin-registry assoc-in [plugin-id :enabled] true))
    (msg/warn (str "unknown plugin: " plugin-id))))

(defn disable-plugin! [plugin-id]
  (if-let [plug (get-plugin plugin-id)]
    (do (unmount-plugin! plug)
        (swap! plugin-registry assoc-in [plugin-id :enabled] false))
    (msg/warn (str "unknown plugin: " plugin-id))))


(defn reg-plugin
  "注册一个插件：先执行 apply-plugin! 进行类型初始化，再以 :id 为键存入全局 map。
   若注册过程出现严重异常则返回 nil。"
  [plugin]
  (try
    (when-let [pid (:id plugin)]
      (swap! plugin-registry assoc pid plugin)
      (:id plugin))
    (catch Exception e
      (msg/error (str "Failed to register plugin: " (:id plugin) " - " (.getMessage e))))))

(defn reg-plugin!
  "注册插件并立即应用."
  [plugin]
  (try
    (mount-plugin! plugin)
    (swap! plugin-registry assoc (:id plugin) plugin)
    (:id plugin)
    (catch Exception e
      (msg/error (str "Failed to register plugin: " (:id plugin) " - " (.getMessage e))))))

(defn plugin-enabled? [plugin-id]
  (when-let [p (get-plugin plugin-id)]
    (:enabled p)))

(defn plugin-unmountable?
  "插件是否可卸载"
  [plugin-id]
  (when-let [p (get-plugin plugin-id)]
    (fn? (nil? (:unmount p)))))

(defn unreg-plugin
  "从 map 中移除指定 :id 的插件。"
  [plugin-id]
  (when (plugin-enabled? plugin-id)
    (disable-plugin! plugin-id))
  (swap! plugin-registry dissoc plugin-id))

(def unregister-plugin unreg-plugin)
(def ^:deprecated register-plugin! reg-plugin!)

(defn all-plugins []
  (vals @plugin-registry))

(defn all-enabled-plugins []
  (filter #(plugin-enabled? (:id %)) (all-plugins)))

(defn define-plugin*
  "高阶函数：注册插件类型行为。handler 接收插件 map。"
  [type handler]
  (defmethod mount-plugin! type [p]
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
