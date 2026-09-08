(ns top.kzre.krro.core.window-layout
  (:require [clojure.spec.alpha :as s]))

(defonce directions #{:horizontal :vertical})
(s/def ::frame-id keyword?)
(s/def ::direction directions)

(s/def ::ratio (s/and number? #(> % 0)))
(s/def ::ratios (s/coll-of ::ratio :kind vector? :min-count 1))

;; props-map 可选包含 :ratios
(s/def ::props (s/keys :opt-un [::ratio ::ratios]))

;; 布局递归 spec
;; 叶子节点：[:frame-id]
;; 分割节点：[direction props-map child1 child2]
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
  "创建一个分割节点（代表一个分割容器）。
   参数:
     - direction: :horizontal 或 :vertical
     - props:     属性 map（必须提供，可为空 {}），可包含 :ratios 等
     - children:  一个或多个子节点（叶子或分割节点）
   返回: 分割节点向量，格式为 [direction props child1 child2 ...]"
  [direction left right & {:as props}]
  [direction props left right])

(defn leaf? [node]
  (and (vector? node)
       (let [kw (first node)]
         (and (keyword? kw)
              (not (contains? directions kw)))) ))

(defn split? [node]
  (and (vector? node) (contains? #{:horizontal :vertical} (first node))))

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

(defn avenge-ratios [tree]
  (when-let [cs (children tree)]
    (let [cnt (count cs)
          ratio (/ 1 cnt)]
      (when (> cnt 0)
        (vec (repeat cnt ratio))))))

(defn get-ratios [tree]
  (or
    (when-let [ratio (get-prop tree :ratio)]
      [ratio (- 1 ratio)])
    (get-prop tree :ratios)
    (avenge-ratios tree)))

(defn prewalk
  ([tree f] (prewalk tree f false))
  ([tree f reversed?]
   (if (leaf? tree)
     (f tree)
     (let [[direction props & children] (f tree )
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
        ;; 无子节点删除自己，虽然不会有这种情况
        0 nil
        ;; 单个子节点合并层级
        1 (first new-children)
        (into [direction props] new-children)))))

(defn empty-layout? [tree]
  (or (nil? tree)
      (empty? tree)))

(defn get-aabb [tree]
  (get-prop tree :aabb))


;; 提取公共函数：计算父节点下指定索引子节点的矩形
(defn child-rect
  "计算父节点中指定索引子节点的标准化 AABB 矩形。

   参数:
     - parent-rect: 父节点的矩形，格式 {:min-x, :max-x, :min-y, :max-y} (归一化坐标)
     - dir: 分割方向，:horizontal 或 :vertical
     - ratios: 子节点的分割比例向量，长度等于子节点数，元素在 (0,1) 之间且累加为 1
     - child-idx: 目标子节点的索引 (0-based)

   返回值: 子节点的矩形 map，与 parent-rect 格式相同。"
  [parent-rect dir ratios child-idx]
  (let [total (if (= dir :horizontal)
                (- (:max-x parent-rect) (:min-x parent-rect))
                (- (:max-y parent-rect) (:min-y parent-rect)))
        start (if (= dir :horizontal) (:min-x parent-rect) (:min-y parent-rect))
        prev-sum (reduce + (take child-idx ratios))
        cur-start (+ start (* total prev-sum))
        cur-end   (+ start (* total (reduce + (take (inc child-idx) ratios))))]
    (if (= dir :horizontal)
      (assoc parent-rect :min-x cur-start :max-x cur-end)
      (assoc parent-rect :min-y cur-start :max-y cur-end))))

(defn aabb-caching?
  "判定节点是否已缓存了 aabb (即 props 中包含 :aabb 键)。只有分割节点可能缓存。"
  [node]
  (not (nil? (get (props node) :aabb))))

;; 更新 recompute-aabbs 以复用 child-rect
(defn recompute-aabbs
  "为树的每个节点计算aabb包围盒子，附加在 :props :aabb 上"
  [tree]
  (letfn [(recompute [node rect]
            (if (leaf? node)
              (let [new-props    (assoc (props node) :aabb rect)]
                [(frame-id node) new-props])
              (let [dir       (split-direction node)
                    children  (children node)
                    ratios    (get-ratios node)
                    cnt       (count children)
                    child-rects (mapv #(child-rect rect dir ratios %) (range cnt))
                    new-children (mapv recompute children child-rects)
                    new-props    (assoc (props node) :aabb rect)]
                (into [(split-direction node) new-props] new-children))))]
    (recompute tree {:min-x 0 :max-x 1 :min-y 0 :max-y 1})))

(defn ensure-aabb-caching [tree]
  (if (aabb-caching? tree)
    tree
    (recompute-aabbs tree)))

;; 利用预计算 aabb 的 frame-aabb 实现
(defn frame-aabb
  "返回frame占据的标准化 aabb {:keys [min-x min-y max-x max-y]}"
  [tree frame-id]
  (letfn [(find-aabb [node rect]
            (cond
              (leaf? node)
              (when (= (frame-id node) frame-id) rect)

              (split? node)
              (let [children  (children node)
                    cnt       (count children)]
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
  "返回所有叶子节点的 [frame-id aabb] 列表"
  [tree]
  (let [tree' (ensure-aabb-caching tree)
        all-ids (all-frames tree)]
    (into {} (mapv (fn [id] [id (frame-aabb tree' id)]) all-ids))))

(defn navigate
  "从当前 frame 沿方向 dir 移动到相邻 frame。
   返回目标 frame-id。若 wrap? 为 true，支持回绕；否则若没有相邻 frame 则返回当前 frame-id。
   point-in-frame 为当前 frame 内的归一化坐标，用作射线起点。"
  [tree current-frame-id dir
   & {:keys [point-in-frame wrap?]
      :or {point-in-frame {:x 0.5 :y 0.5}
           wrap? true}}]
  (let [aabb-map (frame-aabbs tree)
        cur-rect (get aabb-map current-frame-id)
        px (+ (:min-x cur-rect) (* (- (:max-x cur-rect) (:min-x cur-rect)) (:x point-in-frame)))
        py (+ (:min-y cur-rect) (* (- (:max-y cur-rect) (:min-y cur-rect)) (:y point-in-frame)))
        eps 1e-9

        ;; aabb 轴向包含检查
        candidates (filter
                     (fn [[id rect]]
                       (when-not (= id current-frame-id)
                         (case dir
                           (:left :right)  (<= (- (:min-y rect) eps) py (+ eps (:max-y rect)))
                           (:up :down)   (<= (- (:min-x rect) eps) px (+ eps (:max-x rect))))))
                     aabb-map)

        ;; 计算轴距离, 沿着方向越远越大，反之逆着方向走是负值
        dist-fn (fn [[_id rect]]
                  (case dir
                    :left  (- px (:max-x rect))
                    :right (- (:min-x rect) px)
                    :up    (- py (:max-y rect))
                    :down  (- (:min-y rect) py)))]

    (when (seq candidates)
      (or (when-let [[frame-id dist] (apply min-key dist-fn candidates)]
            ;; 该点不在反方向
            (when-not (< dist eps) frame-id))
          (when wrap? (first (apply max-key dist-fn candidates)))))))