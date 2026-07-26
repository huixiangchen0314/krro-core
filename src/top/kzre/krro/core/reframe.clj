(ns top.kzre.krro.core.reframe
  "多实例事件流框架，模仿 re-frame API 风格。
   提供：
     - reg-event-db / reg-event-fx  注册事件（支持拦截器链）
     - reg-sub / subscribe          注册与拉取订阅
     - reg-fx                       注册副作用执行器
     - reg-store                    注册存储后端（getter / setter）
     - dispatch                     派发事件
     - on-store-change              监听数据变化（供 UI 自动刷新）
   所有事件、订阅均按 app-id 隔离，不同实例完全独立。")

;; ═══════════════════════════════════════════════
;; 内部状态
;; ═══════════════════════════════════════════════

(def ^:private event-handlers-db
  "纯事件注册表：{app-id {event-id {:handler fn, :interceptors [...]}}}"
  (atom {}))

(def ^:private event-handlers-fx
  "副作用事件注册表：结构同 event-handlers-db，handler 返回 {:db ... :fx ...}"
  (atom {}))

(def ^:private fx-handlers
  "全局副作用执行器：{fx-id (fn [app-id & args] ...)}"
  (atom {}))

(def ^:private subscriptions
  "订阅注册表：{app-id {query-id query-fn}}"
  (atom {}))

(def ^:private stores
  "存储后端：{app-id {:getter (fn [] db), :setter (fn [new-db])}}"
  (atom {}))

(def ^:private store-listeners
  "变化监听器：{app-id {listener-id callback}}"
  (atom {}))

;; ═══════════════════════════════════════════════
;; 注册 API
;; ═══════════════════════════════════════════════

(defn reg-event-db
  "注册纯事件处理器，处理器返回新的 db。
   用法：
     (reg-event-db app-id event-id handler)
     (reg-event-db app-id event-id [interceptors] handler)
   拦截器是包含可选 :before / :after 的 map 向量，
   每个函数签名为 (fn [db event-v] db)。
   handler: (fn [db & args] -> new-db)。"
  ([app-id event-id handler]
   (reg-event-db app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (when (get-in @event-handlers-fx [app-id event-id])
     (throw (ex-info (str "Event already registered as fx: " event-id)
                     {:app-id app-id, :event-id event-id})))
   (swap! event-handlers-db assoc-in [app-id event-id]
          {:handler handler :interceptors interceptors})))

(defn reg-event-fx
  "注册副作用事件处理器，处理器返回 {:db new-db, :fx [[:fx-id & args]...]}。
   用法同 reg-event-db。
   handler: (fn [db & args] -> {:db db, :fx [...]})。"
  ([app-id event-id handler]
   (reg-event-fx app-id event-id [] handler))
  ([app-id event-id interceptors handler]
   (when (get-in @event-handlers-db [app-id event-id])
     (throw (ex-info (str "Event already registered as db: " event-id)
                     {:app-id app-id, :event-id event-id})))
   (swap! event-handlers-fx assoc-in [app-id event-id]
          {:handler handler :interceptors interceptors})))

(defn reg-fx
  "注册副作用执行器。
   处理器接收 app-id 和参数列表。
   例: (reg-fx :http-post (fn [app-id url body] ...))"
  [fx-id handler]
  (swap! fx-handlers assoc fx-id handler))

(defn reg-sub
  "为指定 app-id 注册订阅查询函数。
   例: (reg-sub :my-app :layers (fn [db] (:layers db)))"
  [app-id query-id query-fn]
  (swap! subscriptions assoc-in [app-id query-id] query-fn))

(defn reg-store
  "注册存储后端。getter: (fn [] db), setter: (fn [new-db])。
   返回注销函数。"
  [app-id getter setter]
  (swap! stores assoc app-id {:getter getter :setter setter})
  #(swap! stores dissoc app-id))

;; ═══════════════════════════════════════════════
;; 查询与变化监听
;; ═══════════════════════════════════════════════

(defn subscribe
  "从指定 app-id 同步拉取订阅数据。可带额外参数。
   例: (subscribe :my-app :layer-by-id \"lyr-1\")"
  [app-id query-id & params]
  (let [store (get @stores app-id)]
    (when-not store
      (throw (ex-info (str "Store not registered: " app-id) {:app-id app-id})))
    (let [query-fn (get-in @subscriptions [app-id query-id])]
      (when-not query-fn
        (throw (ex-info (str "Subscription not found: " query-id)
                        {:app-id app-id, :query-id query-id})))
      (let [db ((:getter store))]
        (apply query-fn db params)))))

(defn on-store-change
  "注册数据变化监听器（当 setter 被 dispatch 调用后触发）。
   callback 无参数，调用方应通过 subscribe 获取最新数据。
   返回注销函数。"
  [app-id callback]
  (let [id (keyword (str (gensym "listener")))]
    (swap! store-listeners update app-id (fnil assoc {}) id callback)
    #(swap! store-listeners update app-id dissoc id)))

(defn- notify-listeners [app-id]
  (when-let [listeners (get @store-listeners app-id)]
    (doseq [callback (vals listeners)]
      (callback))))

;; ═══════════════════════════════════════════════
;; 内部：拦截器链与副作用执行
;; ═══════════════════════════════════════════════

(defn- apply-interceptors
  "按顺序执行拦截器的 :before，调用 handler，再逆序执行 :after。
   返回 {:db new-db, :fx [...]}。"
  [interceptors db event-v handler-fn]
  (let [db-after-before
        (reduce (fn [db interceptor]
                  (if-let [f (:before interceptor)]
                    (f db event-v)
                    db))
                db
                interceptors)
        ;; 调用 handler，可能是纯事件 (返回 db) 或 fx 事件 (返回 map)
        result (apply handler-fn db-after-before (rest event-v))
        db-after-handler (if (map? result) (:db result) result)
        fx (when (map? result) (:fx result))
        db-final
        (reduce (fn [db interceptor]
                  (if-let [f (:after interceptor)]
                    (f db event-v)
                    db))
                db-after-handler
                (reverse interceptors))]
    {:db db-final :fx fx}))

(defn- execute-fx [app-id fx-vec]
  (doseq [[fx-id & args] fx-vec]
    (if-let [fx-fn (get @fx-handlers fx-id)]
      (apply fx-fn app-id args)
      (throw (ex-info (str "Unknown fx: " fx-id) {:fx-id fx-id})))))

;; ═══════════════════════════════════════════════
;; 派发
;; ═══════════════════════════════════════════════

(defn dispatch
  "向指定 app-id 派发事件。事件向量格式：[event-id & args]。
   根据注册类型自动选择纯事件处理器或 fx 处理器，并执行拦截器链。
   例: (dispatch :canvas-data [:add-layer {:id \"lyr-1\"}])"
  [app-id event-v]
  (let [store (get @stores app-id)]
    (when-not store
      (throw (ex-info (str "Store not registered: " app-id) {:app-id app-id})))
    (let [event-id (first event-v)
          old-db   ((:getter store))
          entry-db (get-in @event-handlers-db [app-id event-id])
          entry-fx (get-in @event-handlers-fx [app-id event-id])]
      (cond
        entry-db
        (let [{:keys [handler interceptors]} entry-db
              {:keys [db]} (apply-interceptors interceptors old-db event-v handler)]
          ((:setter store) db)
          (notify-listeners app-id))

        entry-fx
        (let [{:keys [handler interceptors]} entry-fx
              {:keys [db fx]} (apply-interceptors interceptors old-db event-v handler)]
          ((:setter store) db)
          (when fx (execute-fx app-id fx))
          (notify-listeners app-id))

        :else
        (throw (ex-info (str "No handler for event: " event-id)
                        {:app-id app-id, :event-id event-id}))))
    nil))