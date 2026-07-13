(ns top.kzre.krro.core.keymap-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.frame :as frame]))

(use-fixtures :each
              (fn [f]
                ;; 重置全局键图和前缀栈，确保测试隔离
                (reset! km/global-keymap
                        (km/make-keymap {:u :krro.command/undo
                                         :r :krro.command/redo
                                         :esc :krro.command/escape
                                         "C-z" :krro.command/undo}))
                (reset! km/prefix-stack ())
                (proj/init-project!)
                (f)))

;; ── 辅助函数：创建测试 Frame 并返回 Frame 对象 ──────
(defn- test-frame []
  (frame/create-frame! :id (keyword (str "test-" (gensym "f")))))

;; ── 键图创建与查找基础测试 ───────────────────────────
(deftest test-make-keymap
  (let [m (km/make-keymap {:a :cmd-a})]
    (is (= {:keys {:a :cmd-a} :parent nil} m)))
  (let [parent (km/make-keymap {:b :cmd-b})
        child (km/make-keymap {:c :cmd-c} parent)]
    (is (= (:parent child) parent))))

(deftest test-lookup-key
  (let [m (km/make-keymap {:a :cmd-a})]
    (is (= :cmd-a (km/lookup-key m :a)))
    (is (nil? (km/lookup-key m :x))))
  (let [parent (km/make-keymap {:b :cmd-b})
        child (km/make-keymap {:c :cmd-c} parent)]
    (is (= :cmd-c (km/lookup-key child :c)))
    (is (= :cmd-b (km/lookup-key child :b)))
    (is (nil? (km/lookup-key child :z)))))

(deftest test-global-keymap
  (is (= :krro.command/undo (km/lookup-key @km/global-keymap :u)))
  (is (= :krro.command/redo (km/lookup-key @km/global-keymap :r)))
  (km/set-global-key! :h :krro.command/help)
  (is (= :krro.command/help (km/lookup-key @km/global-keymap :h))))

;; ── 模式键图栈测试（基于 Frame）─────────────────────
(deftest test-mode-keymap-stack
  (let [f (test-frame)
        mode-km  (km/make-keymap {:x :mode-cmd})
        minor-km (km/make-keymap {:x :minor-cmd :y :minor-other})]
    ;; 初始：只有全局键图
    (is (= :krro.command/undo (km/lookup-key-in-context :u (frame/keymaps f))))
    ;; 压入主模式键图
    (frame/push-keymap f mode-km)
    (let [kmaps (frame/keymaps f)]
      (is (= :mode-cmd (km/lookup-key-in-context :x kmaps)))
      (is (= :krro.command/undo (km/lookup-key-in-context :u kmaps))))
    ;; 再压入副模式键图（优先级更高）
    (frame/push-keymap f minor-km)
    (let [kmaps (frame/keymaps f)]
      (is (= :minor-cmd (km/lookup-key-in-context :x kmaps)))
      (is (= :minor-other (km/lookup-key-in-context :y kmaps))))
    ;; 弹出副模式后恢复主模式
    (frame/pop-keymap f)
    (let [kmaps (frame/keymaps f)]
      (is (= :mode-cmd (km/lookup-key-in-context :x kmaps))))
    ;; 弹出主模式，回归全局
    (frame/pop-keymap f)
    (let [kmaps (frame/keymaps f)]
      (is (= :krro.command/undo (km/lookup-key-in-context :u kmaps))))))

;; ── 键序列前缀与分派测试 ──────────────────────────
(deftest test-prefix-stack
  (let [f (test-frame)
        prefix-km (km/make-keymap {"f" :krro.command/forward
                                   "b" :krro.command/backward})
        root-km   (km/make-keymap {"C-x" prefix-km})
        _         (frame/push-keymap f root-km)
        kmaps     (frame/keymaps f)
        executed-commands (atom [])]
    (with-redefs [cmd/execute-command! (fn [id & args]
                                         (swap! executed-commands conj id))]
      ;; 按 "C-x" 进入前缀
      (km/handle-key! "C-x" kmaps)
      (is (= [{:keymap prefix-km}] @km/prefix-stack))
      ;; 再按 "f" 应执行 :forward 并清空前缀
      (km/handle-key! "f" kmaps)
      (is (= [] @km/prefix-stack))
      (is (= [:krro.command/forward] @executed-commands))
      ;; 重新测试未定义键序列
      (reset! executed-commands [])
      (km/handle-key! "C-x" kmaps)
      (km/handle-key! "z" kmaps)
      (is (= [] @km/prefix-stack))
      (is (empty? @executed-commands))
      ;; 测试 reset-prefix!
      (km/handle-key! "C-x" kmaps)
      (is (= [{:keymap prefix-km}] @km/prefix-stack))
      (km/reset-prefix!)
      (is (= [] @km/prefix-stack)))))

;; ── 测试 handle-key! 直接命令（无前缀） ──────────
(deftest test-direct-command
  (let [f (test-frame)
        kmaps (frame/keymaps f)
        executed-commands (atom [])]
    (with-redefs [cmd/execute-command! (fn [id & args]
                                         (swap! executed-commands conj [id args]))]
      (km/handle-key! :u kmaps)
      (is (= [[:krro.command/undo nil]] @executed-commands))
      (km/handle-key! "C-z" kmaps)
      (is (= [[:krro.command/undo nil] [:krro.command/undo nil]] @executed-commands)))))

;; ── 动态重定义边界情况 ─────────────────────────
(deftest test-lookup-with-nil-keymap
  (is (nil? (km/lookup-key nil :x)))
  ;; 空列表查找
  (is (nil? (km/lookup-key-in-context :not-exist [])))
  (let [f (test-frame)]
    (is (nil? (km/lookup-key-in-context :zzz (frame/keymaps f))))))