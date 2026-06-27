(ns top.kzre.krro.core.custom
  "用户配置系统。defcustom 宏创建 atom 并绑定到 Var，同时注册。
   局部变量栈操作直接作用在 atom 上。"
  (:require [top.kzre.krro.core.hook :as hook]))


;; 类型验证多方法
(defmulti valid-type? (fn [type value] type))
(defmethod valid-type? :integer [_ v] (integer? v))
(defmethod valid-type? :boolean [_ v] (boolean? v))
(defmethod valid-type? :string [_ v] (string? v))
(defmethod valid-type? :keyword [_ v] (keyword? v))
(defmethod valid-type? :default [_ _] true)

(defonce custom-change-hook (hook/make-hook))

(defonce custom-registry (atom {}))



(defn create-custom [sym default docstring & {:as opts}]
  (let [meta-map (merge {:default default} opts)
        a (atom default)]
    (reset-meta! a {:default default :custom/var-sym sym})
    (add-watch a :custom
               (fn [_ _ old new]
                 (hook/run-hooks custom-change-hook sym old new)))
    (swap! custom-registry assoc sym (assoc meta-map :atom a))
    a))

(defn get-custom [var] @var)
(defn all-customs [] @custom-registry)
(defn set-custom! [var value]
  (when-let [entry (get @custom-registry (:custom/var-sym (meta var)))]
    (when-let [type (:type entry)]
      (when-not (valid-type? type value)
        (throw (ex-info "Invalid value type" {:var var, :expected type, :actual value})))))
  (reset! var value))

;; ── 局部变量栈 ──────────────────────
(defn push-local-value! [custom-var value]
  (let [old-stack (:local-stack (meta custom-var))]
    (try
      (alter-meta! custom-var update :local-stack (fnil conj []) value)
      (reset! custom-var value)
      (catch Exception e
        ;; 回滚元数据栈
        (alter-meta! custom-var assoc :local-stack old-stack)
        (throw e)))))

(defn pop-local-value!
  "弹出 custom-var 的局部变量栈顶，恢复上一个值或默认值。"
  [custom-var]
  (when-let [stack (:local-stack (meta custom-var))]
    (when (seq stack)                     ;; 防止空栈 pop 异常
      (let [new-stack (pop stack)]
        (alter-meta! custom-var assoc :local-stack new-stack)
        (if-let [restored (peek new-stack)]
          (reset! custom-var restored)   ;; 恢复到上一层局部值
          ;; 栈已空，恢复到全局默认值
          (when-let [d (:default (meta custom-var))]
            (reset! custom-var d)))))))


(defmacro defcustom
  "声明一个可定制变量，返回其 atom 并绑定到 name。"
  [name default docstring & {:as opts}]
  `(def ~name (create-custom '~name ~default ~docstring ~@(flatten (seq opts)))))