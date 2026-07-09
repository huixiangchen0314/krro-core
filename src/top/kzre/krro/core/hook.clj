(ns top.kzre.krro.core.hook
  "基于关键字的全局 Hook 系统。替代局部的 make-hook atom。")

(defonce ^:private hooks-registry (atom {}))

(defn add-hook!
  "向 hook-key 对应的 hook 列表中添加回调函数 fn。"
  [hook-key fn]
  (swap! hooks-registry update hook-key (fnil conj []) fn))

(defn remove-hook!
  "从 hook-key 对应的 hook 列表中移除回调函数 fn。"
  [hook-key fn]
  (swap! hooks-registry update hook-key #(vec (remove #{fn} %))))

(defn run-hook!
  "运行 hook-key 对应的所有回调函数，传入额外参数 args。"
  [hook-key & args]
  (doseq [f (get @hooks-registry hook-key)]
    (apply f args)))
