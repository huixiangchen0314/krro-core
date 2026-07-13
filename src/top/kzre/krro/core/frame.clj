(ns top.kzre.krro.core.frame
  "Frame 抽象：每个 Frame 代表一个独立的工作空间，持有模式、键图栈。
   所有状态通过 IFrame 协议封装，不暴露内部原子。"
  (:require [top.kzre.krro.core.keymap :as km]))

(defonce frame-registry (atom {}))   ;; {frame-id Frame}



(defprotocol IFrame
  (frame-id [this] "返回 Frame 的唯一标识")
  (major-mode [this] "返回当前主模式 ID")
  (minor-modes [this] "返回当前激活的副模式集合")
  (set-major-mode! [this mode-id] "设置主模式")
  (add-minor-mode! [this mode-id] "激活副模式")
  (remove-minor-mode! [this mode-id] "停用副模式")
  (params-atom [this] "获取参数原子，方便监听.")
  (params [this] "返回当前的参数快照map")
  (param [this key] "获取 Frame 的自定义参数，可提供默认值")
  (set-param! [this key value] "设置参数")
  (remove-param! [this key] "移除参数")
  (local-customs-atom [this] "返回存储局部 custom 的原子")
  (local-customs [this] "返回局部 custom 的 map 快照")
  (set-local-custom! [this id value] "设置一个局部 custom 值")
  (remove-local-custom! [this id] "移除一个局部 custom 值"))

(defrecord Frame [id major-mode-atom minor-modes-atom params-atom local-customs-atom]
  IFrame
  (frame-id [_] id)
  (major-mode [_] @major-mode-atom)
  (minor-modes [_] @minor-modes-atom)
  (set-major-mode! [_ mode-id] (reset! major-mode-atom mode-id))
  (add-minor-mode! [_ mode-id] (swap! minor-modes-atom conj mode-id))
  (remove-minor-mode! [_ mode-id] (swap! minor-modes-atom disj mode-id))
  (params-atom [_] params-atom)
  (params [_] @params-atom)
  (param [_ key] (get @params-atom key))
  (set-param! [_ key value] (swap! params-atom assoc key value))
  (remove-param! [_ key] (swap! params-atom dissoc key))
  (local-customs-atom [_] local-customs-atom)
  (local-customs [_] @local-customs-atom)
  (set-local-custom! [_ vid value] (swap! local-customs-atom assoc vid value))
  (remove-local-custom! [_ vid] (swap! local-customs-atom dissoc vid)))


(def ^:dynamic *current-frame* nil)

(defmacro with-frame [f & body]
  `(binding [*current-frame* ~f]
     ~@body))


(defn ensure-param!
  "获取 frame 参数 key 的值。若不存在，则原子地调用 init 生成默认值并设置。
   init 仅会在首次缺失时执行一次（通过 locking 保证）。
   适用于启动时初始化 frame 局部状态。"
  [frame key init]
  (let [pa (params-atom frame)]
    (locking pa
      (let [val @pa]
        (if (contains? val key)
          (get val key)
          (let [new-val (if (fn? init) (init) init)]
            (swap! pa assoc key new-val)
            new-val))))))

(defn all-frames []
  (vals @frame-registry))

(defn frames-with-param
  "返回所有参数中指定 key 的值等于 val 的 Frame 列表。"
  [key val]
  (filter #(= (param % key) val) (all-frames)))


(defn create-frame!
  "创建一个新的 Frame，自动注册到全局表。
   可指定 :id，默认自动生成。"
  [& {:keys [id] :or {id (keyword (str "frame-" (gensym "f")))}}]
  (let [f (map->Frame {:id id
                       :major-mode-atom (atom :krro.mode/fundamental)
                       :minor-modes-atom (atom #{})
                       :params-atom (atom {})
                       :local-customs-atom (atom {})})]
    (swap! frame-registry assoc id f)
    f))

(defn destroy-frame!
  "从全局表中移除 Frame，释放资源。"
  [id]
  (swap! frame-registry dissoc id))