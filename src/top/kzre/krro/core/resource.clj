(ns top.kzre.krro.core.resource
  "统一的编解码系统，上下文驱动的编码，纯数据驱动的解码。
   编码器根据 ctx (keyword) 决定序列化策略（如 :memory/:project-save/:vcs）。
   解码器无需上下文，代理 map 自带足够的类型和数据信息。
   注册的编解码器签名为：
     encoder: (fn [obj ctx] -> proxy-map)
     decoder: (fn [proxy-map] -> obj)
   pred 可以是函数或 Class，若为 Class 则自动优化为 instance? 检查并建立快速索引。"
  (:require [top.kzre.krro.core.message :as msg])
  (:import (clojure.lang IDeref IPersistentMap IRecord)
           (java.net URI)
           (java.util Date UUID)))

;; ── 工具：区分普通 Map 和 DefRecord ────────────────
(defn primitive-map?
  "判断是否为普通不可变 map（非 defrecord）。"
  [x]
  (and (map? x)
       (not (instance? IRecord x))
       (instance? IPersistentMap x)))

;; ── 编解码注册表 ──────────────────────────────────
(defonce codec-registry (atom {}))
(defonce class-codec-map (atom {}))   ;; Class → type-kw 快速索引

(defn register-codec!
  "注册一个编解码器对。type-kw 为 :krro/type 的值。
   pred:   可以是函数 (fn [obj] -> boolean?) 或 Class（自动转为 instance? 检查）
   encoder: (fn [obj ctx] -> proxy-map)
   decoder: (fn [proxy-map] -> obj)"
  [type-kw pred encoder decoder]
  {:pre [(keyword? type-kw)
         (or (ifn? pred) (class? pred))   ;; 允许 Class 类型
         (ifn? encoder)
         (ifn? decoder)]}
  (let [;; 若 pred 是 Class，生成等效的 instance? 函数
        pred-fn (if (class? pred)
                  (fn [obj] (instance? pred obj))
                  pred)
        ;; 自动包装旧式单参数编码器
        wrap-encoder (fn [f]
                       (if (and f (= (count (first (:arglists (meta f)))) 1))
                         (fn [obj _ctx] (f obj))
                         f))]
    (swap! codec-registry assoc type-kw {:encoder (wrap-encoder encoder)
                                         :decoder decoder
                                         :pred pred-fn})
    ;; 若 pred 是 Class，则建立 Class → type-kw 的快速索引
    (when (class? pred)
      (swap! class-codec-map assoc pred type-kw))))

;; ── 内部查找编码器（利用 Class 快速索引） ────────────────────────────────
(defn- try-encode-object
  "为给定对象自动查找第一个可成功编码的注册编码器，传递上下文 ctx。
   优先使用对象 class 的快速索引，避免遍历 pred 函数。"
  [obj ctx]
  (if-let [type-kw (get @class-codec-map (class obj))]
    ;; 快速路径：直接通过 class 找到对应的编码器
    (when-let [{:keys [encoder]} (get @codec-registry type-kw)]
      (try
        (let [encoded (encoder obj ctx)]
          (when (and (primitive-map? encoded) (= (:krro/type encoded) type-kw))
            encoded))
        (catch Exception _ nil)))
    ;; 慢速路径：遍历所有注册的 pred 函数
    (some (fn [[type-kw {:keys [pred encoder]}]]
            (when (pred obj)
              (try
                (let [encoded (encoder obj ctx)]
                  (when (and (primitive-map? encoded) (= (:krro/type encoded) type-kw))
                    encoded))
                (catch Exception _ nil))))
          @codec-registry)))

(defn encode-object
  "尝试为给定对象自动查找并应用编码器，传递上下文 ctx。"
  [obj ctx]
  (try-encode-object obj ctx))

