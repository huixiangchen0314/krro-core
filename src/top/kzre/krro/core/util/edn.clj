(ns top.kzre.krro.core.util.edn
  "纯粹的流式 EDN 写入工具。不依赖任何编解码系统。
   通过 :object-encoder 回调将自定义对象转换为可写形式。"
  (:import [java.io Writer]
           (java.net URI)
           (java.util Date UUID)))

(defn- write-scalar [x ^Writer w]
  (.write w (pr-str x)))

(defn- write-val
  [val object-encoder ^Writer w]
  (cond
    (map? val)
    (do
      (.write w "{")
      (loop [[[k v] & more] (seq val)]
        (when k
          (write-scalar k w)  ; 键是标量，直接写出
          (.write w " ")
          (write-val v object-encoder w)
          (when more (.write w ", "))
          (recur more)))
      (.write w "}"))

    (vector? val)
    (do
      (.write w "[")
      (loop [[v & more] (seq val)]
        (when v
          (write-val v object-encoder w)
          (when more (.write w " "))
          (recur more)))
      (.write w "]"))

    (seq? val)
    (do
      (.write w "(")
      (loop [[v & more] (seq val)]
        (when v
          (write-val v object-encoder w)
          (when more (.write w " "))
          (recur more)))
      (.write w ")"))

    (set? val)
    (do
      (.write w "#{")
      (loop [[v & more] (seq val)]
        (when v
          (write-val v object-encoder w)
          (when more (.write w " "))
          (recur more)))
      (.write w "}"))

    ;; EDN 原生标量：直接通过 pr-str 转为字符串输出（内存占用极小）
    (or (nil? val) (boolean? val) (number? val) (string? val)
        (keyword? val) (symbol? val) (char? val)
        (instance? Date val)
        (instance? UUID val)
        (instance? URI val))
    (write-scalar val w)

    ;; 自定义对象编码回调
    (some? object-encoder)
    (let [encoded (object-encoder val)]
      (if (identical? encoded val)
        (throw (ex-info (str "EDN encoding failed for object of type " (class val))
                        {:object val}))
        (write-val encoded object-encoder w)))

    :else
    (throw (ex-info (str "EDN encoding failed for object of type " (class val) " (no encoder callback)")
                    {:object val}))))

(defn write-edn
  "将数据 data 以 EDN 格式流式写入 Writer w。
   options:
     :object-encoder   (fn [obj] -> serializable-val)  用于将自定义对象转换为可写形式。
                       返回原对象表示无法编码，将直接使用 pr-str 输出。"
  [data ^Writer w & {:keys [object-encoder]}]
  (write-val data object-encoder w)
  nil)