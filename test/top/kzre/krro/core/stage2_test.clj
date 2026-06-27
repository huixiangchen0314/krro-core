(ns top.kzre.krro.core.stage2-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.ui.protocol :as ui]))

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
                ;; 重新注册 fundamental 模式，parent 必须为 nil
                (let [fund-spec (mode/make-major-mode :krro.mode/fundamental "Fundamental"
                                                      :parent nil    ;; 关键：防止自循环
                                                      :layout [:v-box {:id :fundamental}]
                                                      :keymap (km/make-keymap {}))]
                  (mode/register-mode! fund-spec))
                (ui/set-renderer! nil)
                (f)))



;; ══════════════════════════════════════════════════════════
;; 2.1 变量类型验证
;; ══════════════════════════════════════════════════════════
(deftest test-custom-type-validation
  (custom/defcustom my-int 0 "Integer var" :type :integer)
  (custom/defcustom my-str "hi" "String var" :type :string)
  (custom/defcustom my-kw :x "Keyword var" :type :keyword)
  (custom/defcustom my-bool true "Bool var" :type :boolean)
  ;; 正确类型
  (custom/set-custom! my-int 5)
  (is (= 5 @my-int))
  (custom/set-custom! my-str "hello")
  (is (= "hello" @my-str))
  (custom/set-custom! my-kw :y)
  (is (= :y @my-kw))
  (custom/set-custom! my-bool false)
  (is (= false @my-bool))
  ;; 错误类型
  (is (thrown? clojure.lang.ExceptionInfo (custom/set-custom! my-int "bad")))
  (is (thrown? clojure.lang.ExceptionInfo (custom/set-custom! my-str 42)))
  (is (thrown? clojure.lang.ExceptionInfo (custom/set-custom! my-kw "bad")))
  (is (thrown? clojure.lang.ExceptionInfo (custom/set-custom! my-bool "bad"))))

;; ══════════════════════════════════════════════════════════
;; 2.2 变量观察 hook
;; ══════════════════════════════════════════════════════════
(deftest test-custom-change-hook
  (let [calls (atom [])
        record (fn [sym old new] (swap! calls conj [sym old new]))]
    (hook/add-hook custom/custom-change-hook record)
    (custom/defcustom observed-var 1 "Observed" :type :integer)
    (custom/set-custom! observed-var 2)
    ;; 使用简单符号，不带命名空间
    (is (= [['observed-var 1 2]] @calls))
    (custom/set-custom! observed-var 3)
    (is (= [['observed-var 1 2] ['observed-var 2 3]] @calls))
    (hook/remove-hook custom/custom-change-hook record)))

;; ══════════════════════════════════════════════════════════
;; 2.3 键序列前缀显示 + 2.4 describe-key
;; ══════════════════════════════════════════════════════════
(deftest test-prefix-echo-and-describe-key
  (let [echoed (atom [])
        echo-callback (fn [msg] (swap! echoed conj msg))
        prefixed (km/make-keymap {"f" :krro.command/forward "b" :krro.command/backward}
                                 nil)
        prefix-km (assoc prefixed :prefix "C-x")]
    (hook/add-hook km/echo-hook echo-callback)
    (km/push-keymap! (km/make-keymap {"C-x" prefix-km}))
    ;; 用 with-redefs 阻止实际命令执行
    (with-redefs [cmd/execute-command! (fn [& _] nil)]
      (km/handle-key! "C-x")
      (is (= ["C-x"] @echoed))
      (is (= 1 (count @km/prefix-stack)))
      (km/handle-key! "f")                     ;; 应完成序列
      (is (= 0 (count @km/prefix-stack))))
    (km/pop-keymap!)
    (hook/remove-hook km/echo-hook echo-callback))

  ;; describe-key 测试
  (testing "describe-key returns bindings in priority order"
    (let [km1 (km/make-keymap {:a :cmd1})
          km2 (km/make-keymap {:a :cmd2})]
      (km/push-keymap! km1)   ;; 先压入的优先级低
      (km/push-keymap! km2)   ;; 后压入的优先级高
      (is (= [:cmd2 :cmd1] (km/describe-key :a)))
      (km/pop-keymap!)
      (km/pop-keymap!))))

;; ══════════════════════════════════════════════════════════
;; 2.5 模式继承 keymap
;; ══════════════════════════════════════════════════════════
(deftest test-mode-keymap-inheritance
  ;; 父模式键图
  (let [parent-km (km/make-keymap {:a :parent-cmd})
        parent-spec (mode/make-major-mode :test.parent "Parent"
                                          :keymap parent-km)
        ;; 子模式键图（应继承父键图）
        child-km (km/make-keymap {:b :child-cmd})
        child-spec (mode/make-major-mode :test.child "Child"
                                         :parent :test.parent
                                         :keymap child-km)]
    (mode/register-mode! parent-spec)
    (mode/register-mode! child-spec)
    (mode/activate-major-mode! proj/project :test.child)
    (let [combined (first @km/keymap-stack)]
      ;; 子键图的 :keys 包含 :b
      (is (contains? (:keys combined) :b))
      ;; 父键图的 :a 通过 :parent 继承
      (is (= :parent-cmd (km/lookup-key combined :a)))
      (is (= :child-cmd (km/lookup-key combined :b))))
    ;; 清理
    (mode/deactivate-mode! proj/project child-spec)))

;; ══════════════════════════════════════════════════════════
;; 2.6 fundamental 模式
;; ══════════════════════════════════════════════════════════
(deftest test-fundamental-mode-exists
  (is (mode/get-mode-spec :krro.mode/fundamental))
  (mode/fundamental-activate proj/project)
  (is (= :krro.mode/fundamental (get-in @proj/project [:krro/modes :major]))))

;; ══════════════════════════════════════════════════════════
;; 2.7 after-hook 公开化
;; ══════════════════════════════════════════════════════════
(deftest test-major-mode-public-after-hook
  (let [my-hook (hook/make-hook)              ;; 手动创建 hook
        spec    (mode/make-major-mode :test.my-mode "My Mode"
                                      :keymap (km/make-keymap {})
                                      :after-hook my-hook)]       ;; 直接作为值传入
    (mode/register-mode! spec)
    (let [called (atom false)]
      (hook/add-hook my-hook #(reset! called true))
      (mode/activate-major-mode! proj/project :test.my-mode)
      (is @called))
    ;; 清理
    (mode/deactivate-mode! proj/project spec)))