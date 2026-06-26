(ns top.kzre.krro.core.keymap
  "快捷键系统，支持全局键图、模式局部键图栈以及键序列处理。
   提供 handle-key! 作为平台无关的按键分派入口。"
  (:require [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.project :as proj]))

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
(defonce global-keymap
         (atom (make-keymap {:u :krro.command/undo
                             :r :krro.command/redo
                             :esc :krro.command/escape
                             "C-z" :krro.command/undo})))

(defn set-global-key! [key-desc command-id]
  (swap! global-keymap #(update % :keys assoc key-desc command-id)))

;; ── 模式局部键图栈 ──────────────────────────
(defonce keymap-stack (atom ()))

(defn push-keymap! [km]
  (when km (swap! keymap-stack conj km)))

(defn pop-keymap! []
  (swap! keymap-stack rest))

(defn current-keymaps
  "返回当前有效的键图列表，优先级从高到低。"
  []
  (concat @keymap-stack [@global-keymap]))

(defn lookup-key-in-context
  "在当前上下文中查找 key-desc 的绑定。"
  [key-desc]
  (some #(lookup-key % key-desc) (current-keymaps)))

;; ── 键序列前缀栈 ───────────────────────────
(defonce prefix-stack (atom ()))

(defn- active-keymaps
  "考虑前缀键后的完整查找链。"
  []
  (if-let [top (peek @prefix-stack)]
    (cons top (current-keymaps))
    (current-keymaps)))

(defn handle-key!
  "处理单个按键 key-desc。
   - 若绑定为子键图，推入前缀栈。
   - 若绑定为命令关键字，执行并清空前缀。
   - 否则清空前缀并提示。"
  [key-desc]
  (let [kmaps (active-keymaps)
        binding (some #(lookup-key % key-desc) kmaps)]
    (cond
      (map? binding)
      (swap! prefix-stack conj binding)

      (keyword? binding)
      (do
        (reset! prefix-stack [])
        (cmd/execute-command proj/project binding))

      :else
      (do
        (reset! prefix-stack [])
        (println "Undefined key sequence:" key-desc)))))

(defn reset-prefix!
  "取消当前键序列。"
  []
  (reset! prefix-stack []))