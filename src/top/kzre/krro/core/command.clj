(ns top.kzre.krro.core.command
  "全局命令注册与执行。命令以关键字标识，存储为包含 handler 和可选交互规范的 map。
   若调用 execute-command! 时不传参数且命令有 :interactive 规范，则自动通过交互器收集参数。"
  (:require [top.kzre.krro.core.interactive :as i]))

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
          (cond
            (= s :string) (i/read-text interactor "Enter value: ")
            (= s :number) (i/read-number interactor "Enter number: ")
            (and (vector? s) (= (first s) :choice))
            (let [[_ opt] s
                  options (if (fn? opt) (opt) opt)]
              (i/read-choice interactor "Choose: " options))
            :else (throw (ex-info "Unsupported interactive spec" {:spec s}))))
        spec))

(defn execute-command!
  "执行命令。
   - 若提供了额外 args，则直接传递给 handler。
   - 若未提供 args 且命令有 :interactive 规范，则通过交互器收集参数后执行。
   - 若既无 args 又无交互规范，则无参执行。
   命令签名为 (fn [project & args] -> new-project)。"
  ([project-atom id]
   (if-let [cmd (lookup-command id)]
     (let [handler   (:handler cmd)
           spec      (:interactive cmd)
           interactor (i/interactor)]
       (if (and spec (seq spec))
         (if interactor
           (let [args (collect-args interactor spec)]
             (apply execute-command! project-atom id args))
           (throw (ex-info (str "Command " id " requires interactive args, but no interactor installed") {:id id})))
         ;; 无交互规范，直接执行（零参数）
         (let [old-value @project-atom]
           (try
             (swap! project-atom handler)
             (catch Exception e
               (reset! project-atom old-value)
               (throw (ex-info "Command execution failed" {:id id} e)))))))
     (throw (ex-info "Unknown command" {:id id}))))
  ([project-atom id & args]
   (if-let [cmd (lookup-command id)]
     (let [handler (:handler cmd)
           old-value @project-atom]
       (try
         (apply swap! project-atom handler args)
         (catch Exception e
           (reset! project-atom old-value)
           (throw (ex-info "Command execution failed" {:id id :args args} e)))))
     (throw (ex-info "Unknown command" {:id id})))))

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