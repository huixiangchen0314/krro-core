(ns top.kzre.krro.core.custom
  "用户配置系统。配置存储在全局 map 中，通过 keyword ID 访问。
   配置变更时触发 <id>-changed-hook，不再使用宏生成 Var。
   局部变量栈支持模式作用域。"
  (:require [top.kzre.krro.core.hook :as hook]))

(defonce custom-registry (atom {}))

;; ── 内部工具 ──────────────────────────────────────
(defn- changed-hook-key [id]
  (keyword (str (name id) "-changed-hook")))

;; ── 创建配置项 ────────────────────────────────────
(defn defcustom
  "在注册表中创建一个配置项，返回其 ID。
   id        : 全局唯一关键字，如 :krro.painting/default-brush
   default   : 默认值
   :type     : 可选类型关键字 (:integer, :boolean, :string, :keyword)，用于验证
   :doc      : 可选文档字符串"
  [id default & {:keys [type doc] :as _opts}]
  (let [value-atom (atom default)
        entry {:id      id
               :type    type
               :doc     doc
               :default default
               :value   value-atom}]
    (swap! custom-registry assoc id entry)
    ;; 添加 watcher，当值变更时触发 hook
    (add-watch value-atom :custom
               (fn [_ _ old new]
                 (hook/run-hook (changed-hook-key id) old new)))
    id))

;; ── 读写操作 ──────────────────────────────────────
(defn get-custom
  "获取配置项当前值，若不存在则返回 nil。"
  [id]
  (when-let [entry (get @custom-registry id)]
    @(:value entry)))

(declare valid-type?)

(defn set-custom!
  "设置配置项的值，自动进行类型验证并触发变更 hook。"
  [id new-value]
  (when-let [entry (get @custom-registry id)]
    (when-let [type (:type entry)]
      (when-not (valid-type? type new-value)
        (throw (ex-info (str "Invalid value type for " id)
                        {:id id :expected type :actual new-value}))))
    (reset! (:value entry) new-value)))

(defn all-customs
  "返回当前所有配置项的 map，key 为 ID，value 为当前值。"
  []
  (reduce-kv (fn [m id entry]
               (assoc m id @(:value entry)))
             {} @custom-registry))

;; ── 类型验证多方法 ────────────────────────────────
(defmulti valid-type? (fn [type value] type))
(defmethod valid-type? :integer [_ v] (integer? v))
(defmethod valid-type? :boolean [_ v] (boolean? v))
(defmethod valid-type? :string [_ v] (string? v))
(defmethod valid-type? :keyword [_ v] (keyword? v))
(defmethod valid-type? :default [_ _] true)

;; ── 局部变量栈（保留，供模式系统使用）─────────────
(defn push-local-value!
  "为指定配置项推入一个临时值，原值保存到局部栈中。"
  [id local-value]
  (when-let [entry (get @custom-registry id)]
    (let [current @(:value entry)
          stack  (or (:local-stack (meta entry)) [])]
      (alter-meta! entry assoc :local-stack (conj stack current))
      (reset! (:value entry) local-value))))

(defn pop-local-value!
  "弹出指定配置项的局部值，恢复上一个值或默认值。"
  [id]
  (when-let [entry (get @custom-registry id)]
    (let [stack (:local-stack (meta entry))]
      (when (seq stack)
        (let [new-stack (pop stack)
              restored  (peek stack)] ; 栈顶是上一个值
          (alter-meta! entry assoc :local-stack new-stack)
          (if restored
            (reset! (:value entry) restored)
            ;; 栈已空，恢复默认值
            (reset! (:value entry) (:default entry))))))))