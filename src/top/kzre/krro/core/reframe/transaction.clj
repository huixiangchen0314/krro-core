(ns top.kzre.krro.core.reframe.transaction
  "reframe 事务插件"
  (:require [top.kzre.krro.core.reframe.util :as util]))

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

(defn- transactions []
  @transaction-registry)

(defn- transaction [trans-kind]
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

(defn- execute-instructions
  [transaction instructions record ins-acc]
  (loop [trans           transaction
         remaining       instructions
         instruction-acc ins-acc
         current         record
         effects-acc     {:fx []}]
    (if-not (seq remaining)
      [trans (assoc effects-acc :record current)]
      (let [instruction (first remaining)
            [new-transaction result]
            (execute-instruction trans instruction current instruction-acc)
            ;; result 里可能有 :record / :fx / :dispatch / :dispatch-n
            effects' (util/merge-effects effects-acc result)]
        (recur new-transaction
               (rest remaining)
               (if (some? new-transaction)
                 (conj instruction-acc instruction)
                 [])
               (:record effects')
               (dissoc effects' :record))))))

(defn transaction-interceptor
  []
  {:after
   (fn [context]
     (let [instructions (get-in context [:effects :transaction])]
       (if (seq instructions)
         (let [record       (get-in context [:effects :record])
               transaction  (get record (transaction-key))
               inst-acc     (get record (transaction-instructions-key) [])
               [new-transaction trans-effects new-inst-acc]
               (execute-instructions transaction instructions record inst-acc)
               ;; 事务状态写回 record
               trans-effects' (-> trans-effects
                                 (assoc-in [:record (transaction-key)] new-transaction)
                                 (assoc-in [:record (transaction-instructions-key)] new-inst-acc))]
           (-> context
               (update :effects util/merge-effects trans-effects')))
         context)))})