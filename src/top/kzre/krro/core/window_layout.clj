(ns top.kzre.krro.core.window-layout
  (:require [clojure.spec.alpha :as s]))

(defonce directions #{:horizontal :vertical})
(s/def ::frame-id keyword?)
(s/def ::direction directions)

;; ratio 语义：第一个子节点的占比，落在 (0,1) 保证两子都非空
(s/def ::ratio (s/and number? #(> % 0) #(< % 1)))
(s/def ::props (s/keys :opt-un [::ratio]))

;; 布局递归 spec
;; 叶子节点：[:frame-id] 或 [:frame-id props]
;; 分割节点：[direction props child1 child2]
(s/def ::layout
  (s/or :frame (s/cat :frame-id ::frame-id
                      :props (s/? ::props))
        :split (s/cat :direction ::direction
                      :props ::props
                      :child1 ::layout
                      :child2 ::layout)))

(defn make-leaf
  [frame-id & {:as props}]
  (if props
    [frame-id props]
    [frame-id]))

(defn make-split
  "创建一个两叉分割节点。
   direction: :horizontal 或 :vertical
   left/right: 子节点
   可选 :ratio（第一个子节点占比，默认 0.5）"
  [direction left right & {:keys [ratio] :or {ratio 0.5} :as props}]
  [direction (assoc props :ratio ratio) left right])

(defn leaf? [node]
  (and (vector? node)
       (let [kw (first node)]
         (and (keyword? kw)
              (not (contains? directions kw))))))

(defn split? [node]
  (and (vector? node) (contains? directions (first node))))

(defn children [node]
  (when (split? node)
    (drop 2 node)))

(defn props [node]
  (or (second node) {}))

(defn split-direction [node]
  (first node))

(defn frame-id [leaf]
  (first leaf))

(defn all-frames [tree]
  (letfn [(walk [node]
            (cond
              (leaf? node) [(frame-id node)]
              (split? node) (mapcat walk (nthrest node 2))))]
    (walk tree)))

(defn get-prop [tree k & default]
  (if-let [ps (props tree)]
    (get ps k default)
    default))

(defn get-ratio
  "返回分割节点第一个子节点的占比，始终落在 (0,1)。非法值回退 0.5。"
  [node]
  (let [r (get-prop node :ratio 0.5)]
    (if (< 0 r 1) r 0.5)))

(defn prewalk
  ([tree f] (prewalk tree f false))
  ([tree f reversed?]
   (if (leaf? tree)
     (f tree)
     (let [[direction props & children] (f tree)
           new-children (mapv #(prewalk % f reversed?)
                              (if reversed?
                                (reverse children)
                                children))]
       (into [direction props] new-children)))))

(defn replace-leaf [tree fid new-node]
  (prewalk tree
           (fn [node]
             (if (leaf? node)
               (if (= (frame-id node) fid)
                 new-node
                 node)
               node))))

(defn remove-leaf [node fid]
  (if (leaf? node)
    (when (not= (frame-id node) fid) node)
    (let [[direction props & children] node
          new-children (filterv some? (mapv #(remove-leaf % fid) children))]
      (case (count new-children)
        0 nil
        1 (first new-children)              ; 单子节点合并层级
        (into [direction props] new-children)))))

(defn empty-layout? [tree]
  (or (nil? tree)
      (empty? tree)))

(defn get-aabb [tree]
  (get-prop tree :aabb))

(defn child-rect
  "计算父节点中指定索引子节点的标准化 AABB 矩形。
   parent-rect 归一化坐标；dir 分割方向；ratio 第一个子节点占比；child-idx 0 或 1。"
  [parent-rect dir ratio child-idx]
  (let [total (if (= dir :horizontal)
                (- (:max-x parent-rect) (:min-x parent-rect))
                (- (:max-y parent-rect) (:min-y parent-rect)))
        start (if (= dir :horizontal) (:min-x parent-rect) (:min-y parent-rect))
        [prev-sum cur-sum] (if (zero? child-idx)
                             [0.0 ratio]
                             [ratio 1.0])
        cur-start (+ start (* total prev-sum))
        cur-end   (+ start (* total cur-sum))]
    (if (= dir :horizontal)
      (assoc parent-rect :min-x cur-start :max-x cur-end)
      (assoc parent-rect :min-y cur-start :max-y cur-end))))

(defn aabb-caching?
  "只有分割节点会缓存 :aabb。"
  [node]
  (not (nil? (get (props node) :aabb))))

(defn recompute-aabbs
  "为树的每个节点计算 aabb，附在 :props :aabb 上。"
  [tree]
  (letfn [(recompute [node rect]
            (if (leaf? node)
              (let [new-props (assoc (props node) :aabb rect)]
                [(frame-id node) new-props])
              (let [dir     (split-direction node)
                    ratio   (get-ratio node)
                    children (vec (children node))
                    cnt     (count children)
                    child-rects (mapv #(child-rect rect dir ratio %) (range cnt))
                    new-children (mapv recompute children child-rects)
                    new-props    (assoc (props node) :aabb rect)]
                (into [dir new-props] new-children))))]
    (recompute tree {:min-x 0 :max-x 1 :min-y 0 :max-y 1})))

(defn ensure-aabb-caching [tree]
  (if (aabb-caching? tree)
    tree
    (recompute-aabbs tree)))

(defn invalidate-aabbs
  "清掉树上所有 :aabb，强制下次 frame-aabb 重算。"
  [layout]
  (prewalk
    layout
    (fn [node]
      (if (split? node)
        (update node 1 dissoc :aabb)
        node))))

(defn frame-aabb
  "返回 frame 占据的标准化 aabb {:keys [min-x min-y max-x max-y]}。"
  [tree frame-id]
  (letfn [(find-aabb [node rect]
            (cond
              (leaf? node)
              (when (= (frame-id node) frame-id) rect)

              (split? node)
              (let [children (vec (children node))
                    cnt      (count children)]
                (loop [i 0]
                  (when (< i cnt)
                    (let [child (nth children i)
                          child-rect (get-aabb child)]
                      (if-let [found (find-aabb child child-rect)]
                        found
                        (recur (inc i)))))))))]
    (find-aabb (ensure-aabb-caching tree)
               {:min-x 0 :max-x 1 :min-y 0 :max-y 1})))

(defn frame-aabbs
  "返回所有叶子节点的 {frame-id aabb}。"
  [tree]
  (let [tree' (ensure-aabb-caching tree)
        all-ids (all-frames tree)]
    (into {} (mapv (fn [id] [id (frame-aabb tree' id)]) all-ids))))

(defn navigate
  "从当前 frame 沿方向 dir 移动到相邻 frame。
   返回目标 frame-id。wrap? 为 true 支持回绕。"
  [tree current-frame-id dir
   & {:keys [point-in-frame wrap?]
      :or {point-in-frame {:x 0.5 :y 0.5}
           wrap? true}}]
  (let [aabb-map (frame-aabbs tree)
        cur-rect (get aabb-map current-frame-id)
        px (+ (:min-x cur-rect) (* (- (:max-x cur-rect) (:min-x cur-rect)) (:x point-in-frame)))
        py (+ (:min-y cur-rect) (* (- (:max-y cur-rect) (:min-y cur-rect)) (:y point-in-frame)))
        eps 1e-9

        candidates (filter
                     (fn [[id rect]]
                       (when-not (= id current-frame-id)
                         (case dir
                           (:left :right) (<= (- (:min-y rect) eps) py (+ eps (:max-y rect)))
                           (:up :down)    (<= (- (:min-x rect) eps) px (+ eps (:max-x rect))))))
                     aabb-map)

        dist-fn (fn [[_id rect]]
                  (case dir
                    :left  (- px (:max-x rect))
                    :right (- (:min-x rect) px)
                    :up    (- py (:max-y rect))
                    :down  (- (:min-y rect) py)))]

    (when (seq candidates)
      (or (when-let [[frame-id dist] (apply min-key dist-fn candidates)]
            (when-not (< dist eps) frame-id))
          (when wrap? (first (apply max-key dist-fn candidates)))))))

(defn frame-id-at-point
  "返回归一化坐标点 {:x x :y y} 所在的叶子 frame-id。若不在任何 frame 内返回 nil。"
  [tree {:keys [x y]}]
  (let [tree' (ensure-aabb-caching tree)
        eps 1e-9
        in-rect? (fn [{:keys [min-x max-x min-y max-y]}]
                   (and (>= x (- min-x eps))
                        (<= x (+ max-x eps))
                        (>= y (- min-y eps))
                        (<= y (+ max-y eps))))]
    (letfn [(find-frame [node]
              (if (leaf? node)
                (frame-id node)
                (some (fn [child]
                        (when-let [rect (get-aabb child)]
                          (when (in-rect? rect)
                            (find-frame child))))
                      (children node))))]
      (find-frame tree'))))