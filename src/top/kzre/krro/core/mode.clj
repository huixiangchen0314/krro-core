(ns top.kzre.krro.core.mode
  "模式系统，参照 Emacs 的 major/minor-mode。
   模式是数据，Hook 是模式数据的一部分。")

;; ── 模式创建函数 ─────────────────────────────────────
(defn make-major-mode [id name & {:as opts}]
  (merge {:mode/id id :mode/name name} opts))

(defn make-minor-mode [id name & {:as opts}]
  (merge {:mode/id id :mode/name name :mode/minor? true} opts))

;; ── 模式激活与停用（由内核调用）───────────────────────
(defn activate-mode! [mode project]
  (when-let [on-enter (get-in mode [:mode/hooks :on-enter])]
    (doseq [f on-enter] (f project)))
  ;; 加载 keymap, layout 等...
  )

(defn deactivate-mode! [mode project]
  (when-let [on-exit (get-in mode [:mode/hooks :on-exit])]
    (doseq [f on-exit] (f project))))