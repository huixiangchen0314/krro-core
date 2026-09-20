(ns top.kzre.krro.core.util.re-export
  "简易的 var 重导出工具。只支持 :refer 形式。")

(defmacro re-export
  "将其他命名空间的 vars 重新导出到当前命名空间。

   用法：
     (re-export [top.kzre.krro.canvas.vector.layer  :refer [paths path-order save-path]]
                [top.kzre.krro.canvas.vector.path   :refer [path-width-type adjust-widths]]
                [top.kzre.krro.canvas.vector.anchor :refer [translate-anchor]])

   支持函数、宏、record 构造器（->X / map->X）等所有 var 类型。
   复制原 var 的 metadata（:doc / :arglists / :macro 等），
   但去掉 :private，确保重导出后对外可见。"
  [& specs]
  ;; 编译期校验：每个 spec 必须是 [some.ns :refer [sym ...]]
  (doseq [spec specs]
    (let [ok? (and (vector? spec)
                   (= 3 (count spec))
                   (= :refer (second spec))
                   (vector? (nth spec 2)))]
      (when-not ok?
        (throw (ex-info "re-export spec must be [some.ns :refer [sym ...]]"
                        {:spec spec})))))
  `(do
     ~@(for [[ns-sym _ syms] specs
             s syms]
         (let [orig (symbol (str ns-sym) (str s))]
           `(let [v# (var ~orig)]
              (intern *ns*
                      (with-meta '~s (dissoc (meta v#) :private))
                      @v#))))))