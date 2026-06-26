(ns top.kzre.krro.core.command
  "全局命令注册与执行，设计灵感来自 Emacs 的 M-x。
   命令是普通 Clojure 函数，附加元数据后注册到全局表。")

(defonce ^:private command-registry (atom {}))

(defn register-command!
  "将一个函数注册为全局命令。
   id     - 命令的关键字标识
   handler - 签名为 (fn [project & args] -> new-project) 的函数
   opts   - 可选 map，:description 描述字符串"
  [id handler & {:keys [description]}]
  (swap! command-registry assoc id
         (with-meta handler {:command/id id
                             :command/description description
                             :command/name (name id)})))

(defn lookup-command
  "根据命令 id 返回对应的处理函数，不存在返回 nil。"
  [id]
  (get @command-registry id))

(defn execute-command
  "执行一个已注册的命令。
   project-atom - 全局项目 atom（将被 swap!）
   id           - 命令关键字
   args         - 传递给处理函数的额外参数"
  [project-atom id & args]
  (if-let [handler (lookup-command id)]
    (apply swap! project-atom handler args)
    (throw (ex-info "Unknown command" {:id id}))))

;; ── 轻量级命令定义宏 ────────────────────────────────
(defmacro defcmd
  "定义一个命令并自动注册。形式：
   (defcmd my-command [project x y]
     \"描述\"  ;; 可选
     (assoc project :something (+ x y)))"
  [name & body]
  (let [docstring? (string? (first body))
        doc (when docstring? (first body))
        body (if docstring? (rest body) body)
        [params & fn-body] body
        cmd-id (keyword (str *ns*) (str name))]
    `(let [handler# (fn ~params ~@fn-body)]
       (register-command! ~cmd-id handler# :description ~doc)
       (def ~(vary-meta name assoc :command/id cmd-id) handler#))))