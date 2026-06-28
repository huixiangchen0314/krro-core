(ns top.kzre.krro.core.frame
  "Frame 抽象：每个 Frame 代表一个独立的工作空间，持有模式、键图栈。
   所有状态通过 IFrame 协议封装，不暴露内部原子。"
  (:require [top.kzre.krro.core.keymap :as km]))

(defprotocol IFrame
  (frame-id [this] "返回 Frame 的唯一标识")
  (major-mode [this] "返回当前主模式 ID")
  (minor-modes [this] "返回当前激活的副模式集合")
  (set-major-mode! [this mode-id] "设置主模式")
  (add-minor-mode! [this mode-id] "激活副模式")
  (remove-minor-mode! [this mode-id] "停用副模式")
  ;; ── 键图栈操作 ──────────────────────────
  (push-keymap [this km] "压入键图到 Frame 的键图栈")
  (pop-keymap [this] "弹出栈顶键图")
  (keymaps [this] "返回当前有效的键图列表（包括全局）"))

(defrecord Frame [id major-mode-atom minor-modes-atom keymap-stack-atom]
  IFrame
  (frame-id [_] id)
  (major-mode [_] @major-mode-atom)
  (minor-modes [_] @minor-modes-atom)
  (set-major-mode! [_ mode-id] (reset! major-mode-atom mode-id))
  (add-minor-mode! [_ mode-id] (swap! minor-modes-atom conj mode-id))
  (remove-minor-mode! [_ mode-id] (swap! minor-modes-atom disj mode-id))
  (push-keymap [_ km] (swap! keymap-stack-atom conj km))
  (pop-keymap [_] (swap! keymap-stack-atom rest))
  (keymaps [_] (concat @keymap-stack-atom [@km/global-keymap])))

(defn create-frame
  "创建一个新的 Frame，可指定 :id。"
  [& {:keys [id] :or {id (keyword (str "frame-" (gensym "f")))}}]
  (map->Frame {:id id
               :major-mode-atom (atom :krro.mode/fundamental)
               :minor-modes-atom (atom #{})
               :keymap-stack-atom (atom ())}))

(def ^:dynamic *current-frame* nil)

(defmacro with-frame [f & body]
  `(binding [*current-frame* ~f]
     ~@body))