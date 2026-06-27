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
                ;; 重新注册 fundamental
                (let [fund-spec (mode/make-major-mode :krro.mode/fundamental "Fundamental"
                                                      :parent nil
                                                      :layout [:v-box {:id :fundamental}]
                                                      :keymap (km/make-keymap {}))]
                  (mode/register-mode! fund-spec))
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

    ;; 激活主模式
    (mode/activate-major-mode! proj/project :test.major)
    (is (= :test.major (get-in @proj/project [:krro/modes :major])))
    (is (= [[:layout layout]] @(:render-log renderer)))

    ;; 按键模拟: "a" 触发 test.cmd/a
    ;; 先推入模式键图（已经在 activate 时推入了），只需调用 handle-key!
    (km/handle-key! :a)
    (is (= :cmd-a (:test/result @proj/project)))

    ;; 按键 "b" 未在当前模式键图中定义，应被忽略（全局也没有）
    (km/handle-key! :b)
    (is (= :cmd-a (:test/result @proj/project))) ; 不变

    ;; 切换 minor mode
    (let [minor (test-minor-spec :test.minor)]
      (mode/register-mode! minor)
      (mode/toggle-minor-mode! proj/project :test.minor)
      (is (contains? (get-in @proj/project [:krro/modes :minors]) :test.minor))
      ;; minor mode 键图覆盖 major，此时 "b" 应能触发 test.cmd/b
      (km/handle-key! :b)
      (is (= :cmd-b (:test/result @proj/project)))

      ;; 关闭 minor mode
      (mode/toggle-minor-mode! proj/project :test.minor)
      (is (not (contains? (get-in @proj/project [:krro/modes :minors]) :test.minor))))

    ;; 停用主模式
    ;; 停用主模式（回退到 fundamental）
    (mode/fundamental-activate proj/project)
    (is (= :krro.mode/fundamental (get-in @proj/project [:krro/modes :major])))
    ;; 变量恢复
    (is (= 0 @my-var))))

(deftest test-plugin-register-and-command
  ;; 模拟一个插件，通过多方法注册命令
  (defmethod plugin/register-plugin! :test-plugin [p]
    (cmd/register-command! :test.plugin/cmd (fn [proj] (assoc proj :plugin/result :ok)))
    (swap! plugin/plugin-registry assoc (:id p) p)
    (:id p))

  (let [p {:id :test-plug :type :test-plugin}]
    (is (= :test-plug (plugin/register-plugin! p)))
    (is (contains? (plugin/registered-plugins) :test-plug))
    (cmd/execute-command proj/project :test.plugin/cmd)
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