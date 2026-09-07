(ns top.kzre.krro.core.util)


(defn merge-deep
  "深度合并多个 map。对于嵌套 map 会递归合并，否则后者的值覆盖前者。
   用法与 merge 相同，但会递归合并嵌套的 map。
   若没有提供任何参数，返回空 map。"
  ([] {})
  ([m1] (or m1 {}))
  ([m1 m2 & more]
   (let [merged (merge-with (fn [v1 v2]
                              (if (and (map? v1) (map? v2))
                                (merge-deep v1 v2)
                                v2))
                            (or m1 {}) (or m2 {}))]
     (if more
       (apply merge-deep merged more)
       merged))))