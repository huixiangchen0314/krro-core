(ns top.kzre.krro.core.util.promise
  "Promise 抽象。Clojure 惯用的异步原语。

   内部包装 CompletableFuture 用于线程安全和执行器调度，
   对外暴露函数式 API，避免 reify。
   适合用户简单的异步任务场景。

   三类操作：
     1. 构造：promise / resolved / rejected / async
     2. 转换：then / map / tap / recover / handle
     3. 组合：all / any / race
     4. 阻塞：await / await-timeout / deref"
  (:refer-clojure :exclude [promise await])
  (:import
    (clojure.lang IBlockingDeref IDeref IPending)
    (java.util.concurrent CompletableFuture
                          CompletionException
                          ExecutionException
                          Executor
                          Executors
                          ExecutorService
                          TimeoutException
                          TimeUnit)
    (java.util.function BiConsumer
                        BiFunction
                        Function
                        Supplier)))

;; ═══════════════════════════════════════════════
;; 类型
;; ═══════════════════════════════════════════════

(defrecord Promise [^CompletableFuture future]
  IDeref
  (deref [_]
    (try
      (.get future)
      (catch ExecutionException e
        (let [cause (.getCause e)]
          (throw (if cause cause e))))
      (catch InterruptedException e
        (.interrupt (Thread/currentThread))
        (throw e))))

  IBlockingDeref
  (deref [_ timeout-ms timeout-val]
    (try
      (.get future (long timeout-ms) TimeUnit/MILLISECONDS)
      (catch TimeoutException _ timeout-val)
      (catch ExecutionException e
        (let [cause (.getCause e)]
          (throw (if cause cause e))))   ; 解开 cause
      (catch InterruptedException e
        (.interrupt (Thread/currentThread))
        (throw e))))


  IPending
  (isRealized [_] (.isDone future))

  Object
  (toString [_]
    (str "#Promise" (if (.isDone future)
                      (if (.isCompletedExceptionally future) "{failed}" "{done}")
                      "{pending}"))))

;; ═══════════════════════════════════════════════
;; 内部工具
;; ═══════════════════════════════════════════════

(defn- ->promise [^CompletableFuture cf]
  (->Promise cf))

;; 默认执行器
(defonce ^:private default-executor*
         (Executors/newWorkStealingPool))

(defn default-executor
  "返回当前默认执行器。"
  ^ExecutorService []
  default-executor*)

(defn set-default-executor!
  "替换默认执行器。返回旧执行器（调用方负责关闭它，如果不再使用）。"
  [^ExecutorService executor]
  (let [old (atom nil)]
    (alter-var-root #'default-executor*
                    (fn [current]
                      (reset! old current)
                      executor))
    @old))

;; ═══════════════════════════════════════════════
;; 构造
;; ═══════════════════════════════════════════════

(defn promise
  "创建一个未完成的 Promise。"
  ^Promise []
  (->promise (CompletableFuture.)))

(defn resolved
  "创建一个已完成的 Promise。"
  ^Promise [v]
  (->promise (CompletableFuture/completedFuture v)))

(defn rejected
  [^Throwable e]
  (let [cf (CompletableFuture.)]
    (.completeExceptionally cf e)
    (->promise cf)))

(defn async
  "在 executor 上异步执行 f。f 是无参函数。
   默认用全局 executor。"
  (^Promise [f] (async f default-executor*))
  (^Promise [f ^Executor executor]
   (->promise
     (CompletableFuture/supplyAsync
       (reify Supplier
         (get [_] (f)))
       executor))))

;; ═══════════════════════════════════════════════
;; 手动完成
;; ═══════════════════════════════════════════════

(defn resolve!
  "手动完成 Promise。返回 true 表示成功，false 表示已完成过。"
  [^Promise p v]
  (.complete  (:future p) v))

(defn reject!
  "手动失败 Promise。返回 true 表示成功，false 表示已完成过。"
  [^Promise p ^Throwable e]
  (.completeExceptionally (:future p) e))

(defn cancel!
  "取消 Promise。返回 true 表示成功。"
  [^Promise p]
  (.cancel (:future p) true))

(defn done?      ^Boolean [^Promise p] (.isDone (:future p)))
(defn failed?    ^Boolean [^Promise p] (.isCompletedExceptionally (:future p)))
(defn cancelled? ^Boolean [^Promise p] (.isCancelled (:future p)))

