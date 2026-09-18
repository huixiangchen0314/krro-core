(ns top.kzre.krro.core.message
  "极简消息存储。提供 message / warn / error 发送消息，保留最近 N 条（默认100）。
   应用层通过 drain-messages! 或 get-messages 获取消息。")

(defonce ^:private max-messages 100)
(defonce messages (atom []))

(defn set-max-messages!
  "设置消息缓冲区最大容量。立即裁剪已有消息。"
  [n]
  (when-not (pos-int? n)
    (throw (ex-info "max-messages must be positive int" {:n n})))
  (alter-var-root #'max-messages (constantly n))
  (swap! messages
         (fn [buf]
           (if (> (count buf) n)
             ;; vec 包裹——打断 SubVector 引用链
             (vec (take-last n buf))
             buf))))

(defn coerce
  "把参数转成可安全拼接的字符串。
   非字符串用 pr-str + try/catch——防循环引用 / 栈溢出。
   宏内部使用——不作为公共 API。"
  [x]
  (if (string? x)
    x
    (try
      (pr-str x)
      (catch Throwable _
        (str "#<unprintable:" (.getName (class x)) ">")))))

(defn push-message [type content]
  (let [entry {:content content :type type}]
    (swap! messages
           (fn [buf]
             (let [new-buf (conj buf entry)
                   n       (count new-buf)]
               (if (> n max-messages)
                 ;; vec 包裹——打断 SubVector 引用链
                 (vec (subvec new-buf (- n max-messages)))
                 new-buf))))))

(defmacro message [& parts]
  `(push-message :info (str ~@(map (fn [p] `(coerce ~p)) parts))))

(defmacro warn [& parts]
  `(push-message :warn (str ~@(map (fn [p] `(coerce ~p)) parts))))

(defmacro error [& parts]
  `(push-message :error (str ~@(map (fn [p] `(coerce ~p)) parts))))
