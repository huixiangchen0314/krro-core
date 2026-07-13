(ns top.kzre.krro.core.custom
  "用户配置系统。全局默认值 + Frame‑local 绑定（通过 IFrame 专用字段）。
   支持分组、类型、文档等元数据。"
  (:require
    [top.kzre.krro.core.frame :as frame]
    [top.kzre.krro.core.hook :as hook]))

(defonce custom-registry (atom {}))

(defn- changed-hook-key [id]
  (keyword (str (name id) "-changed-hook")))

;; ── 定义配置项 ────────────────────────────
(defn defcustom
  [id default & {:keys [type group doc options]}]
  (let [value-atom (atom default)
        entry {:id      id
               :type    type
               :group   group
               :doc     doc
               :options options
               :default default
               :value   value-atom}]
    (swap! custom-registry assoc id entry)
    (add-watch value-atom :custom
               (fn [_ _ old new]
                 (hook/run-hook! (changed-hook-key id) old new)))
    id))

;; ── 类型检查 ──────────────────────────────
(defmulti valid-type? (fn [type _] type))
(defmethod valid-type? :integer [_ v] (integer? v))
(defmethod valid-type? :boolean [_ v] (boolean? v))
(defmethod valid-type? :string  [_ v] (string? v))
(defmethod valid-type? :keyword [_ v] (keyword? v))
(defmethod valid-type? :default [_ _] true)

(defn- check-type! [entry new-value]
  (when-let [type (:type entry)]
    (when-not (valid-type? type new-value)
      (throw (ex-info (str "Invalid value type for " (:id entry))
                      {:id (:id entry) :expected type :actual new-value})))))

;; ── 获取有效值（自动考虑 Frame‑local） ──
(defn get-custom
  ([id] (get-custom id frame/*current-frame*))
  ([id f]
   (when-let [entry (get @custom-registry id)]
     (if f
       (if-let [local (get (frame/local-customs f) id)]
         local
         @(:value entry))
       @(:value entry)))))

;; ── 全局值操作 ─────────────────────────────
(defn global-value [id]
  (when-let [entry (get @custom-registry id)]
    @(:value entry)))

(defn set-custom-global!
  "修改全局值，触发 changed-hook。"
  [id new-value]
  (when-let [entry (get @custom-registry id)]
    (check-type! entry new-value)
    (reset! (:value entry) new-value)))

;; ── Frame‑local 操作（不触发全局钩子）───
(defn set-custom-local!
  ([id value] (set-custom-local! id value frame/*current-frame*))
  ([id value f]
   (when (and f (get @custom-registry id))
     (check-type! (get @custom-registry id) value)
     (frame/set-local-custom! f id value))))

(defn kill-local-custom!
  ([id] (kill-local-custom! id frame/*current-frame*))
  ([id f]
   (when f
     (frame/remove-local-custom! f id))))

(defn local-bound? [id f]
  (contains? (frame/local-customs f) id))

;; ── 分组、文档、状态查询 ────────────────
(defn custom-group [group-id]
  (->> @custom-registry
       vals
       (filter #(= group-id (:group %)))
       (map (fn [e] {:id (:id e) :doc (:doc e)
                     :type (:type e) :default (:default e)
                     :value (global-value (:id e))}))))

(defn custom-modified? [id]
  (when-let [entry (get @custom-registry id)]
    (not= @(:value entry) (:default entry))))

(defn reset-custom! [id]
  (when-let [entry (get @custom-registry id)]
    (reset! (:value entry) (:default entry))))

(defn all-customs []
  (reduce-kv (fn [m id entry]
               (assoc m id @(:value entry)))
             {} @custom-registry))