;; ── 编码（自顶向下，支持显式类型和上下文） ─────────────────
(defn encode-with-type
  "使用指定的 type-kw 调用对应编码器，传递上下文 ctx。"
  [obj type-kw ctx]
  (if-let [{:keys [encoder]} (get @codec-registry type-kw)]
    (try
      (let [encoded (encoder obj ctx)]
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
  "自顶向下递归编码。未注册编码器的非标量对象将立即抛出异常。
   ctx 为上下文 keyword，默认 :memory。"
  ([data] (encode data :memory))
  ([data ctx]
   (cond
     (primitive-map? data) (into {} (map (fn [[k v]] [k (encode v ctx)]) data))
     (vector? data) (mapv #(encode % ctx) data)
     (seq? data)    (map #(encode % ctx) data)
     (set? data)    (into #{} (map #(encode % ctx) data))

     ;; EDN 原生标量：直接通过
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

     ;; 其他 Java 对象：尝试编码
     :else
     (let [encoded (try-encode-object data ctx)]
       (if encoded
         (encode encoded ctx)   ;; 递归编码代理 map
         (throw (ex-info (str "No encoder found for object: " (pr-str data))
                         {:object data
                          :type   (type data)})))))))

;; ── 解码（自底向上惰性，无需上下文） ──────────────────────────
(defn- decode*
  "对已处理子节点的代理 map 执行实际解码，支持递归解码。无需上下文。"
  ([m] (decode* m 5))
  ([m depth]
   (if (pos? depth)
     (if-let [type-kw (:krro/type m)]
       (if-let [{:keys [decoder]} (get @codec-registry type-kw)]
         (let [result (try (decoder m)
                           (catch Exception e
                             (msg/error (str "Decode failed for type " type-kw ": " (.getMessage e)))
                             m))]
           (if (and (primitive-map? result) (:krro/type result))
             (decode* result (dec depth))
             result))
         m)
       m)
     m)))

(defn- shallow-realize
  "递归强制所有嵌套集合中的 delay，返回完全具体化的版本。"
  [x]
  (cond
    (instance? IDeref x) (shallow-realize @x)
    (map? x) (into {} (map (fn [[k v]] [k (shallow-realize v)]) x))
    (vector? x) (mapv shallow-realize x)
    (seq? x) (doall (map shallow-realize x))
    (set? x) (into #{} (map shallow-realize x))
    :else x))

(defn- lazy-decode
  "自底向上惰性解码，返回的结构中代理 map 被 delay 替换。无需上下文。"
  [m]
  (cond
    (primitive-map? m)
    (let [processed (into {} (map (fn [[k v]] [k (lazy-decode v)]) m))]
      (if (:krro/type m)
        (delay (decode* (shallow-realize processed)))
        processed))

    (vector? m) (mapv lazy-decode m)
    (seq? m)    (map lazy-decode m)
    (set? m)    (into #{} (map lazy-decode m))
    :else       m))

(defn decode
  "对数据创建自底向上惰性解码包装。无上下文，纯数据驱动。"
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
  "将项目原子中指定路径的代理数据具体化为可用对象。解码无需上下文。"
  [project-atom ks]
  (let [encoded (get-in @project-atom ks)]
    (when (some? encoded)
      (let [realized (-> encoded decode realize)]
        (swap! project-atom assoc-in ks realized)
        realized))))

(defn deactivate-resource!
  "将项目原子中指定路径的活跃对象编码为代理 map。
   编码时可指定上下文 ctx，默认为 :project-save，用于控制持久化策略。"
  ([project-atom ks] (deactivate-resource! project-atom ks :project-save))
  ([project-atom ks ctx]
   (let [val (get-in @project-atom ks)]
     (when (some? val)
       (let [encoded (encode val ctx)]
         (swap! project-atom assoc-in ks encoded)
         encoded)))))

(defn get-in-project!
  "从项目原子中获取指定路径的值，透明按需激活。解码无需上下文。"
  ([project-atom ks] (get-in-project! project-atom ks nil))
  ([project-atom ks not-found]
   (if-let [val (get-in @project-atom ks)]
     (if (and (primitive-map? val) (:krro/type val))
       (activate-resource! project-atom ks)
       val)
     not-found)))