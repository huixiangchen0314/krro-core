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
  (and (map? x)
       (not (instance? IRecord x))
       ;; 进一步检查是否为持久化 map（排除 Java 的 HashMap 等）
       (instance? IPersistentMap x)))

;; ── 编解码注册表 ──────────────────────────────────
(defonce codec-registry (atom {}))

(defn register-codec!
  "注册一个编解码器对。type-kw 为 :krro/type 的值。
   pred:   (fn [obj] -> boolean?) 类型判定函数
   encoder: (fn [obj] -> proxy-map)
   decoder: (fn [proxy-map] -> obj 或 delay)"
  [type-kw pred encoder decoder]
  {:pre [(keyword? type-kw)
         (ifn? pred)
         (ifn? encoder)
         (ifn? decoder)]}
  (swap! codec-registry assoc type-kw {:encoder encoder
                                       :decoder decoder
                                       :pred pred}))

;; ── 内部查找编码器 ────────────────────────────────
(defn- try-encode-object
  "为给定对象自动查找第一个可成功编码的注册编码器。
   优先使用 pred 过滤，避免无效尝试。
   返回编码后的代理 map，若找不到则返回 nil。"
  [obj]
  (some (fn [[type-kw {:keys [pred encoder]}]]
          ;; 如果提供了 pred，则必须先通过类型检查
          (when  (pred obj)
            (try
              (let [encoded (encoder obj)]
                (when (and (primitive-map? encoded) (= (:krro/type encoded) type-kw))
                  encoded))
              (catch Exception _ nil))))
        @codec-registry))

(defn encode-object
  "尝试为给定对象自动查找并应用编码器，返回代理 map。
   若找不到合适的编码器则返回 nil。"
  [obj]
  (try-encode-object obj))

