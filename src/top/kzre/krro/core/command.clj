(ns top.kzre.krro.core.command
  "全局命令注册与执行，设计灵感来自 Emacs 的 M-x。
   命令是普通 Clojure 函数，附加元数据后注册到全局表。")

(defonce ^:private command-registry (atom {}))

(defn register-command!
  "注册命令。id 冲突时打印警告并覆盖。"
  [id handler & {:keys [description]}]
  (swap! command-registry
         (fn [reg]
           (when (contains? reg id)
             (println "Warning: command" id "redefined."))
           (assoc reg id
                      (with-meta handler
                                 {:command/id id
                                  :command/description description
                                  :command/name (name id)})))))

(defn lookup-command [id]
  (get @command-registry id))

(defn execute-command
  "通过 swap! 执行命令，带原子保护。
   命令签名为 (fn [project & args] -> new-project)。"
  [project-atom id & args]
  (if-let [handler (lookup-command id)]
    (let [old-value @project-atom]
      (try
        (apply swap! project-atom handler args)
        (catch Exception e
          ;; 恢复旧值
          (reset! project-atom old-value)
          (throw (ex-info "Command execution failed" {:id id :args args} e)))))
    (throw (ex-info "Unknown command" {:id id}))))

(defmacro defcmd
  "轻量命令定义宏，自动注册。"
  [name & body]
  (let [[params & more] body
        doc? (string? (first more))
        doc (when doc? (first more))
        fn-body (if doc? (rest more) more)
        cmd-id (keyword (str *ns*) (str name))]
    `(let [handler# (fn ~params ~@fn-body)]
       (register-command! ~cmd-id handler# :description ~doc)
       (def ~(vary-meta name assoc :command/id cmd-id) handler#))))