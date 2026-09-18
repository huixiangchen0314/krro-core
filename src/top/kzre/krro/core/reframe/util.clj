(ns top.kzre.krro.core.reframe.util)


(defn merge-effects
  "合并两个 effects map。

   合并规则：
     :fx         —— 向量拼接
     :dispatch-n —— 向量拼接
     :dispatch   —— 归一到 :dispatch-n（避免覆盖）
     :record     —— map 深度合并（浅 merge）
     其他 key    —— 后者覆盖前者

   用于事务指令执行、嵌套事件返回值的累积。"
  [a b]
  (when (or a b)
    (let [a (or a {})
          b (or b {})
          ;; 归一化 :dispatch → :dispatch-n
          norm (fn [eff]
                 (if-let [d (:dispatch eff)]
                   (-> eff
                       (dissoc :dispatch)
                       (update :dispatch-n (fnil conj []) d))
                   eff))
          a' (norm a)
          b' (norm b)]
      (merge-with
        (fn [x y]
          (cond
            (and (vector? x) (vector? y)) (into x y)
            (and (map? x) (map? y))       (merge x y)
            :else                          y))
        a'
        b'))))