;; ── 编码（自顶向下，支持显式类型） ─────────────────
(defn encode-with-type
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
  "自顶向下递归编码。未注册编码器的非标量对象将立即抛出异常。"
  ([data]
   (encode data nil))
  ([data type-kw]
   (cond
     (primitive-map? data) (into {} (map (fn [[k v]] [k (encode v)]) data))
     (vector? data) (mapv #(encode % type-kw) data)
     (seq? data)    (map #(encode % type-kw) data)
     (set? data)    (into #{} (map #(encode % type-kw) data))

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
     (let [encoded (if type-kw
                     (encode-with-type data type-kw)
                     (try-encode-object data))]
       (if encoded
         ;; 对编码器返回的代理 map 递归编码，保证完全性
         (encode encoded)
         ;; 找不到编码器 → 直接报错，防止污染数据
         (throw (ex-info (str "No encoder found for object: " (pr-str data))
                         {:object data
                          :type   (type data)})))))))

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


(defn- shallow-realize
  "递归强制所有嵌套集合中的 delay，返回完全具体化的版本。"
  [x]
  (cond
    (instance? IDeref x) (shallow-realize @x)   ; force delay 并继续处理结果
    (map? x) (into {} (map (fn [[k v]] [k (shallow-realize v)]) x))
    (vector? x) (mapv shallow-realize x)
    (seq? x) (doall (map shallow-realize x))
    (set? x) (into #{} (map shallow-realize x))
    :else x))

(defn- lazy-decode
  "自底向上惰性解码：递归处理所有子节点，若当前节点是代理则返回 delay。
   当父 delay 被 force 时，其所有后代 delay 都会被递归强制，解码器得到完全具体化的树。"
  [m]
  (cond
    (primitive-map? m)
    (let [processed (into {} (map (fn [[k v]] [k (lazy-decode v)]) m))]
      (if (:krro/type m)
        (delay (decode* (shallow-realize processed)))  ;; 递归强制所有子 delay
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
  "将项目原子中指定路径的代理数据（或惰性包装）具体化为可用的资源对象，并原地替换。
   这是从纯数据（代理 map 或 delay）到活跃 Java 对象的统一入口。

   参数：
     project-atom : 项目原子 (Atom)，包含不可变数据树
     ks           : get-in 路径向量，指向要激活的数据节点

   返回值：
     若路径存在且值非 nil，返回具体化后的对象；否则返回 nil。
     具体化过程会递归 force 所有惰性包装并调用注册的解码器。

   副作用：
     - 原子会被更新：路径处的值被替换为具体化后的对象。
     - 解码器可能产生网络请求、文件 I/O 或 GPU 资源分配。

   适用场景：
     - 当你知道某个路径下的数据是经资源系统编码的代理（:krro/type 标记）
       或在惰性解码树中为 delay 时，使用此函数强制获取可用的具体对象。
     - 常用于需要立即使用大型资源（图像、画布等）的激活过程。
     - 也可用于惰性树中任何嵌套资源的强制求值（通过指定深层路径）。

   限制与注意：
     - 即使路径处的值已经是具体对象，也会进行一次解码/realize 流程，
       尽管结果通常幂等，但会产生不必要的开销。若想避免这种开销，
       请考虑使用 get-in-project!。
     - 如果路径处的数据不是代理且不支持解码，则可能返回原始数据。
     - 写回原子的操作不保证事务性，与其他并发修改需协调。"
  [project-atom ks]
  (let [encoded (get-in @project-atom ks)]
    (when (some? encoded)
      (let [realized (-> encoded decode realize)]
        (swap! project-atom assoc-in ks realized)
        realized))))

(defn deactivate-resource!
  "将项目原子中指定路径的活跃对象编码为适合持久化的纯数据代理 map，并原地替换。
   这是从活跃对象到可序列化数据的统一出口。

   参数：
     project-atom : 项目原子 (Atom)
     ks           : get-in 路径向量，指向要反激活的数据节点

   返回值：
     若路径存在且值非 nil，返回编码后的代理 map（通常包含 :krro/type）；
     否则返回 nil。

   副作用：
     - 原子被更新：路径处的活跃对象被替换为代理 map。
     - 编码过程可能触发数据拷贝或序列化操作，但不会修改原对象。

   适用场景：
     - 项目持久化前，将内存中的资源对象统一转换为代理数据，以便写入 EDN 或数据库。
     - 释放资源对象占用的非托管资源（如 GPU 内存），仅保留轻量代理。
     - 手动控制资源生命周期：先 deactivate 再删除，或延迟加载策略的一部分。

   限制与注意：
     - 编码后的代理 map 是普通 Clojure 数据结构，不再持有原生资源句柄。
     - 如果对象类型未注册对应的编码器，encode 会抛出异常。
     - 写回原子不会自动触发垃圾回收，但会解除对原对象的强引用。
     - 该操作不对原子中其他路径产生影响，如果需要递归反激活整个子树，
       请配合 encode 遍历。"
  [project-atom ks]
  (let [val (get-in @project-atom ks)]
    (when (some? val)
      (let [encoded (encode val)]
        (swap! project-atom assoc-in ks encoded)
        encoded))))

(defn get-in-project!
  "从项目原子中获取指定路径的值，并提供透明的按需解码。
   如果路径处的值是一个资源代理 map（primitive-map? 且含有 :krro/type），
   则自动调用 activate-resource! 将其解码为具体对象，并写回原子；
   否则直接返回原值（可能是已解码的对象或纯数据）。

   参数：
     project-atom : 项目原子 (Atom)
     ks           : get-in 路径向量
     not-found    : 可选默认值，当路径不存在或值为 nil 时返回 (默认 nil)

   返回值：
     如果路径处是代理 map，返回解码后的具体对象；
     如果是其他数据或已解码对象，返回原值；
     如果路径缺失或值为 nil，返回 not-found。

   副作用：
     仅当触发自动激活时，原子会被更新（路径处的代理替换为具体对象），
     否则无副作用。

   适用场景：
     - 读取可能已被编码的资源或简单配置数据，无需预先判断类型。
     - 当顶层数据可能为纯数据（字符串、向量）或已解码对象时，
       避免不必要的解码开销和原子写操作。
     - 在性能敏感路径中，若大部分读取已经处于激活状态，此函数可以
       跳过 decode/realize 步骤，仅返回现有对象。

   限制与注意：
     - 如果路径处的值是一个普通的 map 且碰巧不含 :krro/type，即使其内部
       嵌套了代理数据，本函数也不会触发解码，可能导致后续使用出错。
       正确的做法是确保经过 encode 递归处理后的代理 map 都带标记。
     - 对于惰性解码树中的 delay 值，本函数不会自动 force，可能返回 delay 对象。
       这是因为 delay 不是 primitive-map，不满足自动解码条件。
       如需强制具体化，请使用 activate-resource!。
     - 本函数为读取优化，不保证与并发写入的原子性。
     - 自动激活成功后，原子中存储的是解码后的具体对象；如果该对象后续被修改，
       需要再次调用 deactivate-resource! 才能持久化。"
  ([project-atom ks]
   (get-in-project! project-atom ks nil))
  ([project-atom ks not-found]
   (if-let [val (get-in @project-atom ks)]
     (if (and (primitive-map? val) (:krro/type val))
       (activate-resource! project-atom ks)
       val)
     not-found)))