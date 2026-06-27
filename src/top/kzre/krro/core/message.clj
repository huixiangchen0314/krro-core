(ns top.kzre.krro.core.message
  "极简消息存储。提供 message / warn / error 发送消息，保留最近 N 条（默认100）。
   应用层通过 drain-messages! 或 get-messages 获取消息。")

(defonce ^:private max-messages 100)
(defonce messages (atom []))

(defn set-max-messages!
  "设置消息缓冲区最大容量。"
  [n]
  (alter-var-root #'max-messages (constantly n)))

(defn- add [content type]
  (let [entry {:content content :type type}]
    (swap! messages
           (fn [buf]
             (let [new-buf (conj buf entry)]
               (if (> (count new-buf) max-messages)
                 (subvec new-buf (- (count new-buf) max-messages))
                 new-buf))))))

(defn message [content] (add content :info))
(defn warn    [content] (add content :warn))
(defn error   [content] (add content :error))

