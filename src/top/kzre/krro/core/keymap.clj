(ns top.kzre.krro.core.keymap
  "快捷键系统，支持全局键图 + 模式局部键图栈。")

(defn make-keymap
  ([bindings] {:keys bindings :parent nil})
  ([bindings parent] {:keys bindings :parent parent}))

(defn lookup-key
  "在单个键图中递归查找。"
  [keymap key-desc]
  (when keymap
    (or (get-in keymap [:keys key-desc])
        (lookup-key (:parent keymap) key-desc))))

;; ── 全局键图 ──────────────────────────────────
(defonce global-keymap
         (atom (make-keymap {:u :krro.command/undo
                             :r :krro.command/redo
                             :esc :krro.command/escape
                             "C-z" :krro.command/undo})))

(defn set-global-key! [key-desc command-id]
  (swap! global-keymap #(update % :keys assoc key-desc command-id)))

;; ── 模式键图栈 ────────────────────────────────
(defonce ^:private keymap-stack (atom ()))

(defn push-keymap! [km]
  (when km (swap! keymap-stack conj km)))

(defn pop-keymap! []
  (swap! keymap-stack rest))

(defn current-keymaps
  "返回当前有效的键图列表，优先级从高到低。"
  []
  (concat @keymap-stack [@global-keymap]))

(defn lookup-key-in-context
  "在全局上下文（模式栈 + 全局键图）中查找绑定。"
  [key-desc]
  (some #(lookup-key % key-desc) (current-keymaps)))