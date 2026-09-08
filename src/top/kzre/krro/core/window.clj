(ns top.kzre.krro.core.window
  (:require
    [top.kzre.krro.core.window :as win]
    [top.kzre.krro.core.frame :as frame]))

;; ── 原生窗口协议 ──────────────────────────────────
(defprotocol INativeWindow
 "原生窗口抽象，由具体 UI 平台实现。"
 (native-show! [this])
 (native-hide! [this])
 (native-close! [this])
 (native-title [this])
 (native-set-title! [this title])
 (native-set-bounds! [this bounds])
 (native-bounds [this] "返回 {:x :y :width :height}")
 (native-visible? [this])
 (native-focused? [this] "返回窗口是否拥有输入焦点")
 (native-object [this] "返回平台窗口对象，如 JavaFX Stage"))

;; ── 窗口协议 ──────────────────────────────────────
(defprotocol IWindow
 "顶层系统窗口抽象。
  管理一组平级的 Frame，通过布局树描述它们的视觉分割。
  所有 Frame 的创建与销毁都通过分割操作完成。"
 (window-id [this] "返回 Window 的唯一标识。")
 (current-frame [this] "返回当前拥有焦点的 Frame。")
 (set-current-frame! [this frame] "设置当前 Frame 为焦点。")
 (layout-desc [this]
  "返回布局描述向量树
 叶子节点：[frame-id-id]
 分割节点：[direction props-map child1 child2 ...]")
 (split-frame! [this direction opts]
  "在指定 Frame 的视觉邻接方向上创建一个新的平级 Frame，并更新布局树。
   direction 为 :vertical 或 :horizontal。
   opts 可选 :ratio（原 Frame 所占比例，默认 0.5）。
   返回新创建的 Frame。新 Frame 初始模式为 :krro.mode/fundamental。")
 (delete-frame! [this frame-id]
  "删除指定 Frame，并从布局树中移除。
   若删除后 Window 中无 Frame，则关闭 Window。
   删除后焦点自动移至相邻 Frame。")
 (frames [this] "返回该 Window 中所有平级 Frame 的集合。")
 (get-frame [this frame-id])
 (window-title [this] "返回窗口标题。")
 (set-window-title! [this title] "设置窗口标题。")
 (window-bounds [this] "返回窗口位置和大小，map 形式 {:x :y :width :height}。")
 (set-window-bounds! [this bounds] "设置窗口位置和大小。")
 (window-visible? [this] "返回窗口是否可见。")
 (show-window! [this] "显示窗口。")
 (hide-window! [this] "隐藏窗口。")
 (close-window! [this] "关闭窗口，释放资源并从全局注册表中移除。")
 (native-window [this] "返回原生窗口协议")
 )

;; ── 全局注册表 ────────────────────────────────────
(defonce ^:private window-registry (atom {}))

(defn register-window! [win]
 (swap! window-registry assoc (window-id win) win))

(defn unregister-window! [win]
 (swap! window-registry dissoc (window-id win)))

(defn lookup-window [id]
 (get @window-registry id))

(defn all-windows []
 (vals @window-registry))

(defn all-frames []
 (mapcat win/frames (all-windows)))

(defn active-window
 "返回当前拥有输入焦点的 Window，若没有则返回 nil。"
 []
 (some (fn [win]
        (when-let [native (native-window win)]
         (when (native-focused? native)
          win)))
       (all-windows)))

(defn active-frame
 "返回当前焦点窗口的当前 Frame，若没有则返回 nil。"
 []
 (when-let [w (active-window)]
  (current-frame w)))

(defn frames-with-param
 "返回所有参数中指定 key 的值等于 val 的 Frame 列表。"
 [key val]
 (filter #(= (frame/param % key) val) (all-frames)))

(defn split-frame-horizontal!
 ([]
  (when-let [w (active-window)]
   (split-frame-horizontal! w )))
 ([w] (split-frame! w :horizontal {:ratio 0.5})))