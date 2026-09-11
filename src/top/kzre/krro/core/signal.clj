(ns top.kzre.krro.core.signal
  (:import (clojure.lang IDeref)))

(declare mark-dirty!)

;; ═══════════════════════════════════════════════
;; Signal 类型
;; ═══════════════════════════════════════════════

(deftype Signal [compute-fn
                 inputs            ; vector of Signal
                 dependents        ; atom: #{Signal}
                 watches           ; atom: {key fn}
                 ^:volatile-mutable value
                 ^:volatile-mutable dirty
                 ^:volatile-mutable disposed]
  IDeref
  (deref [_]
    (if (or (not dirty) disposed)
      value
      (let [input-vals (mapv deref inputs)
            new-val (apply compute-fn input-vals)
            old-val value]
        (set! value new-val)
        (set! dirty false)
        ;; 值真的变化时才往下游传播（避免无意义递归）
        (when (not= new-val old-val)
          (doseq [dep @dependents]
            (mark-dirty! dep)))
        new-val))))

;; ═══════════════════════════════════════════════
;; 构造
;; ═══════════════════════════════════════════════

(defn signal
  "创建 Signal。
   compute-fn: (fn [& input-vals] → value)
   inputs:     依赖的 Signal 向量"
  [compute-fn inputs]
  (let [inputs (vec inputs)
        sig (->Signal compute-fn
                      inputs
                      (atom #{})
                      (atom {})
                      ::unset
                      true       ; 初始 dirty，首次 deref 时计算
                      false)]
    (doseq [in inputs]
      (swap! (.-dependents in) conj sig))
    sig))

(defn source
  "创建根源。无输入，getter 无参数。
   例子：(def db-sig (source #(deref db)))"
  [getter]
  (signal (fn [] (getter)) []))

;; ═══════════════════════════════════════════════
;; 标脏传播
;; ═══════════════════════════════════════════════

(defn mark-dirty!
  "标记 Signal 为脏，触发 watches，递归标脏下游。

   - 已 dirty 的不重复触发（幂等）
   - 已 dispose 的忽略
   - watch 回调在标脏时同步触发，不携带值"
  [^Signal sig]
  (when (and (not (.-dirty sig)) (not (.-disposed sig)))
    (set! (.-dirty sig) true)
    (doseq [f (vals @(.-watches sig))]
      (try (f) (catch Throwable _)))
    (doseq [dep @(.-dependents sig)]
      (mark-dirty! dep)))
  nil)

;; ═══════════════════════════════════════════════
;; 监听
;; ═══════════════════════════════════════════════

(defn watch!
  "监听 Signal。f 无参数，标脏时被调用。
   f 收到信号后应主动 deref 拉取。
   返回取消函数。"
  [^Signal sig key f]
  (swap! (.-watches sig) assoc key f)
  (fn [] (swap! (.-watches sig) dissoc key)))

(defn unwatch!
  "取消监听。"
  [^Signal sig key]
  (swap! (.-watches sig) dissoc key))

;; ═══════════════════════════════════════════════
;; 生命周期
;; ═══════════════════════════════════════════════

(defn dispose!
  "释放 Signal。断开与上游的连接。
   已释放的 Signal 不再接收标脏，deref 返回上次的值。"
  [^Signal sig]
  (when-not (.-disposed sig)
    (set! (.-disposed sig) true)
    (doseq [^Signal in (.-inputs sig)]
      (swap! (.-dependents in) disj sig))
    (reset! (.-watches sig) {})))

(defn disposed? [^Signal sig]
  (.-disposed sig))

(defn dirty? [^Signal sig]
  (.-dirty sig))

;; ═══════════════════════════════════════════════
;; 检查与调试
;; ═══════════════════════════════════════════════

(defn inputs [^Signal sig]
  (.-inputs sig))

(defn dependents [^Signal sig]
  @(.-dependents sig))