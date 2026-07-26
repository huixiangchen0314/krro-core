(ns top.kzre.krro.core.reframe
  "多实例事件流框架，完全对齐 re-frame API 风格。"
  (:require [clojure.core.async :as async :refer [go <! >! chan go-loop close!]])
  (:import (clojure.lang IDeref)))

(declare mark-dirty! subscribe execute-fx invalidate-root-signals process-event)

;; ═══════════════════════════════════════
;; 内部状态
;; ═══════════════════════════════════════

(def ^:private event-handlers-db  (atom {}))
(def ^:private event-handlers-fx  (atom {}))
(def ^:private event-handlers-co  (atom {}))
(def ^:private event-handlers-ctx (atom {}))

(def ^:private fx-handlers        (atom {}))
(def ^:private subscriptions      (atom {}))
(def ^:private stores             (atom {}))
(def ^:private store-listeners    (atom {}))

(def ^:private signal-cache       (atom {}))
(def ^:private root-signals       (atom {}))

;; ═══════════════════════════════════════
;; Signal（使用 deftype，支持可变字段）
;; ═══════════════════════════════════════

(deftype Signal [app-id query-id params compute-fn inputs input-signals
                 ^:volatile-mutable value
                 ^:volatile-mutable dirty
                 dependents]
  IDeref
  (deref [this]
    (if-not dirty
      value
      (let [db (when-let [s (get @stores app-id)]
                 ((:getter s)))
            input-vals (mapv (fn [in-desc in-sig]
                               (if (= in-desc :db) db (deref in-sig)))
                             inputs input-signals)
            new-val (apply compute-fn (concat input-vals params))
            old-val value]
        (set! value new-val)
        (set! dirty false)
        ;; 值发生变化时，传播 dirty 标记给下游
        (when (not= new-val old-val)
          (doseq [dep @dependents] (mark-dirty! dep)))
        new-val))))

