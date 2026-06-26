(ns top.kzre.krro.core.hook
  "极简的 Hook 系统。Hook 就是一组函数的集合，用 Atom 管理。")

(defn make-hook [] (atom []))

(defn add-hook [hook fn] (swap! hook conj fn))

(defn remove-hook [hook fn] (swap! hook #(vec (remove #{fn} %))))

(defn run-hooks
  [hook & args]
  (doseq [f @hook] (apply f args)))