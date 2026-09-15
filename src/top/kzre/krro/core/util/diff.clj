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
     IChange:   变化描述——种子 + 合并 + 空判定
     IDiff:     差分规格——传播方向 + 顺序 + 输入 + migrate + release
     diff:      顶层入口——分类 → 传播 → 拓扑序 → 迁移 → 释放

   ═══════════════════════════════════════════════
   多变化传播
   ═══════════════════════════════════════════════

   diff 接受多个 IChange——每个独立从自己的种子出发传播。
   多个变化在同一节点交汇——调用 combine 尝试合并：
     - 返回新变化  → 用合并结果继续向下游
     - 返回空变化  → 抵消——不向下游传播
     - 返回 nil    → 不可合并——两者都保留，各自传播

   节点处的 migrate 收到到达该节点的变化集合——
   据此决定走哪条迁移路径。

   ═══════════════════════════════════════════════
   零开销路径
   ═══════════════════════════════════════════════

   两道短路——都返回 old-values 原引用：
     1. 输入无 active-changes 且无增删
     2. 传播后 affected 为空（变化互相抵消）且无删除

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
     - changes（值变化的集合）

   diff 自动管理：
     - graph 有、旧值表没有 → 新增——从 nil 迁移
     - 旧值表有、graph 没有 → 删除——从新值表移除——调 release
     - 两边都有 → 保留——按 changes 判断是否受影响

   ═══════════════════════════════════════════════
   数学基础
   ═══════════════════════════════════════════════

   propagate 实现「闭包算子」：
     外延性、单调性、幂等性。

   combine 实现「变化代数」：
     结合律——合并顺序无关；
     存在空元素——无变化；
     nil 表示不可合并——保持两者独立。

   diff 实现「离散导数」：
     已知 f、输入变化 δa——求输出变化 δb。
     在 DAG 上——沿依赖边传播——对受影响节点做值迁移。"
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
     空集表示无变化。

   <b>combine</b>
     合并两个变化——返回新变化、空变化、或 nil。
     必须满足结合律。
     nil 表示「无法和这个变化合并」——两者都保留，各自传播。

   <b>empty-change?</b>
     判断是否为空变化。
     为真时——该变化被丢弃，不触发任何迁移。

   <h2>纯函数</h2>
   实现必须纯——不修改自身——combine 返回新变化。"

  (seeds [change]
    "返回种子集合——传播起点。空集表示无变化。")

  (combine [change other]
    "合并两个变化——返回新变化、空变化、或 nil。
     必须满足结合律。
     nil 表示无法和这个变化合并。")

  (empty-change? [change]
    "返回 true 表示空变化——不触发任何迁移。"))

;; ─────────────────────────────────────────────
;; 默认实现——基于集合
;; ─────────────────────────────────────────────

