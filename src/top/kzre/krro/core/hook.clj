(ns top.kzre.krro.core.hook
  "极简的 Hook 系统，类似 Emacs 的 add-hook/run-hooks。
   Hook 就是一组函数的集合，用 Atom 管理。")

(defn make-hook
  "创建一个空的 Hook。"
  []
  (atom []))

(defn add-hook
  "向 Hook 中添加一个函数 fn。"
  [hook fn]
  (swap! hook conj fn))

(defn remove-hook
  "从 Hook 中移除一个函数 fn。"
  [hook fn]
  (swap! hook #(vec (remove #{fn} %))))

(defn run-hooks
  "依次调用 Hook 中的所有函数，可传递额外参数。"
  [hook & args]
  (doseq [f @hook]
    (apply f args)))