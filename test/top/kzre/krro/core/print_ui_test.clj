(ns top.kzre.krro.core.print-ui-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.ui.protocol :as ui]
            [top.kzre.krro.core.ui.spec :as ui-spec]
            [clojure.spec.alpha :as s]))

;; ── 简单的控制台打印渲染器 ───────────────────────────
(defrecord PrintRenderer [depth]
  ui/IRenderer
  (render-element [this element ]
    (let [indent (apply str (repeat (* 2 @depth) \space))]
      (if (string? element)
        (println indent "Text:" (pr-str element))
        (let [[tag attrs & children] element]
          (println indent "Element:" tag)
          (when attrs
            (println indent "  Attrs:" attrs))
          (doseq [child children]
            (swap! depth inc)
            (ui/render-element this child )
            (swap! depth dec))))))
  (render-layout [this root-element]
    (println "=== Rendering Layout ===")
    (reset! depth 0)
    (ui/render-element this root-element ))
  (destroy-ui! [this]
    (println "=== UI Destroyed ===")))

