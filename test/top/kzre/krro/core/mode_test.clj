(ns top.kzre.krro.core.mode-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.hook :as hook]))

;; ── 变量必须在使用前定义 ──────────────────────────────
(custom/defcustom my-test-var 0 "Test variable" :type :integer)

;; ── 测试环境重置 ──────────────────────────────────────
(use-fixtures :each
              (fn [f]
                (proj/init-project!)
                (reset! km/keymap-stack ())
                (reset! km/prefix-stack ())
                (reset! km/global-keymap (km/make-keymap {:u :krro.command/undo}))
                (reset! custom/custom-registry {})
                (when-let [v (resolve 'top.kzre.krro.core.mode/mode-registry)]
                  (reset! @v {}))
                ;; 重置 my-test-var 的值与局部栈
                (reset! my-test-var 0)
                (alter-meta! my-test-var dissoc :local-stack)
                ;; 清除可能的 var-a/var-b 残留
                (when-let [va (resolve 'var-a)]
                  (reset! @va 0)
                  (alter-meta! @va dissoc :local-stack))
                (when-let [vb (resolve 'var-b)]
                  (reset! @vb 0)
                  (alter-meta! @vb dissoc :local-stack))
                (f)))

;; ── 辅助函数 ────────────────────────────────────────────
(defn sample-major-spec [id]
  (mode/make-major-mode id (str "Major " (name id))
                        :keymap (km/make-keymap {:x :cmd.x})
                        :layout [:v-box {:id id}]
                        :variables {my-test-var {:default 10}}
                        :hooks {:on-enter (hook/make-hook)
                                :on-exit  (hook/make-hook)}
                        :after-hook (hook/make-hook)))

(defn sample-minor-spec [id]
  (mode/make-minor-mode id (str "Minor " (name id))
                        :keymap (km/make-keymap {:y :cmd.y})
                        :variables {my-test-var {:default 20}}
                        :hooks {:on-enter (hook/make-hook)
                                :on-exit  (hook/make-hook)}
                        :after-hook (hook/make-hook)))

;; ═══════════════════════════════════════════════════════════
;; 基础注册与创建
;; ═══════════════════════════════════════════════════════════
(deftest test-register-and-get-mode
  (let [spec (sample-major-spec :test.major)]
    (mode/register-mode! spec)
    (is (= spec (mode/get-mode-spec :test.major)))
    (is (nil? (mode/get-mode-spec :nonexistent)))))

(deftest test-make-major-mode
  (let [spec (mode/make-major-mode :major1 "Major One"
                                   :parent :krro.mode/super
                                   :keymap {:a :cmd.a})]
    (is (= :major1 (:mode/id spec)))
    (is (= "Major One" (:mode/name spec)))
    (is (= :krro.mode/super (:mode/parent spec)))
    (is (= {:a :cmd.a} (:mode/keymap spec)))
    (is (not (:mode/minor? spec)))))

(deftest test-make-minor-mode
  (let [spec (mode/make-minor-mode :minor1 "Minor One"
                                   :keymap {:b :cmd.b})]
    (is (= :minor1 (:mode/id spec)))
    (is (:mode/minor? spec))))

;; ═══════════════════════════════════════════════════════════
;; 主模式激活 / 停用
;; ═══════════════════════════════════════════════════════════
(deftest test-activate-major-mode
  (let [spec (sample-major-spec :test.major)
        _ (mode/register-mode! spec)
        enter-called (atom false)
        exit-called (atom false)]
    (hook/add-hook (get-in spec [:mode/hooks :on-enter]) #(reset! enter-called true))
    (hook/add-hook (get-in spec [:mode/hooks :on-exit])  #(reset! exit-called true))
    (mode/activate-major-mode! proj/project :test.major)
    (is (= :test.major (get-in @proj/project [:krro/modes :major])))
    (is (= 1 (count @km/keymap-stack)))
    (is (= 10 @my-test-var))
    (is @enter-called)
    (is (not @exit-called))
    (mode/deactivate-mode! proj/project spec)
    (is (= 0 (count @km/keymap-stack)))
    (is (= 0 @my-test-var))
    (is @exit-called)))

(deftest test-activate-major-mode-switch
  (let [old-spec (sample-major-spec :old.major)
        new-spec (sample-major-spec :new.major)
        old-exit-called (atom false)]
    (mode/register-mode! old-spec)
    (mode/register-mode! new-spec)
    (hook/add-hook (get-in old-spec [:mode/hooks :on-exit]) #(reset! old-exit-called true))
    (mode/activate-major-mode! proj/project :old.major)
    (is (= :old.major (get-in @proj/project [:krro/modes :major])))
    (mode/activate-major-mode! proj/project :new.major)
    (is (= :new.major (get-in @proj/project [:krro/modes :major])))
    (is @old-exit-called)
    (is (= 1 (count @km/keymap-stack)))))

;; ═══════════════════════════════════════════════════════════
;; 副模式切换
;; ═══════════════════════════════════════════════════════════
(deftest test-toggle-minor-mode
  (let [minor (sample-minor-spec :test.minor)
        enter-called (atom false)
        exit-called (atom false)]
    (mode/register-mode! minor)
    (hook/add-hook (get-in minor [:mode/hooks :on-enter]) #(reset! enter-called true))
    (hook/add-hook (get-in minor [:mode/hooks :on-exit])  #(reset! exit-called true))
    (is (= #{} (get-in @proj/project [:krro/modes :minors])))
    (mode/toggle-minor-mode! proj/project :test.minor)
    (is (contains? (get-in @proj/project [:krro/modes :minors]) :test.minor))
    (is @enter-called)
    (is (= 20 @my-test-var))
    (is (= 1 (count @km/keymap-stack)))
    (mode/toggle-minor-mode! proj/project :test.minor)
    (is (not (contains? (get-in @proj/project [:krro/modes :minors]) :test.minor)))
    (is @exit-called)
    (is (= 0 @my-test-var))
    (is (= 0 (count @km/keymap-stack)))))

;; ═══════════════════════════════════════════════════════════
;; 变量继承
;; ═══════════════════════════════════════════════════════════
(deftest test-variable-inheritance
  (custom/defcustom var-a 0 "A")
  (custom/defcustom var-b 0 "B")
  (let [parent-spec (-> (sample-major-spec :parent.mode)
                        (assoc :mode/variables {var-a {:default 100}}))
        child-spec  (-> (sample-major-spec :child.mode)
                        (assoc :mode/parent :parent.mode
                               :mode/variables {var-b {:default 200}}))]
    (mode/register-mode! parent-spec)
    (mode/register-mode! child-spec)
    (mode/activate-major-mode! proj/project :child.mode)
    (is (= 100 @var-a))
    (is (= 200 @var-b))
    (mode/deactivate-mode! proj/project child-spec)
    (is (= 0 @var-a))
    (is (= 0 @var-b))))

;; ═══════════════════════════════════════════════════════════
;; after-hook
;; ═══════════════════════════════════════════════════════════
(deftest test-after-hook
  (let [spec (sample-major-spec :test.after)
        after-called (atom false)]
    (mode/register-mode! spec)
    (hook/add-hook (:mode/after-hook spec) #(reset! after-called true))
    (mode/activate-major-mode! proj/project :test.after)
    (is @after-called)))

;; ═══════════════════════════════════════════════════════════
;; 便捷宏
;; ═══════════════════════════════════════════════════════════
(deftest test-define-major-mode-macro
  (mode/define-major-mode my-test-mode "Test major mode"
                          :keymap (km/make-keymap {:z :cmd.z})
                          :variables {my-test-var {:default 999}})
  (is (= :top.kzre.krro.core.mode-test/my-test-mode my-test-mode))
  (is (mode/get-mode-spec my-test-mode))
  (is (fn? my-test-mode-activate)))

(deftest test-define-minor-mode-macro
  (mode/define-minor-mode my-test-minor "Test minor mode"
                          :keymap (km/make-keymap {:q :cmd.q})
                          :variables {my-test-var {:default 55}})
  (is (= :top.kzre.krro.core.mode-test/my-test-minor my-test-minor))
  (is (mode/get-mode-spec my-test-minor))
  (is (fn? my-test-minor-toggle)))