(ns top.kzre.krro.core.plugin
  "插件注册分发中心。内核不定义任何插件协议，仅提供按 :type 分派的多方法。
   undo、vcs 等子系统通过 defmethod 扩展注册逻辑。")

(defonce plugin-registry (atom {}))

(defmulti register-plugin!
          "注册一个插件。根据插件的 :type 关键字分派到对应方法。
           每个方法负责：初始化数据、注册命令/模式/键图、调用子系统初始化等。
           返回插件 id。"
          (fn [plugin] (:type plugin)))

(defmethod register-plugin! :default [plugin]
  (println "Unknown plugin type:" (:type plugin)))

(defn unregister-plugin [plugin-id]
  (swap! plugin-registry dissoc plugin-id))

(defn registered-plugins [] (keys @plugin-registry))