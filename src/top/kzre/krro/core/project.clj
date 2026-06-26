(ns top.kzre.krro.core.project
  "全局项目数据管理。项目数据是普通的不可变 EDN map，存储在 atom 中。
   所有修改必须通过命令系统进行，以支持 undo/redo 和 Git 历史。
   数据中不保留任何 Java 对象，保证纯 EDN 透明性。"
  (:import (java.time Instant)))

;; 全局项目原子，初始为空项目
(defonce project (atom {}))

;; 辅助读取函数：从项目中提取路径的值，提供默认值
(defn get-in-project
  "从项目数据中按路径取值，如果不存在则返回默认值。"
  ([path]
   (get-in @project path))
  ([path default]
   (get-in @project path default)))

;; 辅助修改函数：通过 swap! 更新项目数据
;; 注意：此函数仅供命令系统内部使用，外部应通过 execute-command 间接修改
(defn update-project!
  "原子地更新项目数据。f 是一个函数 (fn [proj] -> new-proj)。"
  [f]
  (swap! project f))

;; 初始化项目，设置默认结构
;; 使用纯字符串表示时间戳，避免 Java 对象污染 EDN 数据
(defn init-project!
  "将项目重置为一个全新的默认结构。"
  [& {:keys [name] :or {name "Untitled"}}]
  (let [now (str (Instant/now))]
    (reset! project
            {:krro/meta {:version "0.1.0"
                         :name name
                         :created-at now
                         :modified-at now
                         :active-mode nil}
             :krro/history {:branches {"main" {:head nil}}
                            :commits {}}
             :krro/plugins #{}})))