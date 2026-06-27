(ns top.kzre.krro.core.print-ui-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.ui.protocol :as ui]
            [top.kzre.krro.core.ui.spec :as ui-spec]
            [clojure.spec.alpha :as s]))

;; ── 简单的控制台打印渲染器 ───────────────────────────
(defrecord PrintRenderer [depth]
  ui/IRenderer
  (render-element [this element parent-node]
    (let [indent (apply str (repeat (* 2 @depth) \space))]
      (if (string? element)
        (println indent "Text:" (pr-str element))
        (let [[tag attrs & children] element]
          (println indent "Element:" tag)
          (when attrs
            (println indent "  Attrs:" attrs))
          (doseq [child children]
            (swap! depth inc)
            (ui/render-element this child parent-node)
            (swap! depth dec))))))
  (render-layout [this root-element]
    (println "=== Rendering Layout ===")
    (reset! depth 0)
    (ui/render-element this root-element nil))
  (destroy-ui! [this]
    (println "=== UI Destroyed ===")))

;; ── 测试 ──────────────────────────────────────────────
(deftest test-print-renderer-basic
  (let [renderer (->PrintRenderer (atom 0))]
    (ui/set-renderer! renderer)
    (let [layout [:v-box {:style {:padding 5}}
                  [:label {:text "Hello"}]
                  [:button {:on-command :ok} "OK"]]]
      (is (s/valid? ::ui-spec/ui-element layout))
      (ui/render-layout! layout)
      (ui/destroy-global-ui!))))

(deftest test-print-renderer-recursive
  (let [renderer (->PrintRenderer (atom 0))]
    (ui/set-renderer! renderer)
    (let [nested [:h-box
                  [:v-box
                   [:label {:text "A"}]
                   [:label {:text "B"}]]
                  [:button {:on-command :cancel} "Cancel"]]]
      (is (s/valid? ::ui-spec/ui-element nested))
      (ui/render-layout! nested)
      (ui/destroy-global-ui!))))