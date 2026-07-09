(ns top.kzre.krro.core.resource
  "统一的编解码系统。
   - 基于 :krro/type 注册编码器/解码器（函数对）。
   - 编码 (encode) 自顶向下将对象转换为代理 map，支持显式指定目标类型。
   - 解码 (decode) 自底向上惰性：代理 map 被替换为 delay，
     其内部子值已被预先递归处理；force delay 时完成对象构造。
   - realize 可递归强制所有 delay，用于全量具体化。
   项目原子中始终存储纯数据代理 map，读取时返回惰性解码树。"
  (:require [top.kzre.krro.core.message :as msg])
  (:import (clojure.lang IDeref IPersistentMap IRecord)
           (java.net URI)
           (java.util Date UUID)))

;; ── 工具：区分普通 Map 和 DefRecord ────────────────
(defn primitive-map?
  "判断是否为普通不可变 map（非 defrecord）。"
  [x]
  (and (primitive-map? x)
       (not (instance? IRecord x))
       ;; 进一步检查是否为持久化 map（排除 Java 的 HashMap 等）
       (instance? IPersistentMap x)))

;; ── 编解码注册表 ──────────────────────────────────
(defonce codec-registry (atom {}))

(defn register-codec!
  "注册一个编解码器对。type-kw 为 :krro/type 的值。
   encoder: (fn [obj] -> proxy-map)
   decoder: (fn [proxy-map] -> obj 或 delay)"
  [type-kw encoder decoder]
  (swap! codec-registry assoc type-kw {:encoder encoder :decoder decoder}))

;; ── 内部查找编码器 ────────────────────────────────
(defn- try-encode-object
  "为给定对象自动查找第一个可成功编码的注册编码器。
   返回编码后的代理 map，若找不到则返回 nil。"
  [obj]
  (some (fn [[type-kw {:keys [encoder]}]]
          (try
            (let [encoded (encoder obj)]
              (when (and (primitive-map? encoded) (= (:krro/type encoded) type-kw))
                encoded))
            (catch Exception _ nil)))
        @codec-registry))

(defn encode-object
  "尝试为给定对象自动查找并应用编码器，返回代理 map。
   若找不到合适的编码器则返回 nil。"
  [obj]
  (try-encode-object obj))

;; ── 编码（自顶向下，支持显式类型） ─────────────────
(defn- encode-with-type
  "使用指定的 type-kw 调用对应编码器。"
  [obj type-kw]
  (if-let [{:keys [encoder]} (get @codec-registry type-kw)]
    (try
      (let [encoded (encoder obj)]
        (if (and (primitive-map? encoded) (= (:krro/type encoded) type-kw))
          encoded
          (do (msg/error (str "Encoder for " type-kw " did not return a valid proxy map"))
              obj)))
      (catch Exception e
        (msg/error (str "Encoding failed for type " type-kw ": " (.getMessage e)))
        obj))
    (do
      (msg/error (str "No encoder registered for type " type-kw))
      obj)))



