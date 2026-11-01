(ns top.kzre.krro.core.util.ref-count
  "全局引用计数管理工具。


   语义（所有异常路径都直接抛出 ex-info，无日志）：
   - acquire：
     * key 已存在                    -> 计数 +1（携带的 on-release 若与已有的不同则抛异常）；
     * key 不存在且提供 on-release   -> 创建计数 1；
     * key 不存在且未提供 on-release -> 抛异常（不知道归零时该做什么）。
     返回一个幂等的 release 函数：同一个函数多次调用只释放一次。
   - release：底层单次减一，非幂等；归 0 时从 registry 移除并调用 on-release。
     key 不存在时抛异常。on-release 抛出的异常直接传播（此时 registry 已清理干净）。
     业务代码请优先使用 acquire 返回的 release 函数，不要直接调 release。
   - dump：查看当前计数快照，便于排查泄漏。")

;; 全局互斥锁，保证 counts-atom 的读-改-写与"归零决策"原子化。
;; 用 defonce 避免 dev reload 时替换锁对象。
(defonce ^:private lock* (Object.))

;; key -> {:count n :on-release f}
(defonce ^:private counts-atom (atom {}))

(defn release
  "引用计数 -1。归 0 时移除并调用 on-release，返回释放后的计数。
   key 不存在时抛 ex-info。on-release 抛出的异常会直接传播（registry 已清理）。
   非幂等，业务侧优先用 acquire 返回的函数。"
  [key]
  (let [[status on-release]
        (locking lock*
          (if-let [{:keys [count on-release]} (get @counts-atom key)]
            (if (<= count 1)
              (do (swap! counts-atom dissoc key)
                  [:removed on-release])
              (do (swap! counts-atom update-in [key :count] dec)
                  [:dec nil]))
            [:missing nil]))]
    (case status
      :missing
      (throw (ex-info "ref-count: release on unknown key" {:key key}))

      :dec
      (get-in @counts-atom [key :count])

      :removed
      ;; 回调在锁外执行，避免回调里再次 acquire/release 造成死锁；
      ;; 异常不捕获，直接抛给调用方。
      (do
        (when on-release (on-release))
        0))))


(defn- acquire*
  "auto-init? 为 true 时 key 不存在则创建；false 时 key 不存在则抛异常。"
  [key resource-closure auto-init?]
  (let [released?  (atom false)
        release-fn (fn []
                     (when (compare-and-set! released? false true)
                       (release key)))]
    (locking lock*
      (if (get @counts-atom key)
        (swap! counts-atom update-in [key :count] inc)
        (if auto-init?
          (swap! counts-atom assoc key
                 {:key key
                  :count 1
                  :on-release (resource-closure)})
          (throw (ex-info "ref-count: acquire on unknown key; use (acquire key resource-closure) to create it first"
                          {:key key})))))
    release-fn))


(defn acquire
  "引用计数 +1，返回幂等的 release 函数。
    resource-closure :: ()-> dispose-fn
   (acquire key)           ;; 纯加计数：key 必须已存在，否则抛 :unknown-key
   (acquire key resource-closure);; 加计数或创建：resource-closure 必须是函数；
                           ;;   key 已存在时若 resource-closure 与创建时的不 identical 则抛异常。"
  ([key]
   (acquire* key nil false))
  ([key resource-closure]
   (when-not (ifn? resource-closure)
     (throw (ex-info "ref-count: resource-closure must be a function (use (acquire key) for count-only acquire)"
                     {:key key :resource-closure resource-closure})))
   (acquire* key resource-closure true)))


(defn dump
  "无参：返回全部 {:key {:count n :on-release f}} 的快照；
   单参：返回指定 key 的条目。"
  ([] @counts-atom)
  ([key] (get @counts-atom key)))