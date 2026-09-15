(ns top.kzre.krro.core.util.diff
  "差分计算——把旧值表差分为新值表。

   ═══════════════════════════════════════════════
   核心模型
   ═══════════════════════════════════════════════

   顶层入口 diff：

     旧值表 ──diff──> 新值表

   处理一次变化：
     - 未受影响的节点——旧值引用不变——直接搬到新值表
     - 受影响的节点——调用 migrate（协议方法）——从旧值迁移出新值
     - 新增节点——从 nil 迁移
     - 删除节点——从新值表移除——调用 release 释放

   旧值表完全不动——返回全新的新值表。

   ═══════════════════════════════════════════════
   graph 的结构
   ═══════════════════════════════════════════════

   graph 是一个 map，含三个字段：

     {:nodes    {node-id → node-config}      节点配置——diff 不解释内容
      :outgoing {node-id → #{node-id}}       传播边——谁受我影响——无序
      :inputs   {node-id → [node-id ...]}}   输入边——我依赖谁——有序

   :outgoing 和 :inputs 互为镜像：
     若 :b 依赖 :a——则 :inputs[:b] 含 :a、:outgoing[:a] 含 :b
   构建时反向一次——求解时 O(1) 查询。

   ═══════════════════════════════════════════════
   分层
   ═══════════════════════════════════════════════

   底层——闭包传播
     propagate: (种子, outgoing) → 受影响集合

   中层——值迁移
     Change:    变化描述——种子 + 组合 + 单位元
     Diff:      差分规格——传播方向 + 顺序 + 输入 + migrate + release
     diff:      顶层入口——分类 → 种子 → 闭包 → 迁移 → 合并

   ═══════════════════════════════════════════════
   不负责的
   ═══════════════════════════════════════════════

   结构变化的具体语义——由各领域上层负责：

     - vdom:    重新 render 得新树 → 新旧对比 → 构建新 graph
     - 计算图:  操作日志 → 图重建 → 构建新 graph

   本命名空间只做：结构确定后的「值迁移」。

   调用方给出：
     - 新 graph（增删已在 graph 里体现）
     - 旧值表（可能含已删除节点的旧值）
     - change（值变化的种子）

   diff 自动管理：
     - graph 有、旧值表没有 → 新增——从 nil 迁移
     - 旧值表有、graph 没有 → 删除——从新值表移除——调 release
     - 两边都有 → 保留——按 change 判断是否受影响

   ═══════════════════════════════════════════════
   数学基础
   ═══════════════════════════════════════════════

   propagate 实现「闭包算子」：
     外延性、单调性、幂等性。

   diff 实现「离散导数」：
     已知 f、输入变化 δa——求输出变化 δb。
     在 DAG 上——沿依赖边求闭包——对受影响节点做值迁移。"
  (:require
    [clojure.set :as set]))

;; ═══════════════════════════════════════════════
;; 底层——闭包传播
;; ═══════════════════════════════════════════════

(defn propagate
  "从种子集合出发，沿 outgoing 求可达闭包。

   <h2>性质</h2>
   满足闭包算子三条性质：
     1. 外延性——结果 ⊇ 种子
     2. 单调性——种子更大 → 结果更大
     3. 幂等性——再次传播不产生新节点

   <h2>实现</h2>
   BFS——每个节点最多访问一次——O(V + E)。

   <h2>参数</h2>
   - seeds:    起始节点集合
   - outgoing: (node-id) → 邻居 id 集合

   <h2>返回</h2>
   可达闭包——包含种子自身。

   <h2>纯函数</h2>
   不修改任何输入。"
  [seeds outgoing]
  (loop [visited  #{}
         frontier (set seeds)]
    (if (empty? frontier)
      visited
      (let [next-nodes (into #{}
                             (comp (mapcat outgoing)
                                   (remove visited))
                             frontier)]
        (recur (into visited frontier) next-nodes)))))

;; ═══════════════════════════════════════════════
;; Change —— 值变化描述
;; ═══════════════════════════════════════════════

(defprotocol IChange
  "值变化描述——diff 的输入。

   一次变化描述「哪些节点的值变了」——以节点 id 的形式。
   Change 假设节点存在；删除节点属于结构变化——
   由上层处理——不在本协议职责内。

   <h2>契约</h2>

   <b>seeds</b>
     返回种子集合——传播起点。
     空集表示无变化——diff 应直接返回旧值表。

   <b>combine</b>
     合并两个变化——返回新变化。
     必须满足结合律：(combine (combine a b) c) = (combine a (combine b c))
     应满足交换律（同一类型）。
     实现通常取并集。

   <b>empty-change?</b>
     判断是否为空变化。
     empty-change? 为真时——diff 不做任何迁移。

   <h2>纯函数</h2>
   实现必须纯——不修改自身——combine 返回新变化。"

  (seeds [change]
    "返回种子集合——传播起点。空集表示无变化。")

  (combine [change other]
    "合并两个变化——返回新变化。必须满足结合律。")

  (empty-change? [change]
    "返回 true 表示空变化——不触发任何迁移。"))

;; ─────────────────────────────────────────────
;; 默认实现——基于集合
;; ─────────────────────────────────────────────

(defrecord SetChange [seed-set]
  IChange
  (seeds         [_] seed-set)
  (combine       [_ other]
    (->SetChange (into seed-set (seeds other))))
  (empty-change? [_] (empty? seed-set)))

(defn set-change
  "从 id 集合构造变化描述。"
  [ids]
  (->SetChange (set ids)))

(defn no-change
  "空变化——不触发任何迁移。"
  []
  (->SetChange #{}))

;; ═══════════════════════════════════════════════
;; Diff —— 差分规格
;; ═══════════════════════════════════════════════

(defprotocol IDiff
  "差分规格——描述如何在给定图上传播和迁移。

   一个 Diff 实例定义「对某类图，如何应用某类值变化」。
   它是 diff 的算法参数——无状态。

   <h2>契约</h2>

   <b>outgoing</b>
     给定图和节点 id——返回「受此节点影响的邻居 id 集合」。
     这是传播方向的唯一定义：
       - 渲染调度——返回「谁依赖我」——向下传播
       - vdom——返回「我所在的父组件」——向上传播
     必须满足：若 b 依赖 a——则 outgoing(graph, a) 包含 b。

   <b>order</b>
     给定图和受影响 id 集合——返回处理顺序。
     必须满足拓扑序约束：
       若 b 依赖 a 且 a、b 都在集合中——a 必须排在 b 前。

   <b>inputs</b>
     给定图和节点 id——返回「该节点的输入节点 id 序列」。
     顺序语义由实现定义——通常与 migrate 的 input-values 顺序一致。

   <b>migrate</b>
     给定图、旧值、节点 id、输入值序列——返回新值。
     - <b>旧值是一等公民</b>——迁移可以从旧值出发——
       比如 vdom 复用子树、画布增量更新——不是「从零算」
     - old-value 为 nil 表示新增节点——迁移函数应能处理
     - 纯函数——不修改图、不修改旧值
     - 可返回普通值或 Promise
     - 抛异常表示该节点迁移失败——diff 传播异常

   <b>release</b>
     释放一个被删除节点的旧值。
     - 用于资源回收——比如画布释放、vdom 引用解除
     - 默认 no-op——不需要资源的领域可以不实现
     - 纯副作用——返回值被忽略

   <h2>纯函数</h2>
   order / inputs 只读图——migrate 不修改图、不修改旧值——
   release 只执行副作用、不返回有意义的"

  (outgoing [diff graph node-id]
    "从 node-id 出发的传播边——返回受影响邻居 id 集合。定义传播方向。")

  (order [diff graph affected-ids]
    "返回受影响集合的处理顺序——必须满足拓扑序约束。")

  (inputs [diff graph node-id]
    "返回节点的输入 id 序列——migrate 的 input-values 参数。")

  (migrate [diff graph old-value node-id input-values]
    "把一个节点的旧值迁移为新值。

     参数：
       - diff:        本规格
       - graph:       图——只读
       - old-value:   旧值——nil 表示新增节点
       - node-id:     节点标识——用于从 graph 查配置
       - input-values: 输入节点的当前值序列

     返回：新值或 Promise。

     契约：
       - 纯函数——不修改 graph 和 old-value
       - old-value 为 nil 表示新增节点
       - 抛异常表示迁移失败")

  (release [diff graph old-value]
    "释放一个被删除节点的旧值。
     默认 no-op。
     纯副作用——返回值被忽略。"))

;; ─────────────────────────────────────────────
;; 默认实现——四函数 + 可选 release
;; ─────────────────────────────────────────────

(defrecord FnDiff [outgoing-fn order-fn inputs-fn migrate-fn release-fn]
  IDiff
  (outgoing [_ graph node-id]              (outgoing-fn graph node-id))
  (order    [_ graph affected-ids]         (order-fn graph affected-ids))
  (inputs   [_ graph node-id]              (inputs-fn graph node-id))
  (migrate  [_ graph old-value node-id ins] (migrate-fn graph old-value node-id ins))
  (release  [_ graph old-value]            (when release-fn (release-fn old-value))))

(defn fn-diff
  "用四个函数构造 Diff。
   release-fn 可选——为 nil 时 release 是 no-op。

   - outgoing-fn: (graph, node-id) → 邻居 id 集合
   - order-fn:    (graph, affected-ids) → 有序序列
   - inputs-fn:   (graph, node-id) → 输入 id 序列
   - migrate-fn:  (graph, old-value, node-id, input-values) → 值 | Promise
   - release-fn:  (old-value) → 无返回值——可选"
  ([outgoing-fn order-fn inputs-fn migrate-fn]
   (->FnDiff outgoing-fn order-fn inputs-fn migrate-fn nil))
  ([outgoing-fn order-fn inputs-fn migrate-fn release-fn]
   (->FnDiff outgoing-fn order-fn inputs-fn migrate-fn release-fn)))

;; ═══════════════════════════════════════════════
;; 顶层——diff
;; ═══════════════════════════════════════════════

(defn- affected-set
  "计算受影响集合——闭包传播。内部使用。"
  [diff-spec graph change added-ids]
  (let [seed-set (into (set (seeds change)) added-ids)]
    (propagate seed-set #(outgoing diff-spec graph %))))

(defn diff
  "把旧值表差分为新值表。

   <h2>自动管理增删</h2>
   不做任何「用户预先剪除 graph 或 old-values」的要求：
     - graph 有、old-values 没有 → 新增——从 nil 迁移——作为种子
     - old-values 有、graph 没有 → 删除——从新值表移除——调 release
     - 两边都有 → 保留——按 change 判断是否受影响

   <h2>四步流程</h2>
   1. 分类：新增 / 删除 / 保留

   2. 种子：change 的种子 ∪ 新增节点

   3. 传播：闭包求受影响集合

   4. 迁移：按拓扑序——对受影响节点调 migrate
      释放：对删除节点调 release

   <h2>参数</h2>
   - diff-spec:  Diff 实例
   - graph:      图——新结构——含 :nodes :outgoing :inputs
   - change:     Change 实例
   - old-values: 旧值表——{node-id → value}

   <h2>返回</h2>
   新值表——{node-id → value}。

   <b>关键语义</b>：
     - 未受影响的节点——新值表中是旧值表里的<b>同一个引用</b>
     - 受影响的节点——值被 migrate 重新算出
     - 新增节点——值是 migrate 从 nil 迁移的结果
     - 删除节点——不出现在新值表——旧值被 release
     - <b>旧值表完全不动</b>——新值表是全新对象

   <h2>空变化 + 无增删</h2>
   change 为空且无增删——直接返回 old-values——零开销。

   <h2>错误</h2>
   migrate 抛异常时——整体立即失败。

   <h2>纯函数</h2>
   diff 不修改 graph 和 old-values——
   除了对删除节点调 release（副作用）——返回新值表。

   用法：
     (diff diff-spec new-graph change old-values)
     ;; → 新值表

     ;; 调用方拿到新值表后——自行决定怎么用
     ;; 旧值表中的值仍然有效——由调用方决定何时释放"
  [diff-spec graph change old-values]
  (let [graph-ids (set (keys (:nodes graph)))
        old-ids   (set (keys old-values))
        added     (set/difference graph-ids old-ids)
        removed   (set/difference old-ids graph-ids)
        kept      (set/intersection graph-ids old-ids)]
    (if (and (empty-change? change)
             (empty? added)
             (empty? removed))
      old-values
      (let [;; 未受影响的保留——旧值引用不变
            kept-vals (select-keys old-values kept)

            ;; 受影响集合——种子 + 新增 → 闭包
            affected  (affected-set diff-spec graph change added)

            ;; 拓扑序
            eval-seq  (order diff-spec graph affected)

            ;; 逐个迁移——依赖先算
            new-vals  (reduce
                        (fn [vals node-id]
                          (let [ins     (inputs diff-spec graph node-id)
                                invs    (mapv #(get vals %) ins)
                                old-val (get old-values node-id)   ; 原始旧值
                                v       (migrate diff-spec graph old-val node-id invs)]
                            (assoc vals node-id v)))
                        kept-vals
                        eval-seq)]

        ;; 释放被删除节点的旧值
        (doseq [node-id removed]
          (release diff-spec graph (get old-values node-id)))

        new-vals))))