(defn encode
  "自顶向下递归编码。
   单参数：自动查找编码器。
   双参数：(encode data type-kw) 使用指定的 :krro/type 进行编码，
           适用于同一对象类型有多个编码场景。
   已包含 :krro/type 的 map 视为已编码，不再处理。"
  ([data]
   (encode data nil))
  ([data type-kw]
   (cond
     (primitive-map? data)    (if (:krro/type data)
                      data
                      (into {} (map (fn [[k v]] [k (encode v)]) data)))
     (vector? data) (mapv #(encode % type-kw) data)
     (seq? data)    (map #(encode % type-kw) data)
     (set? data)    (into #{} (map #(encode % type-kw) data))

     ;; 快速路径：EDN 原生标量类型，直接保留
     (or (nil? data)
         (boolean? data)
         (number? data)
         (string? data)
         (keyword? data)
         (symbol? data)
         (char? data)
         (instance? Date data)
         (instance? UUID data)
         (instance? URI data))
     data

     :else          (if type-kw
                      (encode-with-type data type-kw)
                      (if-let [encoded (try-encode-object data)]
                        encoded
                        data)))))

;; ── 解码（自底向上惰性） ──────────────────────────
(defn- decode*
  "对已处理子节点的代理 map 执行实际解码。"
  [m]
  (if-let [type-kw (:krro/type m)]
    (if-let [{:keys [decoder]} (get @codec-registry type-kw)]
      (try (decoder m)
           (catch Exception e
             (msg/error (str "Decode failed for type " type-kw ": " (.getMessage e)))
             m))
      m)
    m))

(defn- lazy-decode
  "自底向上惰性解码：递归处理所有子节点，若当前节点是代理则返回 delay。"
  [m]
  (cond
    (primitive-map? m)
    (let [processed (into {} (map (fn [[k v]] [k (lazy-decode v)]) m))]
      (if (:krro/type m)
        (delay (decode* processed))   ;; 子节点已惰性化，force 时解码器获得 processed map
        processed))

    (vector? m) (mapv lazy-decode m)
    (seq? m)    (map lazy-decode m)
    (set? m)    (into #{} (map lazy-decode m))
    :else       m))

(defn decode
  "对数据创建自底向上惰性解码包装。返回的结构中，代理 map 被替换为 delay，
   其内部子结构已预先递归处理；force delay 时执行实际解码。"
  [data]
  (lazy-decode data))

;; ── 强制求值 ──────────────────────────────────────
(defn realize
  "递归强制所有 delay，返回完全具体化的数据。"
  [x]
  (cond
    (instance? IDeref x) (realize @x)
    (primitive-map? x) (into {} (map (fn [[k v]] [k (realize v)]) x))
    (vector? x) (mapv realize x)
    (seq? x) (map realize x)
    (set? x) (into #{} (map realize x))
    :else x))

;; ── 项目集成辅助 ──────────────────────────────────
(defn get-in-project-lazy
  "从 project 原子中获取路径，返回惰性解码后的树。"
  [project ks]
  (some-> (get-in project ks) decode))

(defn activate-resource!
  "激活项目原子中指定路径的代理数据为具体对象，并原地替换。
   若路径下无数据或值为 nil，返回 nil 且不修改原子。
   否则返回解码并 realize 后的具体对象。
   project-atom: 项目原子
   ks: get-in 路径向量"
  [project-atom ks]
  (let [encoded (get-in @project-atom ks)]
    (when (some? encoded)                     ;; 只有存在非 nil 值才处理
      (let [realized (-> encoded decode realize)]
        (swap! project-atom assoc-in ks realized)
        realized))))


(defn deactivate-resource!
  "将项目原子中指定路径的活跃对象编码为代理 map，并原地替换。
   若路径下无数据或值为 nil，返回 nil 且不修改原子。
   否则返回编码后的代理 map。
   project-atom: 项目原子
   ks: get-in 路径向量"
  [project-atom ks]
  (let [val (get-in @project-atom ks)]
    (when (some? val)
      (let [encoded (encode val)]
        (swap! project-atom assoc-in ks encoded)
        encoded))))


(defn get-in-project!
  "从项目原子中获取指定路径的值。若该值为代理 map（含有 :krro/type），
   则自动调用 activate-resource! 将其解码为具体对象并写回原子，返回该对象。
   若路径不存在或值为 nil，返回可选默认值 not-found（缺省为 nil）。
   使用示例：
     (get-in-project! proj/project [:krro.painting/canvases :default])
     (get-in-project! proj/project [:some :path] :default-value)"
  ([project-atom ks]
   (get-in-project! project-atom ks nil))
  ([project-atom ks not-found]
   (if-let [val (get-in @project-atom ks)]
     (if (and (primitive-map? val) (:krro/type val))
       (activate-resource! project-atom ks)    ;; 代理 map → 激活并返回具体对象
       val)                                    ;; 已是具体对象（或非代理 map），直接返回
     not-found)))