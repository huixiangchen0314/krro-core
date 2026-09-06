(ns top.kzre.krro.core.window-impl
  (:require
    [top.kzre.krro.core.frame :as frame]
    [top.kzre.krro.core.mode :as mode]
    [top.kzre.krro.core.ui.protocol :as ui]
    [top.kzre.krro.core.window :as win]))


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
  win/IWindow
  (window-id [_] id)

  (current-frame [_]
    (when-let [fid @current-frame-id-atom]
      (get @frames-atom fid)))

  (set-current-frame! [_ f]
    (reset! current-frame-id-atom (frame/frame-id f)))

  (split-frame! [this direction {:keys [ratio]}]
    (let [focus-frame (win/current-frame this)
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
        (win/close-window! this)                     ;; 无 Frame 剩余，关闭窗口
        (do
          (reset! layout-atom new-layout)
          (swap! frames-atom dissoc fid)
          (when-let [next-id (first (all-leaf-ids new-layout))]
            (win/set-current-frame! this (get @frames-atom next-id)))))))

  (other-frame! [this]
    (let [ids (vec (all-leaf-ids @layout-atom))
          current-id @current-frame-id-atom
          idx (.indexOf ids current-id)
          next-id (get ids (mod (inc idx) (count ids)))]
      (when next-id
        (win/set-current-frame! this (get @frames-atom next-id)))))

  (frame-at [_this direction]
    (when-let [native native]
      (let [bounds (win/native-bounds native)]
        (when-let [fid (neighbour-id @layout-atom bounds @current-frame-id-atom direction)]
          (get @frames-atom fid)))))

  (frames [_] (vals @frames-atom))

  ;; 窗口元数据全部委托给 native
  (window-title [_] (when native (win/native-title native)))
  (set-window-title! [_ title] (when native (win/native-set-title! native title)))
  (window-bounds [_] (when native (win/native-bounds native)))
  (set-window-bounds! [_ bounds] (when native (win/native-set-bounds! native bounds)))
  (window-visible? [_] (when native (win/native-visible? native)))
  (show-window! [_] (when native (win/native-show! native)))
  (hide-window! [_] (when native (win/native-hide! native)))
  (close-window! [this]
    (when native (win/native-close! native))
    (win/unregister-window! this)
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
    (win/register-window! win)
    (mode/fundamental-activate! initial-frame)
    win))
