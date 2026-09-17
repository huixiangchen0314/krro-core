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
  (kind [_])
  (begin [_ kwargs record])
  (operate [_ op-kind kwargs record])
  (commit [_ kwargs record])
  (rollback [_ ctx record]))

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

(defonce ^:private transaction-instructions-key* ::transaction-instructions)
(defn transaction-instructions-key [] transaction-instructions-key*)

(defn- execute-instruction
  [trans inst record instruction-acc]
  (let [[tag & args] inst]
    (case tag

      :begin-transaction
      (let [[k kwargs] args]
        (when trans
          (throw (ex-info "trans already active"
                          {:active     (kind trans)
                           :attempting k})))
        (when-not (keyword? k)
          (throw (ex-info "invalid trans k"
                          {:kind k})))
        (let [t (or (transaction k) (throw (ex-info "unknown trans k" {:kind k})))]
          (begin t kwargs record)))

      :transaction-operation
      (let [[k op-kind kwargs] args]
        (when-not trans
          (throw (ex-info "no active trans" {:operation op-kind})))
        (when-not (= k (kind trans))
          (throw (ex-info "trans k mismatch"
                          {:active     (kind trans)
                           :attempting k})))
        (try
          (operate trans
                   op-kind
                   kwargs
                   record)
          (catch Throwable e
            (try (rollback trans
                           {:instructions instruction-acc
                            :status :error
                            :instruction inst} record)
                 (catch Throwable _))
            (throw e))))

      :commit-transaction
      (let [[k kwargs] args]
        (when-not trans
          (throw (ex-info "no active trans" {:operation :commit})))
        (when-not (= k (kind trans))
          (throw (ex-info "trans k mismatch"
                          {:active     (kind trans)
                           :attempting k})))
        (try
          [nil (commit trans kwargs record)]
          (catch Throwable e
            (try (rollback trans {:instructions instruction-acc
                                  :status :error
                                  :instruction inst} record)
                 (catch Throwable _))
            (throw e))))

      :rollback-transaction
      (let [[k] args]
        (if-not trans
          ;; 无活跃事务——幂等
          [nil {:fx []}]
          (do
            (when-not (= k (kind trans))
              (throw (ex-info "trans kind mismatch"
                              {:active     (kind trans)
                               :attempting k})))
            [nil (rollback trans {:instructions instruction-acc
                                  :status nil
                                  :instruction inst} record)])))

      ;; ── 未知指令 ──────────────────────────────────
      (throw (ex-info "unknown trans instruction"
                      {:instruction inst})))))

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
  [transaction instructions record ins-acc]
  (loop [trans     transaction
         remaining instructions
         instruction-acc ins-acc
         current   record
         fx-acc    []]
    (if (seq remaining)
      ;; ── 完成——返回
      [trans
       {:record current
        :fx     fx-acc}]
      ;; ── 处理下一条
      (let [instruction (first remaining)
            [new-transaction result]
            (execute-instruction trans instruction current instruction-acc)
            result-record (or (:record result) {})
            result-fx     (or (:fx result) [])]
        (recur new-transaction
               (rest remaining)
               (conj instruction-acc instruction)
               (merge current result-record)
               (into fx-acc result-fx))))))

(defn transaction-interceptor
  []
  {:after
   (fn [context]
     (let [instructions (get-in context [:effects :transaction])]
       (if (seq instructions)
         (let [transaction (get context (transaction-key))
               inst-acc (get context (transaction-instructions-key) [])
               record (get-in context [:effects :record])
               [new-transaction result new-inst-acc] (execute-instructions transaction instructions record inst-acc)
               {:keys [record fx]} result]
           (-> context
               (assoc (transaction-key) new-transaction)
               (assoc (transaction-instructions-key) new-inst-acc)
               (update :effects
                       (fn [eff]
                         (cond-> eff
                                 record           (assoc :record (merge (:record eff) record))
                                 (seq fx)         (update :fx (fnil into []) fx)
                                 )))))
         context)
       ))})