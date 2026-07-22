(ns top.kzre.krro.core.keymap
  "快捷键系统，支持嵌套键图和多键序列。
   键图就是普通的嵌套 map，键为关键字，值为命令关键字或子键图。
   例如 {:u :undo, :C-x {:u :save}}。
   全局键图、前缀栈均使用此结构。"
  (:require
   [top.kzre.krro.core.command :as cmd]
   [top.kzre.krro.core.hook :as hook]
   [top.kzre.krro.core.message :as msg]
   [top.kzre.krro.core.variable :refer [*debug*]]))

;; ── 全局键图 ────────────────────────────────
(defonce global-keymap (atom {}))

(defn set-global-key!
  "设置全局键绑定，key-vec 为关键字向量，如 [:C-x :u] 或 [:u]。"
  [key-vec command-id]
  (swap! global-keymap assoc-in key-vec command-id))

;; ── 键图构造 ────────────────────────────────
(defn make-keymap
  "根据平面键绑定 map 生成嵌套键图。
   绑定表的键可以是：
     - 关键字（单键，如 :u）
     - 键序列向量（如 [:C-x :u]）
   值为命令关键字或前缀键图。
   返回生成好的嵌套键图 map。
   示例：
     (make-keymap {:u :undo, [:C-x :u] :save})
     => {:u :undo, :C-x {:u :save}}"
  [bindings]
  (reduce-kv
    (fn [m key command]
      (let [key-vec (if (vector? key) key [key])]
        (if (seq key-vec)
          (try
            (assoc-in m key-vec command)
            (catch ClassCastException e
              (throw (ex-info (str "Cannot bind key sequence " key-vec
                                   " because prefix is already a command")
                              {:key-vec key-vec :existing (get-in m (butlast key-vec))}
                              e))))
          (throw (ex-info "Key sequence must not be empty" {})))))
    {}
    bindings))

;; ── 键序列前缀栈 ───────────────────────────
(defonce prefix-stack (atom []))

(defn lookup-key
  "在给定的键图中查找 key-vec 对应的绑定。
   key-vec 为关键字向量，如 [:C-x :u] 或 [:u]。
   返回：
     - 关键字：找到的命令 ID
     - map：    前缀键图（需要继续输入）
     - nil：    未绑定"
  [keymap key-vec]
  (when keymap
    (let [first-key (first key-vec)]
      (if-let [binding (get keymap first-key)]
        (if (map? binding)
          (if-let [rest-keys (seq (rest key-vec))]
            (lookup-key binding (vec rest-keys))
            binding)
          binding)
        nil))))

(defn lookup-key-in-context
  "在键图列表中按优先级查找 key-vec 的绑定。列表从头到尾优先级递减。"
  [key-vec keymaps-list]
  (some #(lookup-key % key-vec) keymaps-list))

(defn handle-key!
  "处理单个按键 key-desc（关键字，如 :C-x 或 :u）。keymaps-list 为当前有效的键图列表。"
  [key-desc keymaps-list]
  (let [key-vec [key-desc]
        effective-keymaps (if-let [prefix-km (peek @prefix-stack)]
                            (cons prefix-km keymaps-list)
                            keymaps-list)
        binding (lookup-key-in-context key-vec effective-keymaps)]
    (cond
      (map? binding)
      (do
        (when-let [prefix-str (:prefix binding)]
          (hook/run-hook! :krro.core/key-sequence-hook prefix-str))
        (swap! prefix-stack conj binding))

      (keyword? binding)
      (do
        (reset! prefix-stack [])
        (try
          (cmd/execute-command! binding)
          (catch Exception e
            (msg/error (str "Command execution failed for " binding ": " (.getMessage e)))
            (when *debug* (throw e)))))

      :else
      (do
        (reset! prefix-stack [])
        (msg/warn (str "Undefined key sequence: " key-desc))))))

(defn describe-key
  "描述键在给定键图列表中的绑定情况。"
  [key-vec keymaps-list]
  (keep #(lookup-key % key-vec) keymaps-list))

(defn reset-prefix!
  "取消当前键序列。"
  []
  (reset! prefix-stack []))