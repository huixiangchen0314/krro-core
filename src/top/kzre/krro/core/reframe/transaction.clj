(ns top.kzre.krro.core.reframe.transaction
  "reframe 事务插件")

(defn begin-transaction
  "构造事务创建指令"
  [trans-kind & {:as args}]
  [:begin-transaction trans-kind args])

(defn commit-transaction
  "构造事务提交指令"
  [trans-kind & {:as args}]
  [:commit-transaction trans-kind args])

(defn rollback-transaction
  "构造事务回滚指令"
  [trans-kind & {:as args}]
  [:rollback-transaction trans-kind args])

(defn transaction-operation
  "构建事务操作指令"
  [trans-kind op-kind & {:as args}]
  [:transaction-operation trans-kind op-kind args])

(defprotocol ITransaction
  (begin [_ kwargs record])
  (operate [_ kwargs record])
  (commit [_ kwargs record])
  (rollback [_ kwargs record]))

(defonce ^:private transaction-registry (atom {}))

(defn transactions []
  @transaction-registry)

(defn transaction [trans-kind]
  (get (transactions) trans-kind))

(defn reg-transaction [kind trans]
  {:pre [(keyword? kind)
         (satisfies? ITransaction trans)]}
  (swap! transaction-registry assoc kind trans))

(defonce ^:private transaction-key* ::transaction)

(defn transaction-key [] transaction-key*)

(defn- execute-instruction
  "执行一条事务指令——更新事务状态——返回结果。

   ═══════════════════════════════════════════════
   指令形态
   ═══════════════════════════════════════════════

     [:begin-transaction    kind {args}]
     [:transaction-operation kind op-kind {args}]
     [:commit-transaction   kind {args}]
     [:rollback-transaction kind {args}]

   ═══════════════════════════════════════════════
   返回
   ═══════════════════════════════════════════════

     {:new-transaction <事务状态或 nil>
      :result          {:record <db 片段>
                        :fx     [fx ...]}}

   ═══════════════════════════════════════════════
   事务状态
   ═══════════════════════════════════════════════

     {:kind     <事务类型>
      :instance <ITransaction 实例>}
     或 nil（无活跃事务）"
  [transaction instruction-v record]
  (let [[tag & args] instruction-v]
    (case tag

      ;; ═══════════════════════════════════════════
      ;; 开始事务
      ;; ═══════════════════════════════════════════
      :begin-transaction
      (let [[kind kwargs] args]
        (when transaction
          (throw (ex-info "transaction already active"
                          {:active     (:kind transaction)
                           :attempting kind})))
        (when-not (keyword? kind)
          (throw (ex-info "invalid transaction kind"
                          {:kind kind})))
        (let [t (or (transaction kind)
                    (throw (ex-info "unknown transaction kind"
                                    {:kind kind})))
              result (begin t kwargs record)]
          {:new-transaction {:kind kind :instance t}
           :result          (or result {:fx []})}))

      ;; ═══════════════════════════════════════════
      ;; 事务内操作
      ;; ═══════════════════════════════════════════
      :transaction-operation
      (let [[kind op-kind kwargs] args]
        (when-not transaction
          (throw (ex-info "no active transaction"
                          {:operation op-kind})))
        (when-not (= kind (:kind transaction))
          (throw (ex-info "transaction kind mismatch"
                          {:active     (:kind transaction)
                           :attempting kind})))
        (let [result (operate (:instance transaction)
                              (assoc kwargs :op-kind op-kind)
                              record)]
          {:new-transaction transaction
           :result          (or result {:fx []})}))

      ;; ═══════════════════════════════════════════
      ;; 提交
      ;; ═══════════════════════════════════════════
      :commit-transaction
      (let [[kind kwargs] args]
        (when-not transaction
          (throw (ex-info "no active transaction"
                          {:operation :commit})))
        (when-not (= kind (:kind transaction))
          (throw (ex-info "transaction kind mismatch"
                          {:active     (:kind transaction)
                           :attempting kind})))
        (try
          (let [result (commit (:instance transaction) kwargs record)]
            {:new-transaction nil
             :result          (or result {:fx []})})
          (catch Throwable e
            ;; 提交失败——尝试回滚
            (try
              (rollback (:instance transaction) {} record)
              (catch Throwable _))
            (throw e))))

      ;; ═══════════════════════════════════════════
      ;; 回滚
      ;; ═══════════════════════════════════════════
      :rollback-transaction
      (let [[kind kwargs] args]
        (if-not transaction
          ;; 无活跃事务——幂等
          {:new-transaction nil
           :result          {:fx []}}
          (do
            (when-not (= kind (:kind transaction))
              (throw (ex-info "transaction kind mismatch"
                              {:active     (:kind transaction)
                               :attempting kind})))
            (let [result (rollback (:instance transaction) kwargs record)]
              {:new-transaction nil
               :result          (or result {:fx []})}))))

      ;; ═══════════════════════════════════════════
      ;; 未知指令
      ;; ═══════════════════════════════════════════
      (throw (ex-info "unknown transaction instruction"
                      {:instruction instruction-v})))))

(defn execute-instructions
  "对指令序列循环执行——事务状态 + record 在指令间传递。

   输入：
     transaction   —— 当前事务状态（nil 或 {:kind ... :instance ...}）
     instructions  —— 指令序列
     record        —— 当前 db 片段

   返回：
     {:new-transaction <事务状态或 nil>
      :result          {:record <合并后的 db 片段>
                        :fx     <所有 fx 拼接>}}

   错误处理：
     任何一条指令抛异常——整体抛——外层不合并 fx。
     天然事务性：要么全部成功，要么全部不生效。"
  [transaction instructions record]
  (loop [trans     transaction
         remaining instructions
         current   record
         fx-acc    []]
    (if (seq remaining)
      ;; ── 完成——返回
      {:new-transaction trans
       :result          {:record current
                         :fx     fx-acc}}
      ;; ── 处理下一条
      (let [instruction (first remaining)
            {:keys [new-transaction result]}
            (execute-instruction trans instruction current)
            result-record (or (:record result) {})
            result-fx     (or (:fx result) [])]
        (recur new-transaction
               (rest remaining)
               (merge current result-record)
               (into fx-acc result-fx))))))

(defn transaction-interceptor
  []
  {:after
   (fn [context]
     (let [instructions (get-in context [:effects :transaction])]
       (if (seq instructions)
         (let [transaction (get context (transaction-key))
               record (get-in context [:effects :record])
               {:keys [new-transaction result]} (execute-instructions transaction instructions record)
               {:keys [record fx]} result]
           (-> context
               (assoc (transaction-key) new-transaction)
               (update :effects
                       (fn [eff]
                         (cond-> eff
                                 record           (assoc :record (merge (:record eff) record))
                                 (seq fx)         (update :fx (fnil into []) fx)
                                 )))))
         context)
       ))})