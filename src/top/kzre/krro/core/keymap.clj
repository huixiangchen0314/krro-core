(ns top.kzre.krro.core.keymap
  "快捷键系统，支持全局键图、键图数据结构与按键分派。
   所有与 Frame 相关的状态操作已移至 Frame 协议，此处仅提供纯函数。"
  (:require
    [top.kzre.krro.core.command :as cmd]
    [top.kzre.krro.core.hook :as hook]
    [top.kzre.krro.core.message :as msg]))

(defonce echo-hook (hook/make-hook))

;; ── 键图数据结构与查找 ──────────────────────────
(defn make-keymap
  ([bindings] {:keys bindings :parent nil})
  ([bindings parent] {:keys bindings :parent parent}))

(defn lookup-key
  "在单个键图中递归查找 key-desc 对应的绑定。"
  [keymap key-desc]
  (when keymap
    (or (get-in keymap [:keys key-desc])
        (lookup-key (:parent keymap) key-desc))))

;; ── 全局键图 ────────────────────────────────
(defonce global-keymap (atom (make-keymap {})))

(defn set-global-key! [key-desc command-id]
  (swap! global-keymap #(update % :keys assoc key-desc command-id)))

;; ── 键序列前缀栈 ───────────────────────────
(defonce prefix-stack (atom ()))

(defn lookup-key-in-context
  "在给定的键图列表中查找 key-desc 的绑定。"
  [key-desc keymaps-list]
  (some #(lookup-key % key-desc) keymaps-list))

(defn handle-key!
  "处理单个按键 key-desc。需提供当前有效的键图列表（从 Frame/keymaps 获取）。"
  [key-desc keymaps-list]
  (let [kmaps (if-let [top (peek @prefix-stack)]
                (cons (:keymap top) keymaps-list)
                keymaps-list)
        binding (some #(lookup-key % key-desc) kmaps)]
    (cond
      (map? binding)
      (do
        (when-let [prefix-str (:prefix binding)]
          (hook/run-hooks echo-hook prefix-str))
        (swap! prefix-stack conj {:keymap binding}))

      (keyword? binding)
      (do
        (reset! prefix-stack [])
        (try
          (cmd/execute-command! binding)
          (catch Exception e
            (msg/error (str "Command execution failed for " binding ": " (.getMessage e))))))

      :else
      (do
        (reset! prefix-stack [])
        (msg/warn (str "Undefined key sequence: " key-desc))))))

(defn describe-key
  "描述键在给定键图列表中的绑定情况。"
  [key-desc keymaps-list]
  (keep #(lookup-key % key-desc) keymaps-list))

(defn reset-prefix!
  "取消当前键序列。"
  []
  (reset! prefix-stack []))