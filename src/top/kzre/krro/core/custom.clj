(ns top.kzre.krro.core.custom
  "用户配置系统，提供 defcustom 宏，声明可定制的全局变量。")

(defonce custom-registry (atom {}))

;; ── defcustom 宏 ─────────────────────────────────────
(defmacro defcustom
  "声明一个可定制变量，类似于 Emacs 的 defcustom。
   name      - 变量名（symbol）
   default   - 默认值
   docstring - 文档字符串
   &rest     - 可选键值对，支持 :type（如 :integer, :boolean, :string, :color）、
               :group（分组 symbol）、:range、:options 等"
  [name default docstring & {:as opts}]
  `(let [var# (quote ~name)]
     ;; 注册到全局 custom registry
     (swap! custom-registry assoc var#
            (merge {:value ~default
                    :default ~default
                    :doc ~docstring}
                   ~opts))
     ;; 同时声明为动态变量
     (defonce ~name (atom ~default))))

;; ── 辅助函数 ─────────────────────────────────────────
(defn get-custom [var]
  (when-let [entry (get @custom-registry var)]
    @var))

(defn set-custom! [var value]
  (when-let [entry (get @custom-registry var)]
    (reset! var value)))

(defn all-customs []
  @custom-registry)