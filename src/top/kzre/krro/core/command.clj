(ns top.kzre.krro.core.command
  "全局命令注册与执行。命令以关键字标识，存储为包含 handler 和可选交互规范的 map。
   若调用 execute-command! 时不传参数且命令有 :interactive 规范，则自动通过交互器收集参数。
   错误信息通过消息系统输出，不再抛出异常。"
  (:require [top.kzre.krro.core.interactive :as i]
            [top.kzre.krro.core.message :as msg]
            [top.kzre.krro.core.project :as proj]))

(defonce command-registry (atom {}))

(defn register-command!
  [id handler & {:keys [description interactive]}]
  (swap! command-registry assoc id
         {:handler     handler
          :id          id
          :description description
          :interactive (vec interactive)}))

(defn lookup-command [id]
  (get @command-registry id))

(defn- collect-args [interactor spec]
  (mapv (fn [s]
          (let [;; 标准化为 [type prompt & opts]
                [type prompt & opts] (if (keyword? s)
                                       [s "Enter value: "]
                                       s)
                prompt (or prompt "Enter value: ")]
            (case type
              :string (i/read-text interactor prompt)
              :number (i/read-number interactor prompt)
              :choice (let [options-fn (first opts)
                            options (if (fn? options-fn) (options-fn) options-fn)
                            choice-prompt (or (second opts) "Choose: ")]
                        (i/read-choice interactor choice-prompt options))
              (do
                (msg/error (str "Unsupported interactive spec: " type))
                nil))))
  spec))

(defn execute-command!
  "执行命令。
   - 若提供了额外 args，则直接传递给 handler。
   - 若未提供 args 且命令有 :interactive 规范，则通过交互器收集参数后执行。
   - 若既无 args 又无交互规范，则无参执行。
   命令签名为 (fn [project & args] -> new-project)。"
  ([id]
   (if-let [cmd (lookup-command id)]
     (let [handler   (:handler cmd)
           spec      (:interactive cmd)
           interactor (i/interactor)]
       (if (and spec (seq spec))
         (if interactor
           (let [args (collect-args interactor spec)]
             (if (some nil? args)
               nil
               (apply execute-command! id args)))
           (msg/error (str "Command " id " requires interactive args, but no interactor installed")))
         ;; 零参数直接执行
         (try
           (let [old-value @proj/project]
             (try
               (swap! proj/project handler)
               (catch Exception e
                 (reset! proj/project old-value)
                 (msg/error (str "Command execution failed: " id " - " (.getMessage e))))))
           (catch Exception e
             (msg/error (str "Command execution failed: " id " - " (.getMessage e)))))))
     (msg/error (str "Unknown command: " id))))
  ([id & args]
   (if-let [cmd (lookup-command id)]
     (let [handler (:handler cmd)
           old-value @proj/project]
       (try
         (apply swap! proj/project handler args)
         (catch Exception e
           (reset! proj/project old-value)
           (msg/error (str "Command execution failed: " id " with args " args " - " (.getMessage e))))))
     (msg/error (str "Unknown command: " id)))))

(defmacro defcmd
  "定义命令并注册。语法：
   (defcmd name [project & args] :description \"doc\" :interactive [:string] body...)
   关键字选项 :description, :interactive 可放在参数向量之后任意位置。"
  [name & body]
  (let [[params & opt+body] body
        [opts body-forms]
        (loop [remaining opt+body opts {} acc-body []]
          (if (empty? remaining)
            [opts (vec acc-body)]
            (let [f (first remaining)]
              (if (keyword? f)
                (case f
                  (:description :interactive)
                  (recur (drop 2 remaining) (assoc opts f (second remaining)) acc-body)
                  (recur [] opts (vec remaining)))
                (recur [] opts (vec remaining))))))
        cmd-id (keyword (str *ns*) (str name))]
    `(let [handler# (fn ~params ~@body-forms)]
       (register-command! ~cmd-id handler#
                          :description ~(:description opts)
                          :interactive ~(:interactive opts))
       (def ~(vary-meta name assoc :command/id cmd-id) handler#))))