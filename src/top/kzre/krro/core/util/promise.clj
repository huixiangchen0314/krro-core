(ns top.kzre.krro.core.util.promise
  "Promise 抽象。Clojure 惯用的异步原语。

   内部包装 CompletableFuture 用于线程安全和执行器调度，
   对外暴露函数式 API，避免 reify。
   适合用户简单的异步任务场景。

   三类操作：
     1. 构造：promise / resolved / rejected / async
     2. 转换：then / map / tap / recover / handle
     3. 组合：all / any / race
     4. 阻塞：await / await-timeout / deref


     "
  (:refer-clojure :exclude [promise await])
  (:require
   [top.kzre.krro.core.util.assert :refer [assert-args]])
  (:import
   (clojure.lang IBlockingDeref IDeref IPending)
   (java.util.concurrent
    CompletableFuture
    CompletionException
    ExecutionException
    Executor
    ExecutorService
    Executors
    ThreadFactory
    TimeUnit
    TimeoutException)
   (java.util.function
    BiConsumer
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

(defn from-completable-future
  ^Promise [^CompletableFuture cf]
  (->promise cf))

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

(defn tap-finally
  "副作用：无论成功失败，f 都执行（无参数）。
   返回原 Promise（值/异常不变）。
   用于清理、日志等。"
  [^Promise p f]
  (->promise
    (.whenComplete (:future p)
                   (reify BiConsumer
                     (accept [_ _ _]
                       (f))))))

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
  (let [result (CompletableFuture.)]
    (.whenComplete (:future p)
                   (reify BiConsumer
                     (accept [_ v e]
                       (if e
                         (let [cause (if (instance? CompletionException e)
                                       (.getCause ^CompletionException e)
                                       e)]
                           (try
                             (let [^Promise inner (f cause)]
                               ;; 把 inner 的结果 chain 到 result——flatten
                               (.whenComplete (:future inner)
                                              (reify BiConsumer
                                                (accept [_ iv ie]
                                                  (if ie
                                                    (.completeExceptionally result ie)
                                                    (.complete result iv))))))
                             (catch Throwable t
                               (.completeExceptionally result t))))
                         (.complete result v)))))
    (->promise result)))

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
           (reify ThreadFactory
             (^Thread newThread [_ ^Runnable r]
               (doto (Thread. r "krro-promise-timeout")
                 (.setDaemon true))))))

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



;; ═══════════════════════════════════════════════
;; Monad 绑定
;; ═══════════════════════════════════════════════

(defn ensure-promise
  "把值提升为 Promise。已是 Promise 则原样返回。
   用于 plet / pplet 的 body——body 返回普通值或 Promise 都可以。"
  ^Promise [v]
  (if (instance? Promise v)
    v
    (resolved v)))


(defn spawn
  "在 executor 上执行 f——f 的返回值自动扁平化。

   与 async 的区别：
     - async  —— 把 f 的返回值原样作为结果——如果 f 返回 Promise，
                  结果是 Promise<Promise<T>>（嵌套）
     - spawn  —— 把 f 的返回值 flatten——f 返回 Promise<T> 时
                  结果是 Promise<T>；f 返回普通值 v 时结果是 Promise<v>

   适用场景：f 本身是一个「返回 Promise 的计算」——
   比如 f = (fn [] (render-layers! ...))，render-layers! 返回
   Promise<DiffCanvas>。用 spawn 得到 Promise<DiffCanvas>——
   用 async 得到 Promise<Promise<DiffCanvas>>。

   实现：async 提交 f 到 executor——得到 Promise<Promise<T>>；
        再 then + ensure-promise——flatten 一层。

   用法：
     (spawn (fn [] (fetch-user id)) executor)
     ;; → Promise<User>——不是 Promise<Promise<User>>

     (spawn (fn [] 42) executor)
     ;; → Promise<42>

     (spawn (fn [] (fetch-user id)))   ; 默认 executor
     ;; → Promise<User>"
  (^Promise [f]
   (spawn f default-executor*))
  (^Promise [f ^Executor executor]
   (-> (async f executor)
       (then ensure-promise))))

