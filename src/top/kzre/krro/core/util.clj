(ns top.kzre.krro.core.util)

(defn take-padded
  "从 coll 中取前 n 个元素，不足部分用 default-value（默认 nil）补齐。
   等价于 (take n (concat coll (repeat default-value)))。
   常用于将旧集合对齐到新集合的长度。"
  [n coll & [default-value]]
  (take n (concat coll (repeat default-value))))

(defn merge-deep
  "深度合并多个 map。对于嵌套 map 会递归合并，否则后者的值覆盖前者。
   用法与 merge 相同，但会递归合并嵌套的 map。
   若没有提供任何参数，返回空 map。"
  ([] {})
  ([m1] (or m1 {}))
  ([m1 m2 & more]
   (let [merged (merge-with (fn [v1 v2]
                              (if (and (map? v1) (map? v2))
                                (merge-deep v1 v2)
                                v2))
                            (or m1 {}) (or m2 {}))]
     (if more
       (apply merge-deep merged more)
       merged))))


(defn topo-sort
  "对集合 coll 中的元素进行拓扑排序，依赖关系由 deps-select-fn 提供。
   参数：
     - deps-select-fn: (fn [item] ...) 返回 item 所依赖的其他元素集合（向量或列表）
     - coll: 需要排序的元素集合（向量或列表）
     - fast-fail?: 布尔值，默认为 true。若为 true，检测到第一个环后立即停止并只返回该环；若为 false，则收集所有环。
   返回 map：
     - :sorted          排序后的向量（若存在环则返回原集合）
     - :circular?       布尔值，true 表示存在循环依赖
     - :circular-chains 循环依赖路径的向量，每个路径为一个节点序列（向量）"
  [deps-select-fn coll & {:keys [fast-fail?]
                          :or {fast-fail? true}}]
  (let [nodes (vec coll)
        node-set (set nodes)
        state (atom {})          ; 0=unvisited, 1=visiting, 2=visited
        sorted (atom [])
        cycles (atom [])
        visiting-stack (atom [])
        stop? (atom false)]
    (letfn [(find-cycles [node]
              (when (and (not @stop?) (not (contains? @state node)))
                (swap! state assoc node 1)
                (swap! visiting-stack conj node)

                (doseq [dep (deps-select-fn node)
                        :when (and (not @stop?) (contains? node-set dep))]
                  (let [s (get @state dep)]
                    (cond
                      (= s 1) ; 发现环
                      (let [start-idx (.indexOf @visiting-stack dep)
                            cycle (subvec (vec @visiting-stack) start-idx)]
                        (swap! cycles conj cycle)
                        (when fast-fail?
                          (reset! stop? true)))

                      (nil? s)
                      (find-cycles dep)

                      :else nil)))

                (when-not @stop?
                  (swap! state assoc node 2)
                  (swap! visiting-stack pop)
                  (swap! sorted conj node))))]

      (doseq [node nodes]
        (when (and (not @stop?) (not (contains? @state node)))
          (find-cycles node)))

      (if (empty? @cycles)
        {:sorted @sorted
         :circular? false
         :circular-chains []}
        {:sorted nodes
         :circular? true
         :circular-chains (if fast-fail?
                            [(first @cycles)]
                            (distinct @cycles))}))))