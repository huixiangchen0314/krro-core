(ns top.kzre.krro.core.keymap
  "快捷键系统，一等公民。数据即键图，键图即数据。")


;; 键图数据结构：
;; {:keys {<key-descriptor> <binding>}   ;; 单键绑定
;;  :parent <parent-keymap>              ;; 继承（可选）
;;  :prefix nil}                         ;; 前缀键（可选，用于键序列）
;;
;; key-descriptor 可以是字符（如 \g）、关键字（如 :esc、:f1）或字符串（如 "C-x"）。
;; binding 可以是命令 ID（keyword）或另一个 keymap（用于键序列）。

(defn make-keymap
  "创建一个新的键图，可选地继承自父键图。"
  ([bindings] {:keys bindings :parent nil})
  ([bindings parent] {:keys bindings :parent parent}))

(defn lookup-key
  "在键图中递归查找 key-descriptor 对应的绑定。
   如果绑定是另一个键图，则返回该键图（表示键序列的前缀）。"
  [keymap key-desc]
  (when keymap
    (or (get-in keymap [:keys key-desc])
        (lookup-key (:parent keymap) key-desc))))

;; 全局键图（一个 atom，方便动态修改）
(defonce global-keymap
         (atom (make-keymap {:u :krro.command/undo
                             :r :krro.command/redo
                             :esc :krro.command/escape
                             "C-z" :krro.command/undo})))

(defn set-global-key!
  "动态修改全局键绑定。"
  [key-desc command-id]
  (swap! global-keymap
         #(update % :keys assoc key-desc command-id)))