;; ═══════════════════════════════════════════════
;; 转换（单链）
;; ═══════════════════════════════════════════════

(defn then
  "链式：f 接收值，返回新 Promise。
   在完成线程上执行（无额外调度）。"
  [^Promise p f]
  (->promise
    (.thenCompose (:future p)
                  (reify Function
                    (apply [_ v]
                      (:future ^Promise (f v)))))))

(defn then-async
  "链式：f 在新线程上执行。
   默认用全局 executor。"
  ([^Promise p f] (then-async p f default-executor*))
  ([^Promise p f ^Executor executor]
   (->promise
     (.thenComposeAsync (:future p)
                        (reify Function
                          (apply [_ v]
                            (:future ^Promise (f v))))
                        executor))))

(defn fmap
  "映射：f 接收值，返回普通值。"
  [^Promise p f]
  (->promise
    (.thenApply (:future p)
                (reify Function
                  (apply [_ v] (f v))))))

(defn fmap-async
  "映射：f 在新线程上执行。"
  ([^Promise p f] (fmap-async p f default-executor*))
  ([^Promise p f ^Executor executor]
   (->promise
     (.thenApplyAsync (:future p)
                      (reify Function
                        (apply [_ v] (f v)))
                      executor))))

(defn tap
  "副作用：f 接收值，返回原 Promise（值不变）。
   用于日志、埋点等。"
  [^Promise p f]
  (->promise
    (.thenApply (:future p)
                (reify Function
                  (apply [_ v] (f v) v)))))

(defn tap-error
  "副作用：f 接收异常，返回原 Promise。
   用于记录错误日志。"
  [^Promise p f]
  (->promise
    (.whenComplete (:future p)
                   (reify BiConsumer
                     (accept [_ _ e]
                       (when e (f e)))))))

;; ═══════════════════════════════════════════════
;; 错误处理
;; ═══════════════════════════════════════════════

(defn recover
  "错误恢复：f 接收异常，返回替代值。
   成功时 f 不被调用。"
  [^Promise p f]
  (->promise
    (.exceptionally (:future p)
                    (reify Function
                      (apply [_ e]
                        (let [cause (if (instance? CompletionException e)
                                      (.getCause ^CompletionException e)
                                      e)]
                          (f cause)))))))

(defn recover-with
  "错误恢复：f 接收异常，返回新 Promise。"
  [^Promise p f]
  (->promise
    (.handle (:future p)
             (reify BiFunction
               (apply [_ v e]
                 (if e
                   (let [cause (if (instance? CompletionException e)
                                 (.getCause ^CompletionException e)
                                 e)
                         ^Promise inner (f cause)]
                     (:future inner))
                   (CompletableFuture/completedFuture v)))))))

(defn handle
  "统一处理：f 接收 (值, 异常)，返回新值。
   成功时异常为 nil，失败时值为 nil。"
  [^Promise p f]
  (->promise
    (.handle (:future p)
             (reify BiFunction
               (apply [_ v e]
                 (let [cause (when e
                               (if (instance? CompletionException e)
                                 (.getCause ^CompletionException e)
                                 e))]
                   (f v cause)))))))

;; ═══════════════════════════════════════════════
;; 组合（多链）
;; ═══════════════════════════════════════════════

