(ns top.kzre.krro.core.project
  "全局项目数据管理。项目数据是普通的不可变 EDN map，存储在 atom 中。
   内核只维护元数据与活跃插件集合，模式状态由 Frame 管理，不再存储于项目原子。"
  (:require
   [top.kzre.krro.core.util.edn :as edn]
   [clojure.java.io :as io]

   [top.kzre.krro.core.resource :as res])
  (:import
   (java.time Instant)))

(defonce project (atom {}))


;; ── 资源便捷操作 ──────────────────────────────────
(defn activate-resource!
  "激活全局项目中指定路径的代理数据为具体对象。参见 resource/activate-resource!"
  [ks]
  (res/activate-resource! project ks))

(defn deactivate-resource!
  "将全局项目中指定路径的活跃对象编码为代理 map。参见 resource/deactivate-resource!"
  [ks]
  (res/deactivate-resource! project ks))

(defn get-in-project!
  "从全局项目中获取路径的值，自动激活代理 map。参见 resource/get-in-project!"
  ([ks]
   (res/get-in-project! project ks))
  ([ks not-found]
   (res/get-in-project! project ks not-found)))

(defn get-in-project-lazy
  "从全局项目中获取路径，返回惰性解码树。参见 resource/get-in-project-lazy"
  [ks]
  (res/get-in-project-lazy @project ks))

(defn init-project!
  "初始化一个新的最小项目。不再包含 :krro/modes。"
  [& {:keys [name] :or {name "Untitled"}}]
  (let [now (str (Instant/now))]
    (reset! project
            {:krro/meta   {:version "0.1.0"
                           :name name
                           :created-at now
                           :modified-at now}
             :krro/plugins {:active #{}}})))

(defn load-project!
  "从文件 f 加载项目，重置全局项目原子为纯数据代理 map。
   不进行任何解码操作，加载后项目处于完全未激活状态。"
  [f]
  (reset! project (read-string (slurp (io/file f)))))

(defn save-project!
  "流式保存全局项目到文件 f。任何未注册编码器的对象将导致保存失败。"
  [f]
  (let [encoder-fn (fn [obj]
                     (or (res/encode-object obj)
                         (throw (ex-info (str "No codec registered for object of type " (class obj))
                                         {:object obj}))))]
    (with-open [w (io/writer f)]
      (edn/write-edn @project w :object-encoder encoder-fn))))

(defonce protected-keys
         (atom #{:krro/meta :krro/plugins}))  ;; 内核默认保护

(defn register-protected-key! [kw]
  (swap! protected-keys conj kw))

(defn user-data
  "返回项目的用户数据部分，剔除所有受保护的键。没经过解码，仅处理保护键行为."
  ([]
   (reduce dissoc @project @protected-keys))
  ([project]
   (reduce dissoc project @protected-keys)))



