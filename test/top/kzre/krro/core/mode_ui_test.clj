(ns top.kzre.krro.core.mode-ui-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.ui.protocol :as ui]))

;; ── Mock 渲染器 ───────────────────────────────────────
(defrecord MockRenderer [render-calls destroy-called]
  ui/IRenderer
  (render-element [this element]
    (swap! render-calls conj [:element element])
    (println "render-element:" element))
  (render-layout [this root-element]
    (swap! render-calls conj [:layout root-element])
    (println "render-layout:" root-element))
  (destroy-ui! [this]
    (reset! destroy-called true)
    (println "destroy-ui!")))

(defn new-mock-renderer []
  (->MockRenderer (atom []) (atom false)))

;; ── 测试变量 ─────────────────────────────────────────
(custom/defcustom my-test-var 0 "Test variable" :type :integer)

;; ── 辅助模式定义 ─────────────────────────────────────
(defn sample-major-spec [id layout]
  (mode/make-major-mode id (str "Major " (name id))
                        :keymap (km/make-keymap {:x :cmd.x})
                        :layout layout
                        :variables {my-test-var {:default 10}}
                        :hooks {:on-enter (hook/make-hook)
                                :on-exit  (hook/make-hook)}
                        :after-hook (hook/make-hook)))

;; ── 测试 ─────────────────────────────────────────────
(use-fixtures :each
              (fn [f]
                (proj/init-project!)
                (reset! km/keymap-stack ())
                (reset! km/prefix-stack ())
                (reset! km/global-keymap (km/make-keymap {:u :krro.command/undo}))
                (reset! custom/custom-registry {})
                (when-let [v (resolve 'top.kzre.krro.core.mode/mode-registry)]
                  (reset! @v {}))
                (reset! my-test-var 0)
                (alter-meta! my-test-var dissoc :local-stack)
                (ui/set-renderer! nil)   ;; 清除渲染器
                (f)))

(deftest test-activate-mode-renders-layout
  (let [renderer (new-mock-renderer)
        layout [:v-box {:id :test} [:label "Hello"]]
        spec (sample-major-spec :test.major layout)]
    (ui/set-renderer! renderer)
    (mode/register-mode! spec)
    (mode/activate-major-mode! proj/project :test.major)
    ;; 检查渲染调用
    (is (= [[:layout layout]] @(:render-calls renderer)))
    (is (not @(:destroy-called renderer)))))

(deftest test-activate-mode-without-layout-does-nothing
  (let [renderer (new-mock-renderer)
        spec (sample-major-spec :test.no-layout nil)]
    (ui/set-renderer! renderer)
    (mode/register-mode! spec)
    (mode/activate-major-mode! proj/project :test.no-layout)
    ;; 未传入布局时不应调用渲染器
    (is (empty? @(:render-calls renderer)))
    (is (not @(:destroy-called renderer)))))

(deftest test-switch-mode-calls-layout
  (let [renderer (new-mock-renderer)
        layout-old [:v-box {:id :old} [:label "Old"]]
        layout-new [:v-box {:id :new} [:label "New"]]
        spec-old (sample-major-spec :old.major layout-old)
        spec-new (sample-major-spec :new.major layout-new)]
    (ui/set-renderer! renderer)
    (mode/register-mode! spec-old)
    (mode/register-mode! spec-new)
    (mode/activate-major-mode! proj/project :old.major)
    (is (= [[:layout layout-old]] @(:render-calls renderer)))
    (mode/activate-major-mode! proj/project :new.major)
    ;; 新模式的布局也应被渲染
    (is (= [[:layout layout-old] [:layout layout-new]]
           @(:render-calls renderer)))
    (is (not @(:destroy-called renderer)))))

(deftest test-renderer-not-installed
  (let [layout [:v-box [:label "No renderer"]]
        spec (sample-major-spec :test.norender layout)]
    (mode/register-mode! spec)
    ;; 未安装渲染器时不应抛出异常
    (is (nil? (mode/activate-major-mode! proj/project :test.norender)))))