(ns top.kzre.krro.core.window
 (:require [top.kzre.krro.core.frame :as frame]
           [top.kzre.krro.core.mode :as mode]
           [clojure.string :as string]
           [top.kzre.krro.core.ui.protocol :as ui]))

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
 (split-frame! [this direction opts]
  "在指定 Frame 的视觉邻接方向上创建一个新的平级 Frame，并更新布局树。
   direction 为 :vertical 或 :horizontal。
   opts 可选 :ratio（原 Frame 所占比例，默认 0.5）。
   返回新创建的 Frame。新 Frame 初始模式为 :krro.mode/fundamental。")
 (delete-frame! [this frame]
  "删除指定 Frame，并从布局树中移除。
   若删除后 Window 中无 Frame，则关闭 Window。
   删除后焦点自动移至相邻 Frame。")
 (other-frame! [this] "按布局遍历顺序将焦点切换到下一个 Frame。")
 (frame-at [this direction]
  "从当前 Frame 出发，返回 direction（:up/:down/:left/:right）方向上的相邻 Frame，
   若不存在则返回 nil。")
 (frames [this] "返回该 Window 中所有平级 Frame 的集合。")
 (window-title [this] "返回窗口标题。")
 (set-window-title! [this title] "设置窗口标题。")
 (window-bounds [this] "返回窗口位置和大小，map 形式 {:x :y :width :height}。")
 (set-window-bounds! [this bounds] "设置窗口位置和大小。")
 (window-visible? [this] "返回窗口是否可见。")
 (show-window! [this] "显示窗口。")
 (hide-window! [this] "隐藏窗口。")
 (close-window! [this] "关闭窗口，释放资源并从全局注册表中移除。")
 (native-window [this] "返回原生窗口协议")
 (layout-desc [this]
  "返回布局描述向量树
 叶子节点：[frame-id]
 分割节点：[direction props child1 child2 ...]"))

;; ── 全局注册表 ────────────────────────────────────
(declare register-window!
 unregister-window!
 lookup-window
 all-windows
 create-window!)

(defonce ^:private window-registry (atom {}))

(defn register-window! [win]
 (swap! window-registry assoc (window-id win) win))

(defn unregister-window! [win]
 (swap! window-registry dissoc (window-id win)))

(defn lookup-window [id]
 (get @window-registry id))

(defn all-windows []
 (vals @window-registry))

;; ── 布局树辅助（内部） ────────────────────────────
(defn leaf? [node]
 (and (vector? node)
      (= 1 (count node))
      (keyword? (first node))))

(defn- frame-id-of [leaf]
 (first leaf))

(defn- all-leaf-ids [node]
 (if (leaf? node)
  [(frame-id-of node)]
  (mapcat all-leaf-ids (drop 2 node))))

(defn- find-leaf [node frame-id]
 (if (leaf? node)
  (when (= (frame-id-of node) frame-id) node)
  (some #(find-leaf % frame-id) (drop 2 node))))

(defn- replace-leaf [node frame-id new-node]
 (if (leaf? node)
  (if (= (frame-id-of node) frame-id) new-node node)
  (let [[direction props & children] node
        new-children (mapv #(replace-leaf % frame-id new-node) children)]
   (into [direction props] new-children))))

(defn- remove-leaf [node frame-id]
 (if (leaf? node)
  (when-not (= (frame-id-of node) frame-id) node)
  (let [[direction props & children] node
        new-children (filterv some? (mapv #(remove-leaf % frame-id) children))]
   (case (count new-children)
    0 nil
    1 (first new-children)
    (into [direction props] new-children)))))

(defn- layout-rects
 [node {:keys [x y w h]}]
 (if (leaf? node)
  {(frame-id-of node) {:x x :y y :w w :h h}}
  (let [[direction props & children] node
        ratios (or (:ratios props) (repeat (count children) (/ 1.0 (count children))))
        total-size (if (#{:horizontal :left :right} direction) w h)
        sizes (map #(* total-size %) ratios)
        offsets (reductions + 0 sizes)
        child-rects
        (map-indexed
         (fn [i child]
          (let [offset (nth offsets i)
                size   (nth sizes i)]
           (if (#{:horizontal :left :right} direction)
            (layout-rects child {:x (+ x offset) :y y :w size :h h})
            (layout-rects child {:x x :y (+ y offset) :w w :h size}))))
         children)]
   (apply merge child-rects))))

(defn- find-neighbour-rect
 [rects current-id direction]
 (when-let [cur (rects current-id)]
  (let [candidates
        (filter (fn [[fid rect]]
                 (and (not= fid current-id)
                      (case direction
                       :left  (and (<= (+ (:x rect) (:w rect)) (:x cur))
                                   (> (+ (:y rect) (:h rect)) (:y cur))
                                   (< (:y rect) (+ (:y cur) (:h cur))))
                       :right (and (>= (:x rect) (+ (:x cur) (:w cur)))
                                   (> (+ (:y rect) (:h rect)) (:y cur))
                                   (< (:y rect) (+ (:y cur) (:h cur))))
                       :up    (and (<= (+ (:y rect) (:h rect)) (:y cur))
                                   (> (+ (:x rect) (:w rect)) (:x cur))
                                   (< (:x rect) (+ (:x cur) (:w cur))))
                       :down  (and (>= (:y rect) (+ (:y cur) (:h cur)))
                                   (> (+ (:x rect) (:w rect)) (:x cur))
                                   (< (:x rect) (+ (:x cur) (:w cur))))
                       false)))
                rects)
        best (first (sort-by
                     (fn [[_ rect]]
                      (case direction
                       :left  (- (:x cur) (+ (:x rect) (:w rect)))
                       :right (- (:x rect) (+ (:x cur) (:w cur)))
                       :up    (- (:y cur) (+ (:y rect) (:h rect)))
                       :down  (- (:y rect) (+ (:y cur) (:h cur)))))
                     candidates))]
   (when best (first best)))))

(defn- neighbour-id
 [layout bounds current-id direction]
 (let [rects (layout-rects layout bounds)]
  (find-neighbour-rect rects current-id direction)))

;; ── Window 实现（委托原生窗口操作给 native） ────
(defrecord Window
 [id frames-atom layout-atom current-frame-id-atom native]
 IWindow
 (window-id [_] id)

 (current-frame [_]
  (when-let [fid @current-frame-id-atom]
   (get @frames-atom fid)))

 (set-current-frame! [_ f]
  (reset! current-frame-id-atom (frame/frame-id f)))

 (split-frame! [this direction {:keys [ratio]}]
  (let [focus-frame (current-frame this)
        _ (when-not focus-frame (throw (ex-info "No current frame to split" {})))
        new-frame (frame/create-frame! this)
        _ (mode/fundamental-activate! new-frame)
        fid (frame/frame-id focus-frame)
        new-fid (frame/frame-id new-frame)
        _ (swap! frames-atom assoc new-fid new-frame)
        old-leaf [fid]
        new-leaf [new-fid]
        split-node [direction {:ratios [(or ratio 0.5) (- 1 ratio)]} old-leaf new-leaf]
        new-layout (replace-leaf @layout-atom fid split-node)]
   (reset! layout-atom new-layout)
   new-frame))

 (delete-frame! [this focus-frame]
  ;; 1. 先通知渲染器销毁该 Frame 的 UI 部分
  (ui/destroy-frame! focus-frame)
  ;; 2. 更新布局树
  (let [fid (frame/frame-id focus-frame)
        new-layout (remove-leaf @layout-atom fid)]
   (if (nil? new-layout)
    (close-window! this)                     ;; 无 Frame 剩余，关闭窗口
    (do
     (reset! layout-atom new-layout)
     (swap! frames-atom dissoc fid)
     (when-let [next-id (first (all-leaf-ids new-layout))]
      (set-current-frame! this (get @frames-atom next-id)))))))

 (other-frame! [this]
  (let [ids (vec (all-leaf-ids @layout-atom))
        current-id @current-frame-id-atom
        idx (.indexOf ids current-id)
        next-id (get ids (mod (inc idx) (count ids)))]
   (when next-id
    (set-current-frame! this (get @frames-atom next-id)))))

 (frame-at [_this direction]
  (when-let [native native]
   (let [bounds (native-bounds native)]
    (when-let [fid (neighbour-id @layout-atom bounds @current-frame-id-atom direction)]
     (get @frames-atom fid)))))

 (frames [_] (vals @frames-atom))

 ;; 窗口元数据全部委托给 native
 (window-title [_] (when native (native-title native)))
 (set-window-title! [_ title] (when native (native-set-title! native title)))
 (window-bounds [_] (when native (native-bounds native)))
 (set-window-bounds! [_ bounds] (when native (native-set-bounds! native bounds)))
 (window-visible? [_] (when native (native-visible? native)))
 (show-window! [_] (when native (native-show! native)))
 (hide-window! [_] (when native (native-hide! native)))
 (close-window! [this]
  (when native (native-close! native))
  (unregister-window! this)
  nil)
 (native-window [_] native)
 (layout-desc [_] @layout-atom) )

;; ── 创建窗口 ────────────────────────────────────
(defn create-window!
 [native
  & {:keys [id]
     :or   {id (keyword (str "window-" (gensym)))}}]
 (let [;; 创建窗口对象，并立即赋予三个原子（初始为空）
       win (->Window id (atom {}) (atom []) (atom nil) native)
       ;; 在窗口对象完全就绪后，创建初始 Frame
       initial-frame (frame/create-frame! win :id :main)
       fid (frame/frame-id initial-frame)]
  ;; 将初始 Frame 注册到窗口的集合和布局树中
  (swap! (:frames-atom win) assoc fid initial-frame)
  (reset! (:layout-atom win) [fid])
  (reset! (:current-frame-id-atom win) fid)
  (register-window! win)
  (mode/fundamental-activate! initial-frame)
  win))


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