(defrecord SetChange [seed-set]
  IChange
  (seeds         [_] seed-set)
  (combine       [_ other]
    (if (instance? SetChange other)
      (->SetChange (into seed-set (:seed-set other)))
      nil))
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
     这是传播方向的唯一定义。
     必须满足：若 b 依赖 a——则 outgoing(graph, a) 包含 b。

   <b>order</b>
     给定图和受影响 id 集合——返回处理顺序。
     必须满足拓扑序约束：
       若 b 依赖 a 且 a、b 都在集合中——a 必须排在 b 前。

   <b>inputs</b>
     给定图和节点 id——返回「该节点的输入节点 id 序列」。
     顺序语义由实现定义——通常与 migrate 的 input-values 顺序一致。

   <b>migrate</b>
     给定图、节点 id、到达的变化集合、旧值、输入值序列——返回新值。
     - 旧值是一等公民——迁移可以从旧值出发
     - changes 是到达本节点的变化集合——可能为空（新增节点）
     - old-value 为 nil 表示新增节点——迁移函数应能处理
     - 纯函数——不修改图、不修改旧值
     - 可返回普通值或 Promise
     - 抛异常表示该节点迁移失败——diff 传播异常

   <b>release</b>
     释放一个被删除节点的旧值。
     - 用于资源回收
     - 默认 no-op
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

  (migrate [diff graph node-id changes old-value input-values]
    "把一个节点的旧值迁移为新值。

     参数：
       - diff:        本规格
       - graph:       图——只读
       - node-id:     节点标识——定位「在哪个节点」
       - changes:     到达本节点的变化集合——「为什么变」
       - old-value:   旧值——nil 表示新增节点
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
  (outgoing [_ graph node-id]                    (outgoing-fn graph node-id))
  (order    [_ graph affected-ids]               (order-fn graph affected-ids))
  (inputs   [_ graph node-id]                    (inputs-fn graph node-id))
  (migrate  [_ graph node-id changes old-value ins]
    (migrate-fn graph node-id changes old-value ins))
  (release  [_ graph old-value]
    (when release-fn (release-fn old-value))))

(defn fn-diff
  "用四个函数构造 Diff。
   release-fn 可选——为 nil 时 release 是 no-op。

   - outgoing-fn: (graph, node-id) → 邻居 id 集合
   - order-fn:    (graph, affected-ids) → 有序序列
   - inputs-fn:   (graph, node-id) → 输入 id 序列
   - migrate-fn:  (graph, node-id, changes, old-value, input-values) → 值 | Promise
   - release-fn:  (old-value) → 无返回值——可选"
  ([outgoing-fn order-fn inputs-fn migrate-fn]
   (->FnDiff outgoing-fn order-fn inputs-fn migrate-fn nil))
  ([outgoing-fn order-fn inputs-fn migrate-fn release-fn]
   (->FnDiff outgoing-fn order-fn inputs-fn migrate-fn release-fn)))

;; ═══════════════════════════════════════════════
;; 传播——内部
;; ═══════════════════════════════════════════════

(defn- pairwise-merge
  "对一组 changes 做 pairwise combine——反复直到不动点。
   返回合并后的集合（已去掉空变化）。"
  [cs]
  (loop [cs (into #{} (remove empty-change?) cs)]
    (let [v     (vec cs)
          pairs (for [i (range (count v))
                      j (range (inc i) (count v))]
                  [(nth v i) (nth v j)])
          [cs' changed?]
          (reduce
            (fn [[acc changed?] [a b]]
              (if (and (contains? acc a) (contains? acc b))
                (if-let [c (combine a b)]
                  (let [acc' (disj (disj acc a) b)
                        acc' (if (empty-change? c) acc' (conj acc' c))]
                    [acc' true])
                  [acc changed?])
                [acc changed?]))
            [cs false]
            pairs)]
      (if changed?
        (recur cs')
        cs))))

(defn- propagate-changes
  "从初始 state 出发，沿 outgoing 传播 changes。
   state: {node-id → #{change}}
   每轮对 worklist 中的节点做 pairwise 合并；
   无条件把合并结果推到下游——下游集合变化才入 worklist。
   空集合不传播——这是「抵消截断」的实现点。"
  [diff-spec graph init-state]
  (loop [state    (into {} (map (fn [[k v]] [k (set v)])) init-state)
         worklist (set (keys init-state))]
    (if (empty? worklist)
      state
      (let [nid       (first worklist)
            worklist  (disj worklist nid)
            cs-now    (get state nid #{})
            cs-merged (pairwise-merge cs-now)
            state'    (assoc state nid cs-merged)
            [state'' worklist']
            (reduce
              (fn [[st wl] succ]
                (let [succ-old (get st succ #{})
                      succ-new (into succ-old cs-merged)]
                  (if (= succ-old succ-new)
                    [st wl]
                    [(assoc st succ succ-new) (conj wl succ)])))
              [state' worklist]
              (if (seq cs-merged)
                (outgoing diff-spec graph nid)
                []))]
        (recur state'' worklist')))))

;; ═══════════════════════════════════════════════
;; 顶层——diff
;; ═══════════════════════════════════════════════

(defn diff
  "把旧值表差分为新值表。

   <h2>自动管理增删</h2>
     - graph 有、old-values 没有 → 新增——从 nil 迁移
     - old-values 有、graph 没有 → 删除——移除——调 release
     - 两边都有 → 保留——按 changes 判断是否受影响

   <h2>多 change 传播</h2>
   每个 change 独立从自己的种子出发传播。
   多个 change 在同一节点交汇——调用 combine 尝试合并。

   <h2>参数</h2>
   - diff-spec:  Diff 实例
   - graph:      图——新结构——含 :nodes :outgoing :inputs
   - changes:    IChange 集合
   - old-values: 旧值表——{node-id → value}

   <h2>返回</h2>
   新值表——{node-id → value}。

   <b>关键语义</b>：
     - 未受影响的节点——新值表中是旧值表里的同一个引用
     - 受影响的节点——值被 migrate 重新算出
     - 新增节点——值是 migrate 从 nil 迁移的结果
     - 删除节点——不出现在新值表——旧值被 release
     - 旧值表完全不动——新值表是全新对象

   <h2>零开销路径</h2>
   两道短路——都返回 old-values 原引用：
     1. 输入无 active-changes 且无增删
     2. 传播后 affected 为空（变化互相抵消）且无删除

   用法：
     (diff diff-spec new-graph changes old-values)
     ;; → 新值表"
  [diff-spec graph changes old-values]
  (let [active-changes (into #{} (remove empty-change?) changes)
        graph-ids      (set (keys (:nodes graph)))
        old-ids        (set (keys old-values))
        added          (set/difference graph-ids old-ids)
        removed        (set/difference old-ids graph-ids)
        kept           (set/intersection graph-ids old-ids)]
    (if (and (empty? active-changes)
             (empty? added)
             (empty? removed))
      ;; 第一道短路——输入无变化、无增删
      old-values

      (let [init-state
            (reduce
              (fn [st change]
                (reduce (fn [st nid]
                          (update st nid (fnil conj #{}) change))
                        st
                        (seeds change)))
              {}
              active-changes)

            final-state (propagate-changes diff-spec graph init-state)

            affected    (into added
                              (filter #(seq (get final-state %)))
                              (keys final-state))]

        (if (and (empty? affected)
                 (empty? removed))
          ;; 第二道短路——净效果为空（抵消、或全部被合并掉）
          old-values

          (let [eval-seq  (order diff-spec graph affected)
                kept-vals (select-keys old-values kept)
                new-vals  (reduce
                            (fn [vals node-id]
                              (let [ins          (inputs diff-spec graph node-id)
                                    invs         (mapv #(get vals %) ins)
                                    old-val      (get old-values node-id)
                                    node-changes (get final-state node-id #{})
                                    v            (migrate diff-spec graph
                                                          node-id
                                                          node-changes
                                                          old-val
                                                          invs)]
                                (assoc vals node-id v)))
                            kept-vals
                            eval-seq)]
            (doseq [node-id removed]
              (release diff-spec graph (get old-values node-id)))
            new-vals))))))