(ns top.kzre.krro.core.mode-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.frame :as frame]))

;; ── 变量必须在使用前定义 ──────────────────────────────
(custom/defcustom my-test-var 0 "Test variable" :type :integer)

;; ── 测试环境重置 ──────────────────────────────────────
(use-fixtures :each
              (fn [f]
                (proj/init-project!)
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
                ;; 创建默认 Frame 并绑定到 *current-frame*
                (let [f (frame/create-frame :id :test)]
                  (alter-var-root #'frame/*current-frame* (constantly f)))
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
        f frame/*current-frame*
        enter-called (atom false)
        exit-called (atom false)]
    (hook/add-hook (get-in spec [:mode/hooks :on-enter]) #(reset! enter-called true))
    (hook/add-hook (get-in spec [:mode/hooks :on-exit])  #(reset! exit-called true))
    (mode/activate-major-mode! :test.major f)
    (is (= :test.major (frame/major-mode f)))
    (is (= 2 (count (frame/keymaps f))))
    (is (= 10 @my-test-var))
    (is @enter-called)
    (is (not @exit-called))
    (mode/deactivate-mode! spec f)
    ;; fundamental 模式激活后，栈中只有 fundamental 键图（深度 1）
    (is (= 1 (count (frame/keymaps f))))
    (is (= 0 @my-test-var))
    (is @exit-called)))

(deftest test-activate-major-mode-switch
  (let [old-spec (sample-major-spec :old.major)
        new-spec (sample-major-spec :new.major)
        f frame/*current-frame*
        old-exit-called (atom false)]
    (mode/register-mode! old-spec)
    (mode/register-mode! new-spec)
    (hook/add-hook (get-in old-spec [:mode/hooks :on-exit]) #(reset! old-exit-called true))
    (mode/activate-major-mode! :old.major f)
    (is (= :old.major (frame/major-mode f)))
    (mode/activate-major-mode! :new.major f)
    (is (= :new.major (frame/major-mode f)))
    (is @old-exit-called)
    (is (= 2 (count (frame/keymaps f))))))   ;; 新模式键图推入

;; ═══════════════════════════════════════════════════════════
;; 副模式切换
;; ═══════════════════════════════════════════════════════════
(deftest test-toggle-minor-mode
  (let [minor (sample-minor-spec :test.minor)
        f frame/*current-frame*
        enter-called (atom false)
        exit-called (atom false)]
    (mode/register-mode! minor)
    (hook/add-hook (get-in minor [:mode/hooks :on-enter]) #(reset! enter-called true))
    (hook/add-hook (get-in minor [:mode/hooks :on-exit])  #(reset! exit-called true))
    (is (= #{} (frame/minor-modes f)))
    (mode/toggle-minor-mode! :test.minor f)
    (is (contains? (frame/minor-modes f) :test.minor))
    (is @enter-called)
    (is (= 20 @my-test-var))
    (is (= 2 (count (frame/keymaps f))))   ;; fundamental + minor
    (mode/toggle-minor-mode! :test.minor f)
    (is (not (contains? (frame/minor-modes f) :test.minor)))
    (is @exit-called)
    (is (= 0 @my-test-var))
    (is (= 1 (count (frame/keymaps f))))))  ;; 回到 fundamental

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
                               :mode/variables {var-b {:default 200}}))
        f frame/*current-frame*]
    (mode/register-mode! parent-spec)
    (mode/register-mode! child-spec)
    (mode/activate-major-mode! :child.mode f)
    (is (= 100 @var-a))
    (is (= 200 @var-b))
    (mode/deactivate-mode! child-spec f)
    (is (= 0 @var-a))
    (is (= 0 @var-b))))

;; ═══════════════════════════════════════════════════════════
;; after-hook
;; ═══════════════════════════════════════════════════════════
(deftest test-after-hook
  (let [spec (sample-major-spec :test.after)
        f frame/*current-frame*
        after-called (atom false)]
    (mode/register-mode! spec)
    (hook/add-hook (:mode/after-hook spec) #(reset! after-called true))
    (mode/activate-major-mode! :test.after f)
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
  (is (fn? my-test-mode-activate))
  (my-test-mode-activate)
  (is (= :top.kzre.krro.core.mode-test/my-test-mode (frame/major-mode frame/*current-frame*))))

(deftest test-define-minor-mode-macro
  (mode/define-minor-mode my-test-minor "Test minor mode"
                          :keymap (km/make-keymap {:q :cmd.q})
                          :variables {my-test-var {:default 55}})
  (is (= :top.kzre.krro.core.mode-test/my-test-minor my-test-minor))
  (is (mode/get-mode-spec my-test-minor))
  (is (fn? my-test-minor-toggle))
  (my-test-minor-toggle)
  (is (contains? (frame/minor-modes frame/*current-frame*) :top.kzre.krro.core.mode-test/my-test-minor)))