(ns top.kzre.krro.core.keymap-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.project :as proj]))

(use-fixtures :each
              (fn [f]
                ;; 重置全局状态，保证测试隔离
                (reset! km/keymap-stack ())
                (reset! km/prefix-stack ())
                (reset! km/global-keymap
                        (km/make-keymap {:u :krro.command/undo
                                         :r :krro.command/redo
                                         :esc :krro.command/escape
                                         "C-z" :krro.command/undo}))
                (proj/init-project!)  ;; 重置项目
                (f)))

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
    (is (= :cmd-b (km/lookup-key child :b)))  ; 继承
    (is (nil? (km/lookup-key child :z)))))

;; ── 全局键图与动态修改测试 ──────────────────────────
(deftest test-global-keymap
  (is (= :krro.command/undo (km/lookup-key @km/global-keymap :u)))
  (is (= :krro.command/redo (km/lookup-key @km/global-keymap :r)))
  (km/set-global-key! :h :krro.command/help)
  (is (= :krro.command/help (km/lookup-key @km/global-keymap :h))))

;; ── 模式键图栈测试 ──────────────────────────────
(deftest test-mode-keymap-stack
  (let [mode-km (km/make-keymap {:x :mode-cmd})
        minor-km (km/make-keymap {:x :minor-cmd :y :minor-other})]
    ;; 空栈时只查全局
    (is (= :krro.command/undo (km/lookup-key-in-context :u)))
    ;; 压入主模式键图
    (km/push-keymap! mode-km)
    (is (= :mode-cmd (km/lookup-key-in-context :x)))
    (is (= :krro.command/undo (km/lookup-key-in-context :u)))
    ;; 再压入副模式键图（优先级更高）
    (km/push-keymap! minor-km)
    (is (= :minor-cmd (km/lookup-key-in-context :x)))  ; 被副模式覆盖
    (is (= :minor-other (km/lookup-key-in-context :y)))
    ;; 弹出副模式后恢复
    (km/pop-keymap!)
    (is (= :mode-cmd (km/lookup-key-in-context :x)))
    ;; 弹出主模式，回归全局
    (km/pop-keymap!)
    (is (= :krro.command/undo (km/lookup-key-in-context :u)))))

;; ── 键序列前缀与分派测试 ──────────────────────────
(deftest test-prefix-stack
  (let [prefix-km (km/make-keymap {"f" :krro.command/forward
                                   "b" :krro.command/backward})
        root-km   (km/make-keymap {"C-x" prefix-km})
        _         (km/push-keymap! root-km)
        ;; 模拟命令执行记录
        executed-commands (atom [])]
    ;; 用 with-redefs 替换 execute-command，避免真实修改项目
    (with-redefs [cmd/execute-command! (fn [proj id & args]
                                        (swap! executed-commands conj id))]
      ;; 按 "C-x" 进入前缀
      (km/handle-key! "C-x")
      ;; 此时前缀栈应包含 prefix-km
      (is (= [prefix-km] @km/prefix-stack))
      ;; 再按 "f" 应执行 :forward 并清空前缀
      (km/handle-key! "f")
      (is (= [] @km/prefix-stack))
      (is (= [:krro.command/forward] @executed-commands))
      ;; 重新测试未定义键序列
      (reset! executed-commands [])
      (km/handle-key! "C-x")
      (km/handle-key! "z")  ;; 未定义
      (is (= [] @km/prefix-stack))
      ;; 这里可以捕获输出，但简单起见仅验证栈清空
      (is (empty? @executed-commands)) ;; 未定义键不执行命令
      ;; 测试 reset-prefix!
      (km/handle-key! "C-x")
      (is (= [prefix-km] @km/prefix-stack))
      (km/reset-prefix!)
      (is (= [] @km/prefix-stack)))))

;; ── 测试 handle-key! 直接命令（无前缀） ──────────
(deftest test-direct-command
  (let [executed-commands (atom [])]
    (with-redefs [cmd/execute-command! (fn [proj id & args]
                                        (swap! executed-commands conj [id args]))]
      (km/handle-key! :u)
      (is (= [[:krro.command/undo nil]] @executed-commands))
      (km/handle-key! "C-z")
      (is (= [[:krro.command/undo nil] [:krro.command/undo nil]] @executed-commands)))))

;; ── 动态重定义边界情况 ─────────────────────────
(deftest test-lookup-with-nil-keymap
  (is (nil? (km/lookup-key nil :x)))
  (is (nil? (km/lookup-key-in-context :not-exist)))
  ;; 当前只有全局，查不到返回 nil
  (is (nil? (km/lookup-key-in-context :zzz))))