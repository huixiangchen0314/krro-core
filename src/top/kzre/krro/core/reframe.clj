(ns top.kzre.krro.core.reframe
  "多实例事件流框架，完全对齐 re-frame API 风格。
   事件处理器按 app-id 隔离；
   副作用及订阅按 app-id 隔离；
   每个 record 拥有独立的 store 与反应式追踪。"
  (:require [clojure.core.async :as async :refer [go <! >! chan go-loop close!]])
  (:import (clojure.lang IDeref)))

(declare mark-dirty! subscribe execute-fx invalidate-record-signal process-event dispatch)

;; ═══════════════════════════════════════
;; 内部状态
;; ═══════════════════════════════════════

;; 事件处理器按 app-id 隔离：{app-id {event-id {:handler fn :interceptors [...] :handler-type ...}}}
(def ^:private event-handlers (atom {}))

;; 副作用按 app-id 隔离：{app-id {fx-id handler}}
(def ^:private fx-handlers        (atom {}))

;; 订阅定义按 app-id 隔离：{app-id {query-id {:inputs [...] :compute-fn fn}}}
(def ^:private subscriptions      (atom {}))

;; stores 结构：{app-id {record-id {:getter fn :setter fn :event-chan chan :stop-loop ...}}}
(def ^:private stores             (atom {}))

;; 监听器按 app-id + record-id 隔离：{app-id {record-id {listener-id fn}}}
(def ^:private store-listeners    (atom {}))

;; signal 缓存：{[app-id record-id query-id params] Signal}
(def ^:private signal-cache       (atom {}))

;; 每个 record 的根 signal：{app-id {record-id root-signal}}
(def ^:private record-root-signals (atom {}))

;; ═══════════════════════════════════════
;; Signal（支持可变字段，绑定 record-id）
;; ═══════════════════════════════════════

(deftype Signal [app-id query-id params compute-fn inputs input-signals
                 record-id
                 ^:volatile-mutable value
                 ^:volatile-mutable dirty
                 dependents]
  IDeref
  (deref [this]
    (if-not dirty
      value
      (let [input-vals (mapv (fn [in-desc in-sig]
                               (if in-sig
                                 (deref in-sig)
                                 nil))
                             inputs input-signals)
            new-val (apply compute-fn (concat input-vals params))
            old-val value]
        (set! value new-val)
        (set! dirty false)
        (when (not= new-val old-val)
          (doseq [dep @dependents] (mark-dirty! dep)))
        new-val))))

