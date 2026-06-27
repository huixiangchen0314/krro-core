(ns top.kzre.krro.core.ui.spec
  "UI 描述语言的通用规格定义。仅约束元素的结构语法，不定义任何具体属性或标签。"
  (:require [clojure.spec.alpha :as s]))

(s/def ::tag keyword?)
(s/def ::attrs (s/nilable map?))

(s/def ::ui-element
  (s/or :string string?
        :vector (s/and vector?
                       (s/cat :tag ::tag
                              :attrs (s/? ::attrs)
                              :children (s/* ::ui-element)))))