(defmacro plet
  "Monad 绑定——顺序执行多个 Promise。

  用法：
    (plet [user    (fetch-user id)
           posts   (fetch-posts (:id user))
           profile (fetch-profile (:id user))]
      {:user user :posts posts :profile profile})

  展开：
    (then (fetch-user id)
      (fn [user]
        (then (fetch-posts (:id user))
          (fn [posts]
            (then (fetch-profile (:id user))
              (fn [profile]
                (ensure-promise
                  (do {:user user :posts posts :profile profile})))))))

  绑定规则：
    - 每个绑定右侧是返回 Promise 的表达式
    - 绑定按顺序执行——后面的绑定可以引用前面的
    - body 是最后一个表达式——普通值或 Promise 都可以
    - 普通值被 resolved 包裹；Promise 原样返回
    - 支持解构——[[a b] (all [pa pb])]

  错误传播：
    - 任何绑定失败——后续不执行——错误传给返回的 Promise
    - 用 recover / handle 处理

  并行：
    - plet 是顺序绑定——独立的请求用 pplet 并行
    - (plet [[a b c] (all [pa pb pc])] ...)

  语义对应 Haskell：
    - plet   ↔ Monad       (>>=) / do-notation   顺序
    - pplet  ↔ Applicative (<*>)                 并行

  对比 let：
    - let：右侧是普通值——同步绑定
    - plet：右侧是 Promise——异步绑定"
  [bindings & body]
  (assert-args (even? (count bindings))
               "plet: bindings must have an even number of forms")
  (cond
    ;; 无绑定——body 直接提升
    (empty? bindings)
    `(ensure-promise (do ~@body))

    ;; 至少一个绑定——递归展开
    :else
    (let [[binding expr & more] bindings]
      `(then  (ensure-promise ~expr)
              (fn [~binding]
               (plet ~(vec more) ~@body))))))

(defmacro pplet
  [bindings & body]
  (let [pairs   (partition 2 bindings)
        symbols (mapv first pairs)
        exprs   (mapv second pairs)
        binding (vec (interleave symbols symbols))]
    `(fmap (all [~@(map (fn [e] `(ensure-promise ~e)) exprs)])   ; ← 包装
           (fn [~binding]
             (ensure-promise (do ~@body))))))


;; ═══════════════════════════════════════════════
;; 默认错误处理器
;; ═══════════════════════════════════════════════

(def ^:dynamic *default-error-handler*
  "plet> / pplet> 的默认错误处理器。
   可通过 :catch 关键字覆盖（按调用）或 binding 覆盖（按作用域）。"
  (fn [e]
    (binding [*out* *err*]
      (println "[promise] unhandled error:" (ex-message e)))
    e))

;; ═══════════════════════════════════════════════
;; 内部——解析 bindings 中的 :catch
;; ═══════════════════════════════════════════════

