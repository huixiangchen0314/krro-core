(ns top.kzre.krro.core.custom
  "用户配置系统。defcustom 宏创建 atom 并绑定到 Var，同时注册。
   局部变量栈操作直接作用在 atom 上。")

(defonce custom-registry (atom {}))



(defn create-custom
  "创建并注册一个定制变量，返回 atom。"
  [sym default docstring & {:as opts}]
  (let [meta-map (merge {:default default} opts)
        a (atom default)]
    (reset-meta! a {:default default})
    (swap! custom-registry assoc sym (assoc meta-map :atom a))
    a))

(defn get-custom [var] @var)
(defn set-custom! [var value] (reset! var value))
(defn all-customs [] @custom-registry)

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

(defn pop-local-value! [custom-var]
  (when-let [stack (:local-stack (meta custom-var))]
    (when (seq stack)
      (let [new-stack (pop stack)]
        (alter-meta! custom-var assoc :local-stack new-stack)
        (if-let [restored (peek new-stack)]
          (reset! custom-var restored)
          (when-let [d (:default (meta custom-var))]
            (reset! custom-var d)))))))


(defmacro defcustom
  "声明一个可定制变量，返回其 atom 并绑定到 name。"
  [name default docstring & {:as opts}]
  `(def ~name (create-custom '~name ~default ~docstring ~@(flatten (seq opts)))))