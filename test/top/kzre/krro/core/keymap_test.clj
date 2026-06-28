(ns top.kzre.krro.core.keymap-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.project :as proj]))

(use-fixtures :each
              (fn [f]
                (reset! km/keymap-stack ())
                (reset! km/prefix-stack ())
                (reset! km/global-keymap
                        (km/make-keymap {:u :krro.command/undo
                                         :r :krro.command/redo
                                         :esc :krro.command/escape
                                         "C-z" :krro.command/undo}))
                (proj/init-project!)
                (f)))

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

(deftest test-mode-keymap-stack
  (let [mode-km (km/make-keymap {:x :mode-cmd})
        minor-km (km/make-keymap {:x :minor-cmd :y :minor-other})]
    (is (= :krro.command/undo (km/lookup-key-in-context :u)))
    (km/push-keymap! mode-km)
    (is (= :mode-cmd (km/lookup-key-in-context :x)))
    (is (= :krro.command/undo (km/lookup-key-in-context :u)))
    (km/push-keymap! minor-km)
    (is (= :minor-cmd (km/lookup-key-in-context :x)))
    (is (= :minor-other (km/lookup-key-in-context :y)))
    (km/pop-keymap!)
    (is (= :mode-cmd (km/lookup-key-in-context :x)))
    (km/pop-keymap!)
    (is (= :krro.command/undo (km/lookup-key-in-context :u)))))

(deftest test-prefix-stack
  (let [prefix-km (km/make-keymap {"f" :krro.command/forward
                                   "b" :krro.command/backward})
        root-km   (km/make-keymap {"C-x" prefix-km})
        _         (km/push-keymap! root-km)
        executed-commands (atom [])]
    (with-redefs [cmd/execute-command! (fn [id & args]
                                         (swap! executed-commands conj id))]
      ;; 按 "C-x" 进入前缀，prefix-stack 存储 {:keymap ...}
      (km/handle-key! "C-x")
      (is (= [{:keymap prefix-km}] @km/prefix-stack))
      ;; 再按 "f" 应执行 :forward 并清空前缀
      (km/handle-key! "f")
      (is (= [] @km/prefix-stack))
      (is (= [:krro.command/forward] @executed-commands))
      ;; 未定义键序列
      (reset! executed-commands [])
      (km/handle-key! "C-x")
      (km/handle-key! "z")
      (is (= [] @km/prefix-stack))
      (is (empty? @executed-commands))
      ;; reset-prefix!
      (km/handle-key! "C-x")
      (is (= [{:keymap prefix-km}] @km/prefix-stack))
      (km/reset-prefix!)
      (is (= [] @km/prefix-stack)))))

(deftest test-direct-command
  (let [executed-commands (atom [])]
    (with-redefs [cmd/execute-command! (fn [id & args]
                                         (swap! executed-commands conj [id args]))]
      (km/handle-key! :u)
      (is (= [[:krro.command/undo nil]] @executed-commands))
      (km/handle-key! "C-z")
      (is (= [[:krro.command/undo nil] [:krro.command/undo nil]] @executed-commands)))))

(deftest test-lookup-with-nil-keymap
  (is (nil? (km/lookup-key nil :x)))
  (is (nil? (km/lookup-key-in-context :not-exist)))
  (is (nil? (km/lookup-key-in-context :zzz))))