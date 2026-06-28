(ns top.kzre.krro.core.integration-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.ui.protocol :as ui]
            [top.kzre.krro.core.plugin :as plugin]))

;; ── Mock 渲染器 ───────────────────────────────────────
(defrecord MockRenderer [render-log]
  ui/IRenderer
  (render-element [this element parent-node]
    (swap! render-log conj [:element element])
    nil)
  (render-layout [this root-element]
    (swap! render-log conj [:layout root-element])
    nil)
  (destroy-ui! [this]
    (swap! render-log conj [:destroy])
    nil))

(defn new-mock-renderer []
  (->MockRenderer (atom [])))

;; ── 测试变量 ─────────────────────────────────────────
(custom/defcustom my-var 0 "Test var" :type :integer)

;; ── 测试模式 ─────────────────────────────────────────
(defn test-major-spec [id layout]
  (mode/make-major-mode id (str "Major " (name id))
                        :keymap (km/make-keymap {:a :test.cmd/a})
                        :layout layout
                        :variables {my-var {:default 42}}
                        :hooks {:on-enter (hook/make-hook)
                                :on-exit  (hook/make-hook)}
                        :after-hook (hook/make-hook)))

(defn test-minor-spec [id]
  (mode/make-minor-mode id (str "Minor " (name id))
                        :keymap (km/make-keymap {:b :test.cmd/b})
                        :variables {my-var {:default 99}}
                        :hooks {:on-enter (hook/make-hook)
                                :on-exit  (hook/make-hook)}
                        :after-hook (hook/make-hook)))

;; ── 测试命令 ─────────────────────────────────────────
(defn test-cmd-a [project]
  (assoc project :test/result :cmd-a))

(defn test-cmd-b [project]
  (assoc project :test/result :cmd-b))

;; ── Fixture ───────────────────────────────────────────
(use-fixtures :each
              (fn [f]
                (proj/init-project!)
                (reset! km/keymap-stack ())
                (reset! km/prefix-stack ())
                (reset! km/global-keymap (km/make-keymap {:u :krro.command/undo}))
                (reset! custom/custom-registry {})
                (reset! custom/custom-change-hook [])
                (reset! km/echo-hook [])
                (when-let [v (resolve 'top.kzre.krro.core.mode/mode-registry)]
                  (reset! @v {}))
                (let [fund-spec (mode/make-major-mode :krro.mode/fundamental "Fundamental"
                                                      :parent nil
                                                      :layout [:v-box {:id :fundamental}]
                                                      :keymap (km/make-keymap {}))]
                  (mode/register-mode! fund-spec))
                (reset! plugin/plugin-registry [])
                (ui/set-renderer! nil)
                (f)))

;; ═══════════════════════════════════════════════════════════
(deftest test-full-workflow
  (let [renderer (new-mock-renderer)
        layout [:v-box {:id :my-layout} [:label "Hello"]]
        spec (test-major-spec :test.major layout)]
    (ui/set-renderer! renderer)
    (mode/register-mode! spec)
    (cmd/register-command! :test.cmd/a test-cmd-a)
    (cmd/register-command! :test.cmd/b test-cmd-b)

    (mode/activate-major-mode! :test.major)
    (is (= :test.major (get-in @proj/project [:krro/modes :major])))
    (is (= [[:layout layout]] @(:render-log renderer)))

    (km/handle-key! :a)
    (is (= :cmd-a (:test/result @proj/project)))

    (km/handle-key! :b)
    (is (= :cmd-a (:test/result @proj/project))) ; 不变

    (let [minor (test-minor-spec :test.minor)]
      (mode/register-mode! minor)
      (mode/toggle-minor-mode! :test.minor)
      (is (contains? (get-in @proj/project [:krro/modes :minors]) :test.minor))
      (km/handle-key! :b)
      (is (= :cmd-b (:test/result @proj/project)))
      (mode/toggle-minor-mode! :test.minor)
      (is (not (contains? (get-in @proj/project [:krro/modes :minors]) :test.minor))))

    (mode/fundamental-activate!)
    (is (= :krro.mode/fundamental (get-in @proj/project [:krro/modes :major])))
    (is (= 0 @my-var))))

(deftest test-plugin-register-and-command
  ;; 定义 :test-plugin 类型的行为
  (defmethod plugin/apply-plugin! :test-plugin [p]
    (cmd/register-command! :test.plugin/cmd (fn [proj] (assoc proj :plugin/result :ok))))

  (let [p {:name :test-plug :type :test-plugin}]
    (plugin/register-plugin! p)
    (is (some #(= (:name %) :test-plug) (plugin/registered-plugins)))
    (cmd/execute-command! proj/project :test.plugin/cmd)
    (is (= :ok (:plugin/result @proj/project)))))

(deftest test-key-sequence
  (let [renderer (new-mock-renderer)]
    (ui/set-renderer! renderer)
    (cmd/register-command! :test.cmd/forward (fn [p] (assoc p :test/action :forward)))
    (cmd/register-command! :test.cmd/backward (fn [p] (assoc p :test/action :backward)))
    (let [prefix-km (km/make-keymap {"f" :test.cmd/forward "b" :test.cmd/backward})
          root-km (km/make-keymap {"C-x" prefix-km})]
      (km/push-keymap! root-km)
      (km/handle-key! "C-x")
      (is (= 1 (count @km/prefix-stack)))
      (km/handle-key! "f")
      (is (= 0 (count @km/prefix-stack)))
      (is (= :forward (:test/action @proj/project)))
      (km/handle-key! "C-x")
      (km/handle-key! "b")
      (is (= :backward (:test/action @proj/project))))
    (km/pop-keymap!)))