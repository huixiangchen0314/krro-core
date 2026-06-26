(ns top.kzre.krro.core.custom
  "用户配置系统，支持 defcustom 宏与模式局部变量覆盖。")

(defonce custom-registry (atom {}))

(defmacro defcustom
  "声明一个可定制变量（atom），支持 :type, :group, :range 等。"
  [name default docstring & {:as opts}]
  `(let [var# (quote ~name)
         meta-map# (merge {:default ~default} ~opts)
         a# (atom ~default)]
     (swap! custom-registry assoc var# (assoc meta-map# :atom a#))
     (defonce ~name a#)))

(defn get-custom [var]
  @var)

(defn set-custom! [var value]
  (reset! var value))

(defn all-customs []
  @custom-registry)

;; ── 局部变量栈（用于模式）──────────────────────
(defn push-local-value! [custom-var value]
  (alter-meta! custom-var update :local-stack (fnil conj []) value)
  (reset! custom-var value))

(defn pop-local-value! [custom-var]
  (when-let [stack (:local-stack (meta custom-var))]
    (let [new-stack (pop stack)]
      (alter-meta! custom-var assoc :local-stack new-stack)
      (if-let [restored (peek new-stack)]
        (reset! custom-var restored)
        ;; 恢复全局默认
        (when-let [global (get @custom-registry (symbol (name custom-var)))]
          (reset! custom-var (:default global)))))))