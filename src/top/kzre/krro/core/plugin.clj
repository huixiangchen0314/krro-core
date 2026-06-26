(ns top.kzre.krro.core.plugin
  "插件注册分发中心。内核仅提供按 :type 分派的多方法。")

(defonce plugin-registry (atom {}))

(defmulti register-plugin!
          "注册一个插件，根据 :type 分派到对应方法。"
          (fn [plugin] (:type plugin)))

(defmethod register-plugin! :default [plugin]
  (println "Unknown plugin type:" (:type plugin)))

(defn unregister-plugin [plugin-id]
  (swap! plugin-registry dissoc plugin-id))

(defn registered-plugins [] (set (keys @plugin-registry)))