(defn all
  "等所有 Promise 完成，返回值的向量。
   任一失败 → 结果 Promise 失败（快速失败）。"
  [promises]
  (let [ps   (vec promises)                                  ; ← 固化，避免二次向量转化
        futs (into-array CompletableFuture
                         (map #(:future ^Promise %) ps))]
    (->promise
      (-> (CompletableFuture/allOf futs)
          (.thenApply
            (reify Function
              (apply [_ _]
                (mapv (fn [^Promise p] (.join (:future p))) ps))))))))

(defn- unwrap-cause
  "把 CompletionException 解包成原始异常。"
  ^Throwable [e]
  (if (and (instance? CompletionException e)
           (.getCause ^Throwable e))
    (.getCause ^Throwable e)
    e))

(defn all-delay-error
  "等所有 Promise 完成（成功或失败），返回值的向量（与输入同序）。

   任一失败 → 等全部完成后，统一抛出 ex-info：
     - ex-message : \"all-delay-error: N/M failed\"
     - ex-data    : {:errors [e1 e2 ...]   ; 失败异常，按输入顺序
                     :count  N
                     :total  M}
     - 第一个异常作为 cause，其余挂在 .suppressed 上（便于 Java 互操作）

   与 all 的区别：all 快速失败（第一个失败立即 reject），
   all-delay-error 不快速失败，保证所有节点都跑完再抛。
   不取消任何子任务。"
  [promises]
  (let [ps (vec promises)
        ;; 每个 future 包成永不失败：值形如 [::ok v] / [::err e]
        settled
        (mapv (fn [^Promise p]
                (.handle (:future p)
                         (reify BiFunction
                           (apply [_ v e]
                             (if e
                               [::err (unwrap-cause e)]
                               [::ok v])))))
              ps)
        futs (into-array CompletableFuture settled)]
    (->promise
      (-> (CompletableFuture/allOf futs)
          (.thenApply
            (reify Function
              (apply [_ _]
                (let [results (mapv #(.join ^CompletableFuture %) settled)
                      errs    (into [] (keep (fn [[k v]] (when (= k ::err) v))) results)]
                  (if (seq errs)
                    (let [ex (ex-info (str "all-delay-error: "
                                           (count errs) "/" (count ps) " failed")
                                      {:errors errs
                                       :count  (count errs)
                                       :total  (count ps)})]
                      ;; 第一个当 cause，其余挂 suppressed
                      (doseq [e (rest errs)]
                        (.addSuppressed ex e))
                      (throw ex))
                    (mapv second results))))))))))

(defn any
  [promises]
  (if (empty? promises)
    (rejected (IllegalArgumentException. "any: empty promises"))
    (->promise
      (CompletableFuture/anyOf
        (into-array CompletableFuture
                    (map #(:future ^Promise %) promises))))))

(defn race
  "同 any。语义更明确。"
  [promises]
  (any promises))

;; ═══════════════════════════════════════════════
;; 超时
;; ═══════════════════════════════════════════════

(defonce ^:private timeout-scheduler
         (Executors/newSingleThreadScheduledExecutor
           (fn [^Runnable r]
             (doto (Thread. r "krro-promise-timeout")
               (.setDaemon true)))))

(defn timeout
  [^Promise p ms]
  (let [cf (:future p)
        result (CompletableFuture.)
        task (.schedule timeout-scheduler
                        ^Runnable #(.completeExceptionally
                                     result (TimeoutException. (str "timeout after " ms "ms")))
                        (long ms) TimeUnit/MILLISECONDS)]
    ;; 成功/失败后取消定时器
    (.whenComplete cf
                   (reify BiConsumer
                     (accept [_ v e]
                       (.cancel task false)   ; ← 取消定时器
                       (if e
                         (.completeExceptionally result e)
                         (.complete result v)))))
    (->promise result)))

(defn complete-on-timeout
  [^Promise p ms fallback]
  (let [cf (:future p)
        result (CompletableFuture.)
        task (.schedule timeout-scheduler
                        ^Runnable #(.complete result fallback)
                        (long ms) TimeUnit/MILLISECONDS)]
    (.whenComplete cf
                   (reify BiConsumer
                     (accept [_ v e]
                       (.cancel task false)
                       (if e
                         (.completeExceptionally result e)
                         (.complete result v)))))
    (->promise result)))

;; ═══════════════════════════════════════════════
;; 阻塞访问
;; ═══════════════════════════════════════════════

(defn await
  "阻塞等待结果。失败时抛原始异常（非 CompletionException）。"
  [^Promise p]
  (try
    (.join (:future p))
    (catch CompletionException e
      (throw (or (.getCause e) e)))))

(defn await-timeout
  "带超时等待。超时返回 timeout-val。失败时抛原始异常。"
  [^Promise p timeout-ms timeout-val]
  (try
    (.get (:future p) (long timeout-ms) TimeUnit/MILLISECONDS)
    (catch TimeoutException _ timeout-val)
    (catch ExecutionException e
      (throw (or (.getCause e) e)))
    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      (throw e))))

(defn await-or
  "带超时等待。超时返回 fallback-fn 的结果。"
  [^Promise p timeout-ms fallback-fn]
  (await-timeout p timeout-ms (fallback-fn)))
