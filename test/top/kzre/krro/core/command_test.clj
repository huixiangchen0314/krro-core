(ns top.kzre.krro.core.command-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.command :as cmd :refer [defcmd]]
            [top.kzre.krro.core.project :as proj]))

(use-fixtures :each
              (fn [f]
                (proj/init-project!)   ;; 只重置项目
                (f)))

(deftest test-register-command
  (let [handler (fn [proj] (assoc proj :test :ok))]
    (cmd/register-command! :test.cmd/simple handler :description "A test command")
    (let [found (cmd/lookup-command :test.cmd/simple)]
      (is found)
      (is (= :test.cmd/simple (-> found meta :command/id)))
      (is (= "A test command" (-> found meta :command/description))))))

(deftest test-register-overwrite
  (let [h1 (fn [p] (assoc p :v 1))
        h2 (fn [p] (assoc p :v 2))]
    (cmd/register-command! :test.cmd/over h1)
    (cmd/register-command! :test.cmd/over h2)
    (let [found (cmd/lookup-command :test.cmd/over)]
      (is (= 2 (:v (found {})))))))

(deftest test-lookup-unknown-command
  (is (nil? (cmd/lookup-command :test.cmd/nonexistent))))

(deftest test-execute-command
  (let [called (atom false)
        handler (fn [proj]
                  (reset! called true)
                  (assoc proj :modified true))]
    (cmd/register-command! :test.cmd/exec handler)
    (cmd/execute-command! proj/project :test.cmd/exec)
    (is @called)
    (is (:modified @proj/project))))

(deftest test-execute-command-with-args
  (let [captured (atom nil)
        handler (fn [proj x y]
                  (reset! captured [x y])
                  (assoc proj :sum (+ x y)))]
    (cmd/register-command! :test.cmd/with-args handler)
    (cmd/execute-command! proj/project :test.cmd/with-args 3 4)
    (is (= [3 4] @captured))
    (is (= 7 (:sum @proj/project)))))

(deftest test-execute-unknown-command-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (cmd/execute-command! proj/project :test.cmd/ghost))))

;; defcmd 在顶层编译时就注册了命令
(defcmd adder [project a b]
        "Add a and b into project."
        (assoc project :result (+ a b)))

(deftest test-defcmd-registers-command
  (let [handler (cmd/lookup-command :top.kzre.krro.core.command-test/adder)]
    (is handler)
    (is (= "Add a and b into project." (-> handler meta :command/description)))))

(deftest test-defcmd-execution
  (cmd/execute-command! proj/project :top.kzre.krro.core.command-test/adder 10 20)
  (is (= 30 (:result @proj/project))))