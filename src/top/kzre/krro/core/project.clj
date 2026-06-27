(ns top.kzre.krro.core.project
  "全局项目数据管理。项目数据是普通的不可变 EDN map，存储在 atom 中。
   内核只维护元数据与活跃插件集合，领域数据由各插件自行管理。"
  (:import (java.time Instant)))

(defonce project (atom {}))

(defn get-in-project
  ([path] (get-in @project path))
  ([path default] (get-in @project path default)))

(defn update-project!
  "原子地更新项目数据。f 签名: (fn [proj] -> new-proj)。
   由命令系统调用，外部应通过 execute-command 间接修改。"
  [f]
  (swap! project f))

(defn init-project!
  "初始化一个新的最小项目。"
  [& {:keys [name] :or {name "Untitled"}}]
  (let [now (str (Instant/now))]
    (reset! project
            {:krro/meta   {:version "0.1.0"
                           :name name
                           :created-at now
                           :modified-at now}
             :krro/modes  {:major :krro.mode/fundamental
                           :minors #{}}
             :krro/plugins {:active #{}}})))




(defonce protected-keys
         (atom #{:krro/meta :krro/modes :krro/plugins}))  ;; 内核默认保护

(defn register-protected-key! [kw]
  (swap! protected-keys conj kw))
