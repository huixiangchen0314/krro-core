(ns top.kzre.krro.core.plugin
  "插件注册分发中心。内核仅提供按 :type 分派的多方法。
   默认行为：若插件有 :init 函数，则调用它进行初始化。")

(defonce plugin-registry (atom {}))

(defn- call-init [plugin]
  (when-let [init-fn (:init plugin)]
    (init-fn)))

(defmulti register-plugin!
          "注册一个插件，根据 :type 分派到对应方法。
           默认实现会调用插件的 :init 函数（如果存在）。"
          (fn [plugin] (:type plugin)))

(defmethod register-plugin! :default [plugin]
  (println "Registering plugin:" (:id plugin) "type:" (:type plugin))
  (call-init plugin)
  (swap! plugin-registry assoc (:id plugin) plugin)
  (:id plugin))

(defn unregister-plugin [plugin-id]
  (swap! plugin-registry dissoc plugin-id))

(defn registered-plugins [] (set (keys @plugin-registry)))