(ns top.kzre.krro.core.reframe
  "多实例事件流框架，数据存取完全委托给外部注册的 getter/setter。
   事件和订阅均通过 app-id 隔离，不同实例互不冲突。")

;; ── 按 app-id 隔离的事件处理器注册表 ──────────────
(def ^:private event-handlers (atom {}))

(defn reg-event
  "为指定 app-id 注册事件处理器。
   app-id   : 应用实例标识
   event-id : 事件标识（在同一 app-id 内唯一）
   handler  : (fn [db & args] -> new-db)，纯函数，接收当前 db 和事件参数，返回新 db"
  [app-id event-id handler]
  (swap! event-handlers assoc-in [app-id event-id] handler))

(defn unreg-event
  "取消某个 app-id 下的某个事件。"
  [app-id event-id]
  (swap! event-handlers #(update-in % [app-id] dissoc event-id)))

;; ── 按 app-id 隔离的订阅查询注册表 ──────────────
(def ^:private subscriptions (atom {}))

(defn reg-sub
  "为指定 app-id 注册订阅查询。
   app-id   : 应用实例标识
   query-id : 订阅标识（在同一 app-id 内唯一）
   query-fn : (fn [db & params] -> derived-data)，从该实例的 db 衍生数据"
  [app-id query-id query-fn]
  (swap! subscriptions assoc-in [app-id query-id] query-fn))

(defn unreg-sub
  "取消某个 app-id 下的订阅。"
  [app-id query-id]
  (swap! subscriptions #(update-in % [app-id] dissoc query-id)))

;; ── 存储后端注册 ──────────────────────────────
(def ^:private stores (atom {}))

(defn reg-store
  "注册一个应用实例的存储后端。
   app-id  : 应用标识
   getter  : (fn [] db)       获取当前数据
   setter  : (fn [new-db])    写入新数据
   返回一个注销函数。"
  [app-id getter setter]
  (swap! stores assoc app-id {:getter getter :setter setter})
  #(swap! stores dissoc app-id))

;; ── 可选的变化监听（用于 UI 自动刷新） ──────────
(def ^:private store-listeners (atom {}))

(defn on-store-change
  "注册一个监听器，当指定 app-id 的数据被 setter 更新后触发。
   callback 不接收参数，由调用方自行通过 subscribe 获取最新数据。
   返回注销函数。"
  [app-id callback]
  (let [id (keyword (str (gensym "listener")))]
    (swap! store-listeners update app-id (fnil assoc {}) id callback)
    #(swap! store-listeners update app-id dissoc id)))

(defn- notify-listeners [app-id]
  (when-let [listeners (get @store-listeners app-id)]
    (doseq [callback (vals listeners)]
      (callback))))

;; ── 派发（事件带 app-id 隔离） ─────────────────
(defn dispatch
  "向指定应用实例派发事件。事件向量：[event-id & args]。
   app-id 和 event-id 联合定位唯一的事件处理器。
   从 app-id 对应的 getter 获取当前 db，调用处理器，用 setter 写回。"
  [app-id event-v]
  (let [store (get @stores app-id)]
    (when (nil? store)
      (throw (ex-info (str "Store not registered: " app-id) {:app-id app-id})))
    (let [event-id (first event-v)
          handler  (get-in @event-handlers [app-id event-id])]   ;; <-- 两级查找
      (when (nil? handler)
        (throw (ex-info (str "No handler for event [" event-id "] in app-id: " app-id)
                        {:app-id app-id :event-id event-id})))
      (let [args   (rest event-v)
            old-db ((:getter store))
            new-db (handler old-db args)]
        ((:setter store) new-db)
        (notify-listeners app-id)
        nil))))

;; ── 订阅（拉取，带 app-id 隔离） ──────────────
(defn subscribe
  "从指定应用实例同步读取订阅数据。
   app-id   : 应用实例
   query-id : 在该 app-id 中注册的订阅标识
   params   : 传递给查询函数的额外参数"
  [app-id query-id & params]
  (let [store (get @stores app-id)]
    (when (nil? store)
      (throw (ex-info (str "Store not registered: " app-id) {:app-id app-id})))
    (let [query-fn (get-in @subscriptions [app-id query-id])]   ;; <-- 两级查找
      (when (nil? query-fn)
        (throw (ex-info (str "No subscription '" query-id "' for app-id: " app-id)
                        {:app-id app-id :query-id query-id})))
      (let [db ((:getter store))]
        (apply query-fn db params)))))