(ns top.kzre.krro.core.util.signal
  "Signal 的 Clojure 包装。保持与 Clojure 原始信号 API 一致的函数风格。"
  (:import [top.kzre.krro.core.util Signal Signal$AsyncComputeFn Signal$SyncComputeFn]))

;; ═══════════════════════════════════════════════
;; 构造
;; ═══════════════════════════════════════════════

(defn signal
  "创建同步信号。
   compute-fn: (fn [& input-vals] → value)
   inputs:     依赖的 Signal 向量"
  [compute-fn inputs]
  (let [inputs-arr (into-array Signal inputs)]
    (Signal/sync
      (reify Signal$SyncComputeFn
        (compute [_ vals]
          (apply compute-fn (seq vals))))
      inputs-arr)))

(defn async-signal
  "创建异步信号。
   compute-fn: (fn [input-vals submit] → nil)
     - input-vals: 依赖的当前值向量
     - submit:     (fn [new-val] ...) 计算完成后调用
   inputs: 依赖的 Signal 向量"
  [compute-fn inputs]
  (let [inputs-arr (into-array Signal inputs)]
    (Signal/async
      (reify Signal$AsyncComputeFn
        (compute [_ vals submit]
          (compute-fn (vec vals)
                      (fn [v] (.accept submit v)))))
      inputs-arr)))

(defn source
  "创建同步根源。getter 无参数，返回当前值。"
  [getter]
  (Signal/source
    (reify Signal$SyncComputeFn
      (compute [_ _] (getter)))))

;; ═══════════════════════════════════════════════
;; 标脏传播
;; ═══════════════════════════════════════════════

(defn mark-dirty!
  "标记信号为脏，触发 watches，递归标脏下游。幂等。"
  [^Signal s]
  (.markDirty s))

;; ═══════════════════════════════════════════════
;; attach / detach / dispose
;; ═══════════════════════════════════════════════

(defn detach!
  "从上游的 dependents 中移除自己。用于缓存节点。幂等。"
  [^Signal s]
  (.detach s))

(defn attach!
  "重新加入上游的 dependents。若输入非 READY，标脏自己。幂等。"
  [^Signal s]
  (.attach s))

(defn dispose!
  "释放信号。断开与上游的连接，清空 watches。幂等。"
  [^Signal s]
  (.dispose s))

;; ═══════════════════════════════════════════════
;; 监听
;; ═══════════════════════════════════════════════

(defn watch!
  "监听信号。f 无参数，标脏时被调用。返回取消函数。"
  [^Signal s key f]
  (.watch s key
          (reify Runnable
            (run [_] (f)))))

(defn unwatch!
  "取消监听。"
  [^Signal s key]
  (.unwatch s key))

;; ═══════════════════════════════════════════════
;; 状态查询
;; ═══════════════════════════════════════════════

(defn ready?     [^Signal s] (.isReady s))
(defn pending?   [^Signal s] (.isPending s))
(defn dirty?     [^Signal s] (.isDirty s))
(defn disposed?  [^Signal s] (.isDisposed s))
(defn attached?  [^Signal s] (.isAttached s))
(defn detached?  [^Signal s] (.isDetached s))

;; ═══════════════════════════════════════════════
;; 调试
;; ═══════════════════════════════════════════════

(defn peek-value  [^Signal s] (.peekValue s))
(defn inputs      [^Signal s] (.getInputs s))
(defn dependents  [^Signal s] (.getDependents s))
(defn watch-count [^Signal s] (.watchCount s))