(defn- get-record-root-signal
  [app-id record-id]
  (let [cache-key [app-id record-id :root]
        existing (get-in @signal-cache cache-key)]
    (if existing
      existing
      (let [store (get-in @stores [app-id record-id])
            _ (when-not store
                (throw (ex-info (str "Store not found for app-id " app-id ", record-id " record-id)
                                {:app-id app-id :record-id record-id})))
            compute-fn (fn [] ((:getter store) record-id))
            signal (Signal. app-id :root [] compute-fn [] [] record-id
                            ::not-computed true (atom #{}))]
        (swap! record-root-signals assoc-in [app-id record-id] signal)
        (swap! signal-cache assoc-in cache-key signal)
        signal))))

(defn- create-signal
  "创建普通订阅 signal，支持三种输入描述符：
   :record            -> 当前 record 的根信号
   keyword            -> 同一 record 下的其他订阅
   [record-id query-id] -> 跨 record 的订阅"
  [app-id record-id query-id params compute-fn inputs]
  (let [input-signals (mapv (fn [in]
                              (cond
                                (= in :record)
                                (get-record-root-signal app-id record-id)

                                (vector? in)
                                (let [[dep-record-id dep-qid] in]
                                  (subscribe app-id dep-record-id dep-qid))

                                (keyword? in)
                                (subscribe app-id record-id in)

                                :else
                                (throw (ex-info (str "Invalid input descriptor: " in) {}))))
                            inputs)
        signal (Signal. app-id query-id params compute-fn inputs input-signals
                        record-id
                        ::not-computed
                        true
                        (atom #{}))]
    (doseq [in-sig input-signals :when in-sig]
      (swap! (.-dependents in-sig) conj signal))
    signal))

(defn- mark-dirty! [^Signal s]
  (when-not (.-dirty s)
    (set! (.-dirty s) true)
    (doseq [dep @(.-dependents s)] (mark-dirty! dep))))

;; ═══════════════════════════════════════
;; 拦截器工厂
;; ═══════════════════════════════════════

(defn path
  [path-vec]
  {:before (fn [context]
             (update-in context [:coeffects :record] #(get-in % path-vec)))
   :after  (fn [context]
             (let [original-record (get-in context [:coeffects :original-record])
                   sub-record      (get-in original-record path-vec)
                   new-record      (get-in context [:effects :record])]
               (-> context
                   (assoc-in [:effects :record] (assoc-in original-record path-vec new-record))
                   (update :coeffects dissoc :original-record))))})

(defn inject-cofx
  [id handler]
  {:before (fn [context]
             (let [cofx (:coeffects context)
                   ev   (:event cofx)
                   val  (handler cofx ev)]
               (assoc-in context [:coeffects id] val)))})

;; ═══════════════════════════════════════
;; 注册 API（事件处理器按 app-id 隔离）
;; ═══════════════════════════════════════

(defn- register-event [app-id event-id interceptors handler handler-type]
  (when (get-in @event-handlers [app-id event-id])
    (throw (ex-info (str "Event already registered for app-id " app-id ": " event-id)
                    {:app-id app-id :event-id event-id})))
  (swap! event-handlers assoc-in [app-id event-id]
         {:handler handler :interceptors interceptors :handler-type handler-type}))

(defn reg-event-record
  "注册纯 record 处理器：(fn [record event-v] -> new-record)。event-v 是完整事件向量。"
  ([app-id event-id handler] (reg-event-record app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (register-event app-id event-id interceptors handler :record)))

(defn reg-event-fx
  "注册副作用处理器：(fn [cofx event-v] -> {:record new-record, :fx [[:fx-id args]]})。"
  ([app-id event-id handler] (reg-event-fx app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (register-event app-id event-id interceptors handler :fx)))

(defn reg-event-ctx
  "注册上下文处理器：(fn [context] -> context)"
  ([app-id event-id handler] (reg-event-ctx app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (register-event app-id event-id interceptors handler :ctx)))

(defn reg-event-co
  "注册协程处理器：(fn [cofx event-v] -> channel)，产出 new-record 或 {:record .. :fx ..}"
  ([app-id event-id handler] (reg-event-co app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (register-event app-id event-id interceptors handler :co)))

(defn reg-fx
  [app-id fx-id handler]
  (swap! fx-handlers assoc-in [app-id fx-id] handler))

(defn reg-sub
  "注册反应式订阅。支持三种输入描述符：
   :record            -> 当前 record 根数据
   keyword            -> 同一 record 下的其他订阅
   [record-id query-id] -> 跨 record 订阅"
  ([app-id query-id compute-fn]
   (reg-sub app-id query-id :<- [:record] compute-fn))
  ([app-id query-id _arrow inputs compute-fn]
   (let [inputs (mapv (fn [in]
                        (cond
                          (= in :record) :record
                          (vector? in)   (let [[dep-record-id dep-qid] in]
                                           [dep-record-id dep-qid])
                          (keyword? in)  in
                          :else (throw (ex-info (str "Invalid input descriptor: " in) {}))))
                      inputs)]
     (swap! subscriptions assoc-in [app-id query-id] {:inputs inputs :compute-fn compute-fn}))))

(defn reg-store
  [app-id record-id getter setter]
  (let [event-chan (chan 64)
        stop-loop (go-loop []
                    (when-let [event-v (<! event-chan)]
                      (process-event app-id event-v)
                      (recur)))]
    (swap! stores assoc-in [app-id record-id]
           {:getter getter :setter setter :event-chan event-chan :stop-loop stop-loop})
    #(do (swap! stores update app-id dissoc record-id)
         (close! event-chan))))

;; ═══════════════════════════════════════
;; 订阅查询
;; ═══════════════════════════════════════

(defn subscribe
  [app-id record-id query-id & params]
  (let [store (get-in @stores [app-id record-id])]
    (when-not store
      (throw (ex-info (str "Store not registered for app-id " app-id ", record-id " record-id)
                      {:app-id app-id :record-id record-id})))
    (let [sub-meta (get-in @subscriptions [app-id query-id])
          _ (when-not sub-meta
              (throw (ex-info (str "Subscription not found: " query-id) {:query-id query-id})))
          cache-key [app-id record-id query-id (vec params)]
          existing (get-in @signal-cache cache-key)]
      (if existing
        existing
        (let [new-sig (create-signal app-id record-id query-id (vec params)
                                     (:compute-fn sub-meta) (:inputs sub-meta))]
          (swap! signal-cache assoc-in cache-key new-sig)
          new-sig)))))

;; ═══════════════════════════════════════
;; 存储变化监听
;; ═══════════════════════════════════════

(defn on-record-change
  [app-id record-id callback]
  (let [id (keyword (str (gensym "listener")))]
    (swap! store-listeners assoc-in [app-id record-id id] callback)
    #(swap! store-listeners update-in [app-id record-id] dissoc id)))

(defn- notify-listeners [app-id record-id]
  (when-let [listeners (get-in @store-listeners [app-id record-id])]
    (doseq [cb (vals listeners)] (cb))))

;; ═══════════════════════════════════════
;; 事件处理引擎
;; ═══════════════════════════════════════

(defn- base-context [app-id event-v]
  (let [event-id  (first event-v)
        record-id (second event-v)
        store     (get-in @stores [app-id record-id])
        _         (when-not store
                    (throw (ex-info (str "Store not found for " app-id "/" record-id)
                                    {:app-id app-id :record-id record-id})))
        record    ((:getter store) record-id)]
    {:coeffects {:record record, :record-id record-id, :event event-v, :app-id app-id}
     :effects {}}))

(defn- apply-interceptors
  "执行拦截器 before -> handler -> after，返回最终 context。
   handler 接收 record/cofx 和完整事件向量，与 re-frame 一致。"
  [interceptors context handler-fn handler-type]
  (let [ctx-before (reduce (fn [ctx interceptor]
                             (if-let [f (:before interceptor)] (f ctx) ctx))
                           context interceptors)
        event-v (get-in ctx-before [:coeffects :event])
        ctx-after-handler
        (case handler-type
          :record
          (let [record (get-in ctx-before [:coeffects :record])]
            (assoc-in ctx-before [:effects :record] (handler-fn record event-v)))

          :fx
          (let [cofx   (get-in ctx-before [:coeffects])
                result (handler-fn cofx event-v)
                {:keys [record fx dispatch dispatch-n]} (if (map? result) result {:record result})
                old-record (get-in ctx-before [:coeffects :record])
                final-record (or record old-record)]   ;; 不允许删除记录
            (-> ctx-before
                (assoc-in [:effects :record] final-record)
                (assoc-in [:effects :fx] (or fx []))
                (cond-> (some? dispatch) (assoc-in [:effects :dispatch] dispatch)
                        (some? dispatch-n) (assoc-in [:effects :dispatch-n] dispatch-n))))

          :ctx (handler-fn ctx-before))

        ctx-final (reduce (fn [ctx interceptor]
                            (if-let [f (:after interceptor)] (f ctx) ctx))
                          ctx-after-handler
                          (reverse interceptors))]
    ctx-final))

(defn- process-event [app-id event-v]
  (let [event-id  (first event-v)
        record-id (second event-v)
        store     (get-in @stores [app-id record-id])
        _         (when-not store
                    (throw (ex-info (str "Store gone for " app-id "/" record-id) {})))
        entry     (get-in @event-handlers [app-id event-id])
        _         (when-not entry
                    (throw (ex-info (str "No handler for event " event-id " in app-id " app-id)
                                    {:app-id app-id :event-id event-id})))
        {:keys [handler interceptors handler-type]} entry
        context   (-> (base-context app-id event-v)
                      (assoc-in [:coeffects :original-record]
                                (get-in (base-context app-id event-v) [:coeffects :record])))]
    (case handler-type
      (:record :fx :ctx)
      (let [ctx (apply-interceptors interceptors context handler handler-type)
            effects (:effects ctx)
            fx-list (or (:fx effects) [])
            dispatch-event (:dispatch effects)
            dispatch-n-events (:dispatch-n effects)]
        ((:setter store) record-id (get-in ctx [:effects :record]))
        ;; 先执行自定义副作用列表
        (when (seq fx-list) (execute-fx app-id fx-list))
        ;; 再单独处理事件分发（不混入 fx-list）
        (when dispatch-event
          (dispatch app-id dispatch-event))
        (when dispatch-n-events (doseq [ev dispatch-n-events] (dispatch app-id ev)))
        (invalidate-record-signal app-id record-id)
        (notify-listeners app-id record-id))

      :co
      (let [ctx-before (reduce (fn [ctx interceptor]
                                 (if-let [f (:before interceptor)] (f ctx) ctx))
                               context interceptors)
            cofx    (get-in ctx-before [:coeffects])
            event-v (get-in ctx-before [:coeffects :event])
            ch      (handler cofx event-v)]
        (go
          (let [result (<! ch)
                record-result (if (map? result) (:record result) result)
                fx (when (map? result) (:fx result))
                dispatch-event (when (map? result) (:dispatch result))
                dispatch-n-events (when (map? result) (:dispatch-n result))]
            ((:setter store) record-id record-result)
            (when (seq fx) (execute-fx app-id fx))
            (when dispatch-event (dispatch app-id dispatch-event))
            (when dispatch-n-events (doseq [ev dispatch-n-events] (dispatch app-id ev)))
            (invalidate-record-signal app-id record-id)
            (notify-listeners app-id record-id)))))))

(defn- invalidate-record-signal [app-id record-id]
  (when-let [sig (get-in @record-root-signals [app-id record-id])]
    (mark-dirty! sig)))

(defn- execute-fx [app-id fx-vec]
  (doseq [[fx-id & args] fx-vec]
    (cond
      (get-in @fx-handlers [app-id fx-id])
      (apply (get-in @fx-handlers [app-id fx-id]) app-id args)

      :else
      (throw (ex-info (str "Unknown fx: " fx-id " for app-id: " app-id)
                      {:app-id app-id :fx-id fx-id})))))

;; ═══════════════════════════════════════
;; 派发
;; ═══════════════════════════════════════

(defn dispatch
  "异步派发事件。事件向量格式：[event-id record-id & args]。
   事件进入对应 app-id 和 record-id 的通道，处理器按 app-id 隔离。"
  [app-id event-v]
  (let [record-id (second event-v)
        store     (get-in @stores [app-id record-id])]
    (when-not store
      (throw (ex-info (str "Store not registered for " app-id "/" record-id)
                      {:app-id app-id :record-id record-id})))
    (async/put! (:event-chan store) event-v))
  nil)