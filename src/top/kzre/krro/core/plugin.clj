(ns top.kzre.krro.core.plugin
  "插件注册分发中心。插件用向量存储，通过多方法 :type 分派初始化。")

(defonce plugin-registry (atom []))

(defmulti apply-plugin!
          "根据插件的 :type 进行类型特定的初始化。默认调用 :init 函数。"
          (fn [plugin] (:type plugin)))

(defmethod apply-plugin! :default [plugin]
  (when-let [init-fn (:init plugin)]
    (init-fn)))

(defn register-plugin!
  "注册一个插件：先执行 apply-plugin! 进行类型初始化，再添加到全局向量。"
  [plugin]
  (apply-plugin! plugin)
  (swap! plugin-registry conj plugin)
  (:name plugin))

(defn unregister-plugin
  "从向量中移除所有 :name 等于 plugin-name 的插件。"
  [plugin-name]
  (swap! plugin-registry
         (fn [v] (vec (remove #(= (:name %) plugin-name) v)))))

(defn registered-plugins []
  @plugin-registry)


(defn define-plugin*
  "定义一个插件类型及其处理函数。
   handler 接收插件 map，可在此执行类型特定的初始化（如注册加载器）。
   若也需要执行 :init，handler 内部需自行调用 (when-let [f (:init p)] (f))。
   用法：(define-plugin-type :krro.plugin/resource-loader my-handler)"
  [type handler]
  (defmethod apply-plugin! type [p]
    (handler p)))

(defmacro define-plugin
  "定义插件类型的行为，自动解包插件属性。
   用法：(define-plugin :krro.plugin/resource-loader [resource-type loader]
           (resource/register-resource-loader! resource-type loader))"
  [type bindings & body]
  (let [;; 将绑定符号转换为关键字（如 resource-type → :resource-type）
        keys (mapv (fn [sym] (keyword (name sym))) bindings)]
    `(define-plugin* ~type
                     (fn [plugin#]
                       (let [~bindings (mapv #(get plugin# %) ~keys)]
                         ~@body)))))