(defn- create-signal [app-id query-id params compute-fn inputs]
  (let [input-signals (mapv (fn [in]
                              (if (= in :db)
                                nil
                                (let [[dep-app dep-qid] in]
                                  (subscribe dep-app dep-qid))))
                            inputs)
        signal (Signal. app-id query-id params compute-fn inputs input-signals
                        ::not-computed       ; 初始 value
                        true                 ; 初始 dirty
                        (atom #{}))]         ; dependents
    ;; 注册到父信号的 dependents 列表
    (doseq [in-sig input-signals :when in-sig]
      (swap! (.-dependents in-sig) conj signal))
    ;; 根信号记录，用于 db 变更时批量标脏
    (when (some #(= % :db) inputs)
      (swap! root-signals update app-id (fnil conj []) signal))
    signal))

(defn- mark-dirty! [^Signal s]
  (when-not (.-dirty s)
    (set! (.-dirty s) true)
    (doseq [dep @(.-dependents s)] (mark-dirty! dep))))

;; ═══════════════════════════════════════
;; 拦截器工厂
;; ═══════════════════════════════════════

(defn path
  "返回拦截器，将处理器的作用域限定在 db 的指定路径内。"
  [path-vec]
  {:before (fn [context]
             (update-in context [:coeffects :db] #(get-in % path-vec)))
   :after  (fn [context]
             (let [original-db (get-in context [:coeffects :original-db])
                   sub-db      (get-in original-db path-vec)
                   new-db      (get-in context [:effects :db])]
               (-> context
                   (assoc-in [:effects :db] (assoc-in original-db path-vec new-db))
                   (update :coeffects dissoc :original-db))))})

(defn inject-cofx
  "返回拦截器，向 coeffect 上下文注入一个新键。"
  [id handler]
  {:before (fn [context]
             (let [cofx (:coeffects context)
                   ev   (:event cofx)
                   val  (handler cofx ev)]
               (assoc-in context [:coeffects id] val)))})

;; ═══════════════════════════════════════
;; 注册 API
;; ═══════════════════════════════════════

(defn- register-event [atom-to-use app-id event-id interceptors handler handler-type]
  (when (some #(get-in @% [app-id event-id])
              [event-handlers-db event-handlers-fx event-handlers-co event-handlers-ctx])
    (throw (ex-info (str "Event already registered: " event-id) {:app-id app-id :event-id event-id})))
  (swap! atom-to-use assoc-in [app-id event-id] {:handler handler :interceptors interceptors}))

(defn reg-event-db
  "注册纯 db 处理器： (fn [db event-v] -> new-db)"
  ([app-id event-id handler] (reg-event-db app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (register-event event-handlers-db app-id event-id interceptors handler :db)))

(defn reg-event-fx
  "注册副作用处理器： (fn [cofx event-v] -> {:db new-db, :fx [[:fx-id args]]})"
  ([app-id event-id handler] (reg-event-fx app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (register-event event-handlers-fx app-id event-id interceptors handler :fx)))

(defn reg-event-ctx
  "注册上下文处理器： (fn [context] -> context)"
  ([app-id event-id handler] (reg-event-ctx app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (register-event event-handlers-ctx app-id event-id interceptors handler :ctx)))

(defn reg-event-co
  "注册协程处理器： (fn [cofx event-v] -> channel)，channel 产出 new-db 或 {:db .. :fx ..}"
  ([app-id event-id handler] (reg-event-co app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (register-event event-handlers-co app-id event-id interceptors handler :co)))

(defn reg-fx [fx-id handler]
  (swap! fx-handlers assoc fx-id handler))

(defn reg-sub
  "注册反应式订阅。"
  ([app-id query-id compute-fn]
   (reg-sub app-id query-id :<- [:db] compute-fn))
  ([app-id query-id _arrow inputs compute-fn]
   (let [inputs (mapv (fn [in]
                        (if (= in :db)
                          :db
                          (let [[dep-app dep-qid] in]
                            [dep-app dep-qid])))
                      inputs)]
     (swap! subscriptions assoc-in [app-id query-id] {:inputs inputs :compute-fn compute-fn}))))

(defn reg-store
  "注册存储后端，启动事件处理循环。返回注销函数。"
  [app-id getter setter]
  (let [event-chan (chan 64)
        stop-loop (go-loop []
                    (when-let [event-v (<! event-chan)]
                      (process-event app-id event-v)
                      (recur)))]
    (swap! stores assoc app-id {:getter getter :setter setter :event-chan event-chan :stop-loop stop-loop})
    #(do (swap! stores dissoc app-id)
         (close! event-chan))))

;; ═══════════════════════════════════════
;; 订阅查询
;; ═══════════════════════════════════════

(defn subscribe
  "获取一个 Signal（实现了 IDeref），可安全 deref 获得最新计算值。"
  [app-id query-id & params]
  (let [store (get @stores app-id)]
    (when-not store (throw (ex-info (str "Store not registered: " app-id) {})))
    (let [sub-meta (get-in @subscriptions [app-id query-id])
          _ (when-not sub-meta (throw (ex-info (str "Subscription not found: " query-id) {})))
          cache-key [app-id query-id (vec params)]
          existing (get-in @signal-cache cache-key)]
      (if existing
        existing
        (let [new-sig (create-signal app-id query-id (vec params)
                                     (:compute-fn sub-meta) (:inputs sub-meta))]
          (swap! signal-cache assoc-in cache-key new-sig)
          new-sig)))))

;; ═══════════════════════════════════════
;; 存储变化监听
;; ═══════════════════════════════════════

(defn on-store-change
  "注册数据变化回调，每次 setter 被调用后触发。返回注销函数。"
  [app-id callback]
  (let [id (keyword (str (gensym "listener")))]
    (swap! store-listeners update app-id (fnil assoc {}) id callback)
    #(swap! store-listeners update app-id dissoc id)))

(defn- notify-listeners [app-id]
  (when-let [listeners (get @store-listeners app-id)]
    (doseq [cb (vals listeners)] (cb))))

;; ═══════════════════════════════════════
;; 事件处理引擎
;; ═══════════════════════════════════════

(defn- base-context [app-id event-v]
  (let [store (get @stores app-id)
        db    ((:getter store))]
    {:coeffects {:db db, :event event-v, :app-id app-id}
     :effects {}}))

(defn- apply-interceptors
  "执行拦截器 before → handler → after，返回最终 context。"
  [interceptors context handler-fn handler-type]
  (let [ctx-before (reduce (fn [ctx interceptor]
                             (if-let [f (:before interceptor)] (f ctx) ctx))
                           context interceptors)
        ctx-after-handler
        (case handler-type
          :db  (let [db   (get-in ctx-before [:coeffects :db])
                     args (rest (get-in ctx-before [:coeffects :event]))]
                 (assoc-in ctx-before [:effects :db] (apply handler-fn db args)))
          :fx  (let [cofx   (get-in ctx-before [:coeffects])
                     event-v (get-in ctx-before [:coeffects :event])
                     result  (apply handler-fn cofx (rest event-v))
                     {:keys [db new-db fx]} (if (map? result) result {:db result})
                     final-db (or new-db db)]
                 (-> ctx-before
                     (assoc-in [:effects :db] final-db)
                     (assoc-in [:effects :fx] (or fx []))))
          :ctx (handler-fn ctx-before))
        ctx-final (reduce (fn [ctx interceptor]
                            (if-let [f (:after interceptor)] (f ctx) ctx))
                          ctx-after-handler
                          (reverse interceptors))]
    ctx-final))

(defn- process-event [app-id event-v]
  (let [store (get @stores app-id)
        _ (when-not store (throw (ex-info "Store gone" {})))
        event-id (first event-v)
        entry-db  (get-in @event-handlers-db  [app-id event-id])
        entry-fx  (get-in @event-handlers-fx  [app-id event-id])
        entry-ctx (get-in @event-handlers-ctx [app-id event-id])
        entry-co  (get-in @event-handlers-co  [app-id event-id])
        context (-> (base-context app-id event-v)
                    (assoc-in [:coeffects :original-db]
                              (get-in (base-context app-id event-v) [:coeffects :db])))]
    (cond
      entry-db
      (let [{:keys [handler interceptors]} entry-db
            ctx (apply-interceptors interceptors context handler :db)]
        ((:setter store) (get-in ctx [:effects :db]))
        (when-let [fx (get-in ctx [:effects :fx])] (execute-fx app-id fx))
        (invalidate-root-signals app-id)
        (notify-listeners app-id))

      entry-fx
      (let [{:keys [handler interceptors]} entry-fx
            ctx (apply-interceptors interceptors context handler :fx)]
        ((:setter store) (get-in ctx [:effects :db]))
        (when-let [fx (get-in ctx [:effects :fx])] (execute-fx app-id fx))
        (invalidate-root-signals app-id)
        (notify-listeners app-id))

      entry-ctx
      (let [{:keys [handler interceptors]} entry-ctx
            ctx (apply-interceptors interceptors context handler :ctx)]
        ((:setter store) (get-in ctx [:effects :db]))
        (when-let [fx (get-in ctx [:effects :fx])] (execute-fx app-id fx))
        (invalidate-root-signals app-id)
        (notify-listeners app-id))

      entry-co
      (let [{:keys [handler interceptors]} entry-co
            ctx-before (reduce (fn [ctx interceptor]
                                 (if-let [f (:before interceptor)] (f ctx) ctx))
                               context interceptors)
            cofx   (get-in ctx-before [:coeffects])
            event-v (get-in ctx-before [:coeffects :event])
            ch     (apply handler cofx (rest event-v))]
        (go
          (let [result (<! ch)
                db-result (if (map? result) (:db result) result)
                fx        (when (map? result) (:fx result))
                ctx-after-handler (-> ctx-before
                                      (assoc-in [:effects :db] db-result)
                                      (assoc-in [:effects :fx] (or fx [])))
                ctx-final (reduce (fn [ctx interceptor]
                                    (if-let [f (:after interceptor)] (f ctx) ctx))
                                  ctx-after-handler
                                  (reverse interceptors))]
            ((:setter store) (get-in ctx-final [:effects :db]))
            (when-let [fx (get-in ctx-final [:effects :fx])] (execute-fx app-id fx))
            (invalidate-root-signals app-id)
            (notify-listeners app-id))))
      :else
      (throw (ex-info (str "No handler for event: " event-id) {})))))

(defn- invalidate-root-signals [app-id]
  (when-let [signals (get @root-signals app-id)]
    (doseq [s signals] (mark-dirty! s))))

(defn- execute-fx [app-id fx-vec]
  (doseq [[fx-id & args] fx-vec]
    (if-let [fx-fn (get @fx-handlers fx-id)]
      (apply fx-fn app-id args)
      (throw (ex-info (str "Unknown fx: " fx-id) {})))))

;; ═══════════════════════════════════════
;; 派发
;; ═══════════════════════════════════════

(defn dispatch
  "异步派发事件。事件进入 app-id 通道，由后台循环顺序处理。"
  [app-id event-v]
  (if-let [store (get @stores app-id)]
    (async/put! (:event-chan store) event-v)
    (throw (ex-info (str "Store not registered: " app-id) {:app-id app-id})))
  nil)