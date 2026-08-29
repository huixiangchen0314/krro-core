(ns top.kzre.krro.core.command
  "全局命令注册与执行。命令以关键字标识，存储为包含 handler 和可选交互规范的 map。
   命令 handler 签名为 (fn [project & args] -> any)，可原地修改项目原子，
   execute-command! 返回 handler 的返回值。"
  (:require
   [top.kzre.krro.core.interactive :as i]
   [top.kzre.krro.core.message :as msg]
   [top.kzre.krro.core.project :as proj]
   [top.kzre.krro.core.variable :refer [*debug*]]
   [top.kzre.krro.core.variable :as variable]))

(defonce command-registry (atom {}))

(defn reg-command
  [id handler & {:keys [description interactive]}]
  {:pre [(or (nil? interactive)
             (boolean? interactive)
             (vector? interactive))]}
  (swap! command-registry assoc id
         {:handler     handler
          :id          id
          :description description
          :interactive interactive}))

(def register-command! reg-command)



(defn lookup-command [id]
  (get @command-registry id))

(defn interactive-commands
  "获取所有可执行命令"
  []
  (into {}
        (filter (fn [[_ v]] (some? (:interactive v))))
        @command-registry))

(defn execute-command!
  ([id]
   (if @variable/command-enabled
     (if-let [cmd (lookup-command id)]
       (let [handler (:handler cmd)
             spec    (:interactive cmd)
             interactor (i/interactor)]
         (if (and spec (seq spec))
           (if interactor
             (let [args (i/read-args interactor spec)]
               (if (some nil? args)
                 nil
                 (apply execute-command! id args)))
             (msg/error (str "Command " id " requires interactive args, but no interactor installed")))
           (try
             (handler @proj/project)
             (catch Exception e
               (msg/error (str "Command execution failed: " id " - " (.getMessage e)))
               (when *debug* (throw e))
               nil))))
       (msg/warn (str "Unknown command: " id)))
     ;; else
     (do
       (msg/warn (str "Command execution disabled, cannot execute " id))
       nil)))
  ([id & args]
   (if @variable/command-enabled
     (if-let [cmd (lookup-command id)]
       (let [handler (:handler cmd)]
         (try
           (apply handler @proj/project args)
           (catch Exception e
             (msg/error (str "Command execution failed: " id " with args " args " - " (.getMessage e)))
             (when *debug* (throw e))
             nil)))
       (msg/warn (str "Unknown command: " id)))
     ;; else
     (do
       (msg/warn (str "Command execution disabled, cannot execute " id " with args " args))
       nil))))

(defmacro defcommand
  "定义命令并注册。语法：
   (defcommand name [project & args] :description \"doc\" :interactive [:string] body...)
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