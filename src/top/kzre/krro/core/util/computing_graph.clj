(ns top.kzre.krro.core.util.computing-graph
  "计算图抽象——依赖图求解。

   采用 Kahn 算法：
     - 预计算 reverse-deps（谁依赖我）和 in-degree（我依赖几个）
     - 初始 ready = in-degree 为 0 的节点
     - 每完成一个节点——它 reverse-deps 中每个节点的 in-degree 减 1
     - in-degree 变 0 → 加入 ready
     - 循环直到全部完成

   求解过程用 ploop 驱动——每轮批量并行计算用 promise/all。

   错误定位：任一节点 compute 失败时——异常包装为 ex-info——
   携带 :node-id / :inputs / :cause——精确定位。

   节点抽象：
     - INode 协议——solve / graph 依赖此协议
     - Node record——默认实现——通过 node 构造
     - 高级用户可以定义自己的节点类型——实现 INode 协议即可"
  (:require
    [clojure.set :as set]
    [top.kzre.krro.core.util.promise :as promise :refer [ploop precur]]))

;; ═══════════════════════════════════════════════
;; 节点协议
;; ═══════════════════════════════════════════════

(defprotocol INode
  "计算节点协议——solve / graph 依赖此协议。

   实现者提供：
     - node-id：唯一标识
     - dependents：依赖的 node-id 向量——顺序即 compute inputs 顺序
     - compute：根据 inputs 计算——返回 Promise 或普通值

   默认实现见 Node record。
   高级用户可以定义自己的节点类型——只要实现协议即可。"

  (node-id [_]
    "节点唯一 id——Keyword / String / 任意可比较对象。")

  (dependencies [_]
    "依赖的 node-id 向量——顺序即 compute inputs 顺序。")

  (compute [_ inputs]
    "根据 inputs 计算——inputs 与 dependents 同序。
     返回 Promise<value> 或 value。"))

;; ═══════════════════════════════════════════════
;; 默认实现——Node record
;; ═══════════════════════════════════════════════

(defrecord Node [id deps compute-fn]
  INode
  (node-id    [_] id)
  (dependencies [_] deps)
  (compute    [_ inputs] (compute-fn inputs)))

(defn node
  "构造默认节点。

   - id:         唯一标识——Keyword / String / 任意可比较对象
   - deps:       依赖的 node-id 向量——顺序即 inputs 顺序
   - compute-fn: (fn [inputs] -> value | Promise)
                 inputs 是与 deps 同序的向量

   用法：
     (node :a []      (fn [_]     10))
     (node :b [:a]    (fn [[a]]   (* a 2)))
     (node :c [:a]    (fn [[a]]   (+ a 1)))
     (node :d [:b :c] (fn [[b c]] (+ b c)))"
  [id deps compute-fn]
  (->Node id (vec deps) compute-fn))

;; ═══════════════════════════════════════════════
;; 计算图——预计算 Kahn 所需的索引
;; ═══════════════════════════════════════════════

;; {:nodes        {node-id → INode}
;;  :reverse-deps {node-id → #{依赖它的 node-id}}
;;  :in-degree    {node-id → 依赖数}
;;  :initial-ready #{node-id in-degree 为 0}}
(defrecord ComputingGraph [nodes reverse-deps in-degree initial-ready])

(defn reverse-dependencies
  "计算图节点的反向依赖映射, {node-id → #{依赖它的 node-id}}"
  [^ComputingGraph graph]
  (:reverse-deps graph))

(defn graph
  "从节点集合构建计算图——预计算 Kahn 算法所需索引。

   校验：
     - 节点 id 唯一
     - 所有 dependents 都在图中存在

   预计算：
     - reverse-deps：node-id → 谁依赖我
     - in-degree：node-id → 我依赖几个
     - initial-ready：初始无依赖的节点

   nodes 可以是任意实现 INode 协议的对象。"
  [& nodes]
  (let [nodes   (vec nodes)
        ids     (mapv node-id nodes)
        id-set  (set ids)
        dup-ids (->> ids frequencies
                     (filter #(> (val %) 1))
                     (map key)
                     vec)]
    (when (seq dup-ids)
      (throw (IllegalArgumentException.
               (str "duplicate node ids: " dup-ids))))
    (doseq [n nodes
            dep (dependencies n)]
      (when-not (contains? id-set dep)
        (throw (IllegalArgumentException.
                 (str "node " (node-id n)
                      " depends on unknown node " dep)))))

    (let [nodes-map    (into {} (map (juxt node-id identity)) nodes)
          reverse-deps (reduce
                         (fn [acc n]
                           (reduce (fn [a dep]
                                     (update a dep (fnil conj #{}) (node-id n)))
                                   acc
                                   (dependencies n)))
                         {}
                         nodes)
          in-degree    (into {}
                             (map (fn [n]
                                    [(node-id n) (count (dependencies n))]))
                             nodes)
          initial-ready (into #{}
                              (filter #(zero? (get in-degree %)))
                              ids)]
      (->ComputingGraph nodes-map reverse-deps in-degree initial-ready))))

;; ═══════════════════════════════════════════════
;; 求解——辅助
;; ═══════════════════════════════════════════════

(defn- compute-batch
  "并行计算一批 ready 节点——返回 Promise<{node-id value}>。

   每个节点的 compute 独立——同时执行——all 等待全部完成。
   compute 可以返回 Promise 或普通值——用 ensure-promise 统一。

   错误定位——同步抛和异步 reject 都包装为 ex-info——
   :node-id 标明失败节点——:inputs 记录当时输入——cause 是原始异常。"
  [nodes-map ready-ids results]
  (let [ps (mapv (fn [nid]
                   (let [n      (get nodes-map nid)
                         deps   (dependencies n)
                         inputs (mapv results deps)]
                     (-> (try
                           (promise/ensure-promise (compute n inputs))
                           (catch Throwable t
                             ;; compute-fn 同步抛——转成 rejected Promise
                             (promise/rejected t)))
                         (promise/handle
                           (fn [v e]
                             (if e
                               (throw (ex-info (str "node compute failed: " nid)
                                               {:node-id nid
                                                :deps    deps
                                                :inputs  inputs}
                                               e))
                               [nid v]))))))
                 ready-ids)]
    (-> (promise/all ps)
        (promise/fmap (fn [pairs] (into {} pairs))))))

;; ═══════════════════════════════════════════════
;; 求解——主循环
;; ═══════════════════════════════════════════════

(defn solve
  "求解计算图——返回 Promise<{node-id value}>。

   使用 Kahn 算法——每轮：
     1. 从 ready 集合取一批
     2. 并行计算
     3. 每个完成的节点——它 reverse-deps 的 in-degree 减 1
     4. in-degree 变 0 → 加入下一轮 ready
   直到 remaining 为 0——返回全部结果。

   如果某轮 ready 为空但 remaining 非空——说明存在循环依赖
   （DAG 不允许）——抛 ex-info。

   错误传播：任一节点 compute 失败——整批失败——最终 Promise
   rejected——ex-info 的 :node-id 标明失败节点。"
  [^ComputingGraph graph]
  (let [{:keys [nodes reverse-deps in-degree initial-ready]} graph]
    (ploop [state {:results   {}
                   :in-degree in-degree
                   :ready     initial-ready
                   :remaining (count nodes)}]
           (let [{:keys [results in-degree ready remaining]} state]
             (if (zero? remaining)
               ;; 完成——返回结果
               results
               (if (empty? ready)
                 ;; 卡住——循环依赖
                 (throw (ex-info "computing graph stuck: cycle detected"
                                 {:remaining  remaining
                                  :computed   (set (keys results))
                                  :unresolved (set/difference
                                                (set (keys nodes))
                                                (set (keys results)))}))
                 ;; 并行计算这一批 ready——递归
                 (-> (compute-batch nodes ready results)
                     (promise/fmap
                       (fn [new-results]
                         (let [completed-ids (keys new-results)
                               ;; 每个完成的节点——reverse-deps 的 in-degree 减 1
                               in-degree'   (reduce
                                              (fn [acc nid]
                                                (reduce (fn [a rnid]
                                                          (update a rnid dec))
                                                        acc
                                                        (get reverse-deps nid #{})))
                                              in-degree
                                              completed-ids)
                               ;; in-degree 变 0 的节点——加入 ready
                               newly-ready  (into #{}
                                                  (filter #(zero? (get in-degree' %)))
                                                  (mapcat #(get reverse-deps % #{})
                                                          completed-ids))]
                           (precur
                             {:results   (merge results new-results)
                              :in-degree in-degree'
                              :ready     newly-ready
                              :remaining (- remaining (count completed-ids))})))))))))))




;; ═══════════════════════════════════════════════
;; 便捷——同步等待
;; ═══════════════════════════════════════════════

(defn solve!
  "同步求解——阻塞等待结果。
   只在非 UI 线程使用。"
  [^ComputingGraph graph]
  (promise/await (solve graph)))