(defn- split-handlers
  "从 bindings 中分离出 :catch 和 :finally 处理器。

   bindings 形如：
     [sym1 expr1 sym2 expr2 :catch handler :finally cleanup]

   返回 map：
     {:bindings  [sym1 expr1 sym2 expr2]     ;; 普通绑定——位置对
      :catch     handler-expr                ;; 默认 *default-error-handler*
      :finally   cleanup-expr 或 nil}        ;; 未提供时为 nil

   :catch / :finally 可出现在任意位置——但各自只能出现一次。"
  [bindings]
  (loop [bs      (seq bindings)
         norm    []
         err     nil
         finally nil]
    (cond
      (nil? bs)
      {:bindings norm
       :catch    (or err `*default-error-handler*)
       :finally  finally}

      (= :catch (first bs))
      (do
        (when (nil? (second bs))
          (throw (IllegalArgumentException.
                   (str "plet>: :catch requires an error handler; "
                        "bindings = " (vec bindings)))))
        (when (some? err)
          (throw (IllegalArgumentException.
                   (str "plet>: :catch can only appear once; "
                        "bindings = " (vec bindings)))))
        (recur (nnext bs) norm (second bs) finally))

      (= :finally (first bs))
      (do
        (when (nil? (second bs))
          (throw (IllegalArgumentException.
                   (str "plet>: :finally requires a cleanup fn; "
                        "bindings = " (vec bindings)))))
        (when (some? finally)
          (throw (IllegalArgumentException.
                   (str "plet>: :finally can only appear once; "
                        "bindings = " (vec bindings)))))
        (recur (nnext bs) norm err (second bs)))

      :else
      (do
        (when (nil? (second bs))
          (throw (IllegalArgumentException.
                   (str "plet>: bindings must have even number of forms; "
                        "bindings = " (vec bindings)))))
        (recur (nnext bs)
               (conj norm (first bs) (second bs))
               err
               finally)))))

;; ═══════════════════════════════════════════════
;; 推终结——副作用——返回 nil
;; ═══════════════════════════════════════════════

(defmacro plet>
  [bindings & body]
  (let [{:keys [bindings catch finally]} (split-handlers bindings)]
    `(let [p# (plet ~bindings ~@body)]
       (tap p# identity)
       (tap-error p# ~catch)
       ~@(when finally
           [`(tap-finally p# ~finally)])
       nil)))

(defmacro pplet>
  [bindings & body]
  (let [{:keys [bindings catch finally]} (split-handlers bindings)]
    `(let [p# (pplet ~bindings ~@body)]
       (tap p# identity)
       (tap-error p# ~catch)
       ~@(when finally
           [`(tap-finally p# ~finally)])
       nil)))

;; ═══════════════════════════════════════════════
;; 拉终结——取值——返回普通值——阻塞
;; ═══════════════════════════════════════════════

(defmacro plet<
  "顺序链——拉终结——取值——返回普通值——阻塞。

   阻塞——只在非 UI / 非 GL / 非事件循环线程使用。

   失败时抛原始异常——用 try/catch 捕获。

   用法：
     (plet< [user  (fetch-user id)
             posts (fetch-posts (:id user))]
       {:user user :posts posts})

   展开：
     (await (plet [user  (fetch-user id)
                   posts (fetch-posts (:id user))]
               {:user user :posts posts}))"
  [bindings & body]
  `(await (plet ~bindings ~@body)))

(defmacro pplet<
  "并行链——拉终结——取值——返回普通值——阻塞。

   ⚠️ 阻塞——只在非 UI / 非 GL / 非事件循环线程使用。

   失败时抛原始异常——用 try/catch 捕获。

   用法：
     (pplet< [user  (fetch-user id)
              posts (fetch-posts id)]
       {:user user :posts posts})

   展开：
     (await (pplet [user  (fetch-user id)
                    posts (fetch-posts id)]
               {:user user :posts posts}))"
  [bindings & body]
  `(await (pplet ~bindings ~@body)))


;; ═══════════════════════════════════════════════
;; 递归标记——ploop 专用
;; ═══════════════════════════════════════════════

(defn precur
  "ploop 的递归标记——携带下一轮的绑定值。

   <b>只能在 ploop 的 body 里调用</b>——在 pploop 或外部调用无意义。

   <h3>为什么是函数而不是特殊形式</h3>

   <p>{@code precur} 是普通函数——返回带标记的 map：

   <pre>{@code
   (precur expr1 expr2)
   ;; → {::precur true, :vals [expr1 expr2]}
   }</pre>

   <p>因为它是数据——<b>可以出现在任何表达式位置</b>：
   {@code if} 分支、{@code then} 回调、{@code handle} 错误处理、
   嵌套闭包——ploop 的展开通过 then 链透传检测这个标记——
   不破坏嵌套的 {@code loop} / {@code recur}。

   <h3>求值时机</h3>

   <p>参数在调用点求值——都用当前作用域的值——对应 {@code recur}：

   <pre>{@code
   (loop [a 1 b 2]
     (recur (inc a) (+ a b)))   ; 都用旧值
   }</pre>

   <p>{@code precur} 的语义一致。每个表达式的值可以是 Promise
   或普通值——ploop 用 {@code ensure-promise} 统一——
   {@code all} 并行解析。

   <h3>对称性</h3>

   <p>与 {@link pprecur} 对称——ploop 用 precur、pploop 用 pprecur。
   两者当前语义相同——分开定义是为了拓展性和错误检测：
   用错标记时——递归不会被识别——在运行时表现为「提前返回」。"
  [& vals]
  {::precur true
   :vals   (vec vals)})

(defn ^{:no-doc true} precur?
  "检测是否为 precur 返回的标记。
   public 是宏展开的需要——ploop 展开后代码会调用它。
   用户不应直接调用。"
  [v]
  (and (map? v) (::precur v)))

;; ═══════════════════════════════════════════════
;; 递归标记——pploop 专用
;; ═══════════════════════════════════════════════

(defn pprecur
  "pploop 的递归标记——携带下一轮的绑定值。

   <b>只能在 pploop 的 body 里调用</b>——在 ploop 或外部调用无意义。

   <h3>与 precur 的关系</h3>

   <p>当前 {@code pprecur} 和 {@code precur} 的运行时行为完全一致——
   都是「返回标记 map——由对应宏的 then 链识别——递归」。

   <p><b>为什么分开定义</b>：
   <ul>
     <li><b>对称性</b>——{@code ploop}/{@code precur}、
         {@code pploop}/{@code pprecur} 成对——读者一眼看出配对</li>
     <li><b>拓展性</b>——将来 pploop 的递归语义可能变化
         （比如批处理、分组、并行度控制）——{@code pprecur}
         可以独立演进——不影响 ploop</li>
     <li><b>错误检测</b>——用错标记（ploop 里写 pprecur）时——
         检测不匹配——递归被跳过——要么提前返回、要么报错——
         暴露 bug 而不是静默出错</li>
   </ul>

   <h3>用法</h3>

   <pre>{@code
   (pploop [user    (fetch-user id)
            profile (fetch-profile id)]
     (if done?
       (combine user profile)
       (pprecur (fetch-user id) (fetch-profile id))))
   }</pre>"
  [& vals]
  {::pprecur true
   :vals    (vec vals)})

(defn ^{:no-doc true} pprecur?
  "检测是否为 pprecur 返回的标记。
   public 是宏展开的需要——pploop 展开后代码会调用它。
   用户不应直接调用。"
  [v]
  (and (map? v) (::pprecur v)))

;; ═══════════════════════════════════════════════
;; ploop —— 顺序初始绑定
;; ═══════════════════════════════════════════════
(defmacro ploop
  "Promise 循环——顺序初始绑定——模仿 Clojure 的 loop。

   初始绑定用 plet（顺序）、precur 绑定用 all（并行）。
   递归标记：precur。

   展开：
     (ploop [a init-a b init-b] body)
   ⇒
     (letfn [(step [a b]
               (-> (do body)
                   ensure-promise
                   (then (fn [v]
                           (if (precur? v)
                             (-> (all (mapv ensure-promise (:vals v)))
                                 (then (fn [resolved]
                                         (apply step resolved))))
                             v)))))]
       (plet [a init-a b init-b]
         (step a b)))"
  [bindings & body]
  (assert-args (even? (count bindings))
               "ploop: bindings must have an even number of forms")
  (let [syms (vec (take-nth 2 bindings))
        step (gensym "ploop-step")]
    `(letfn [(~step [~@syms]
               (-> (do ~@body)
                   ensure-promise
                   (then (fn [v#]
                           (if (precur? v#)
                             (-> (all (mapv ensure-promise (:vals v#)))
                                 (then (fn [resolved#]
                                         (apply ~step resolved#))))
                             (ensure-promise v#))))))]
       (plet ~bindings
             (~step ~@syms)))))

(defmacro pploop
  "Promise 循环——并行初始绑定。

   初始绑定用 all（并行）、pprecur 绑定用 all（并行）。
   递归标记：pprecur。

   展开：
     (pploop [a init-a b init-b] body)
   ⇒
     (letfn [(step [a b]
               (-> (do body)
                   ensure-promise
                   (then (fn [v]
                           (if (pprecur? v)
                             (-> (all (mapv ensure-promise (:vals v)))
                                 (then (fn [resolved]
                                         (apply step resolved))))
                             v)))))]
       (-> (all (mapv ensure-promise [init-a init-b]))
           (then (fn [resolved]
                   (apply step resolved)))))"
  [bindings & body]
  (assert-args (even? (count bindings))
               "pploop: bindings must have an even number of forms")
  (let [syms  (vec (take-nth 2 bindings))
        inits (vec (take-nth 2 (rest bindings)))
        step  (gensym "pploop-step")]
    `(letfn [(~step [~@syms]
               (-> (do ~@body)
                   ensure-promise
                   (then (fn [v#]
                           (if (pprecur? v#)
                             (-> (all (mapv ensure-promise (:vals v#)))
                                 (then (fn [resolved#]
                                         (apply ~step resolved#))))
                             (ensure-promise v#))))))]
       (-> (all (mapv ensure-promise [~@inits]))
           (then (fn [resolved#]
                   (apply ~step resolved#)))))))