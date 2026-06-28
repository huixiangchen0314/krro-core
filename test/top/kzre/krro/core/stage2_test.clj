(ns top.kzre.krro.core.stage2-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.ui.protocol :as ui]
            [top.kzre.krro.core.frame :as frame]))

(use-fixtures :each
              (fn [f]
                (proj/init-project!)
                ;; 不再有全局 keymap-stack，移除重置
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
                (ui/set-renderer! nil)
                ;; 创建默认 Frame 并绑定到 *current-frame*
                (let [f (frame/create-frame :id :stage2)]
                  (alter-var-root #'frame/*current-frame* (constantly f)))
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
    (is (= [['observed-var 1 2]] @calls))
    (custom/set-custom! observed-var 3)
    (is (= [['observed-var 1 2] ['observed-var 2 3]] @calls))
    (hook/remove-hook custom/custom-change-hook record)))

;; ══════════════════════════════════════════════════════════
;; 2.3 键序列前缀显示 + 2.4 describe-key
;; ══════════════════════════════════════════════════════════
(deftest test-prefix-echo-and-describe-key
  (let [f (frame/create-frame :id :prefix-test)   ;; 为键测试创建独立 Frame
        echoed (atom [])
        echo-callback (fn [msg] (swap! echoed conj msg))
        prefixed (km/make-keymap {"f" :krro.command/forward "b" :krro.command/backward}
                                 nil)
        prefix-km (assoc prefixed :prefix "C-x")]
    (hook/add-hook km/echo-hook echo-callback)
    (frame/push-keymap f (km/make-keymap {"C-x" prefix-km}))
    (with-redefs [cmd/execute-command! (fn [& _] nil)]
      (km/handle-key! "C-x" (frame/keymaps f))
      (is (= ["C-x"] @echoed))
      (is (= 1 (count @km/prefix-stack)))
      (km/handle-key! "f" (frame/keymaps f))
      (is (= 0 (count @km/prefix-stack))))
    (frame/pop-keymap f)
    (hook/remove-hook km/echo-hook echo-callback))

  (testing "describe-key returns bindings in priority order"
    (let [f (frame/create-frame :id :desc-test)
          km1 (km/make-keymap {:a :cmd1})
          km2 (km/make-keymap {:a :cmd2})]
      (frame/push-keymap f km1)
      (frame/push-keymap f km2)
      (is (= [:cmd2 :cmd1] (km/describe-key :a (frame/keymaps f))))
      (frame/pop-keymap f)
      (frame/pop-keymap f))))

;; ══════════════════════════════════════════════════════════
;; 2.5 模式继承 keymap
;; ══════════════════════════════════════════════════════════
(deftest test-mode-keymap-inheritance
  (let [f frame/*current-frame*
        parent-km (km/make-keymap {:a :parent-cmd})
        parent-spec (mode/make-major-mode :test.parent "Parent"
                                          :keymap parent-km)
        child-km (km/make-keymap {:b :child-cmd})
        child-spec (mode/make-major-mode :test.child "Child"
                                         :parent :test.parent
                                         :keymap child-km)]
    (mode/register-mode! parent-spec)
    (mode/register-mode! child-spec)
    (mode/activate-major-mode! :test.child f)
    ;; 从 Frame 的键图栈获取栈顶（局部键图优先，全局键图在最后）
    (let [combined (first (frame/keymaps f))]   ;; 因为局部栈在前，第一个就是合并后的键图
      (is (contains? (:keys combined) :b))
      (is (= :parent-cmd (km/lookup-key combined :a)))
      (is (= :child-cmd (km/lookup-key combined :b))))
    (mode/deactivate-mode! child-spec f)))

;; ══════════════════════════════════════════════════════════
;; 2.6 fundamental 模式
;; ══════════════════════════════════════════════════════════
(deftest test-fundamental-mode-exists
  (is (mode/get-mode-spec :krro.mode/fundamental))
  (mode/fundamental-activate! frame/*current-frame*)
  (is (= :krro.mode/fundamental (frame/major-mode frame/*current-frame*))))

;; ══════════════════════════════════════════════════════════
;; 2.7 after-hook 公开化
;; ══════════════════════════════════════════════════════════
(deftest test-major-mode-public-after-hook
  (let [my-hook (hook/make-hook)
        spec    (mode/make-major-mode :test.my-mode "My Mode"
                                      :keymap (km/make-keymap {})
                                      :after-hook my-hook)
        f frame/*current-frame*]
    (mode/register-mode! spec)
    (let [called (atom false)]
      (hook/add-hook my-hook #(reset! called true))
      (mode/activate-major-mode! :test.my-mode f)
      (is @called))
    (mode/deactivate-mode! spec f)))