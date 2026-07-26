(ns top.kzre.krro.core.reframe
  "多实例事件流框架，数据存取完全委托给外部注册的 getter/setter。
   事件和订阅均通过 app-id 隔离，不同实例互不冲突。
   支持纯事件处理器 (reg-event) 以及返回副作用描述的事件处理器 (reg-event-fx)。")

;; ── 纯事件处理器注册表 ──────────────────────────
(def ^:private event-handlers (atom {}))

(defn reg-event
  "注册纯事件处理器。handler 接收 db 和事件参数向量，返回新的 db。
   同一 app-id 内，事件 ID 不能同时注册为纯事件和 fx 事件。"
  [app-id event-id handler]
  (when (get-in @event-handlers-fx [app-id event-id])
    (throw (ex-info (str "Event already registered as fx event: " event-id)
                    {:app-id app-id, :event-id event-id})))
  (swap! event-handlers assoc-in [app-id event-id] handler))

(defn unreg-event
  "取消注册纯事件。"
  [app-id event-id]
  (swap! event-handlers #(update-in % [app-id] dissoc event-id)))

;; ── Fx 事件处理器注册表 ──────────────────────────
(def ^:private event-handlers-fx (atom {}))

(defn reg-event-fx
  "注册 Fx 事件处理器。处理器接收 db 和事件参数，返回 {:db new-db, :fx [[fx-id & args] ...]}。
   同一 app-id 内，事件 ID 不能同时注册为纯事件和 fx 事件。"
  [app-id event-id handler-fx]
  (when (get-in @event-handlers [app-id event-id])
    (throw (ex-info (str "Event already registered as pure event: " event-id)
                    {:app-id app-id, :event-id event-id})))
  (swap! event-handlers-fx assoc-in [app-id event-id] handler-fx))

(defn unreg-event-fx
  "取消注册 Fx 事件。"
  [app-id event-id]
  (swap! event-handlers-fx #(update-in % [app-id] dissoc event-id)))

;; ── Fx 副作用执行器注册表（全局共享） ────────────
(def ^:private fx-handlers (atom {}))

(defn reg-fx
  "注册副作用执行器。handler 接收 app-id 和 args，执行具体副作用。
   例如 (reg-fx :http-post (fn [app-id url body] ...))。"
  [fx-id handler]
  (swap! fx-handlers assoc fx-id handler))

(defn unreg-fx
  "取消注册副作用执行器。"
  [fx-id]
  (swap! fx-handlers dissoc fx-id))

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

;; ── 内部辅助：执行副作用向量 ────────────────────
(defn- execute-fx [app-id fx-vec]
  (doseq [[fx-id & args] fx-vec]
    (if-let [fx-fn (get @fx-handlers fx-id)]
      (apply fx-fn app-id args)
      (throw (ex-info (str "Unknown fx: " fx-id) {:fx-id fx-id})))))

;; ── 派发（纯事件与 Fx 事件统一入口） ──────────
(defn dispatch
  "向指定应用实例派发事件。事件向量：[event-id & args]。
   首先尝试纯事件处理器，若不存在则尝试 Fx 事件处理器。
   纯事件：直接用新 db 调用 setter。
   Fx 事件：处理返回 {:db new-db, :fx [...]}，先设置 db，再执行副作用，
   最后通知监听器。"
  [app-id event-v]
  (let [store (get @stores app-id)]
    (when (nil? store)
      (throw (ex-info (str "Store not registered: " app-id) {:app-id app-id})))
    (let [event-id (first event-v)
          args     (rest event-v)
          old-db   ((:getter store))
          handler-pure (get-in @event-handlers [app-id event-id])
          handler-fx   (get-in @event-handlers-fx [app-id event-id])]
      (cond
        handler-pure
        (let [new-db (apply handler-pure old-db args)]
          ((:setter store) new-db)
          (notify-listeners app-id))

        handler-fx
        (let [{:keys [db fx]} (apply handler-fx old-db args)]
          (when-not db
            (throw (ex-info "Fx handler must return a :db key." {:event event-v})))
          ((:setter store) db)
          (when fx
            (execute-fx app-id fx))
          (notify-listeners app-id))

        :else
        (throw (ex-info (str "No handler for event [" event-id "] in app-id: " app-id)
                        {:app-id app-id, :event-id event-id}))))
    nil))

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
    (let [query-fn (get-in @subscriptions [app-id query-id])]
      (when (nil? query-fn)
        (throw (ex-info (str "No subscription '" query-id "' for app-id: " app-id)
                        {:app-id app-id :query-id query-id})))
      (let [db ((:getter store))]
        (apply query-fn db params)))))