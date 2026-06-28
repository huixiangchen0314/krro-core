(ns top.kzre.krro.core.command-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer :all]
    [top.kzre.krro.core.command :as cmd :refer [defcmd]]
    [top.kzre.krro.core.message :as msg]
    [top.kzre.krro.core.project :as proj]))

(use-fixtures :each
              (fn [f]
                (proj/init-project!)
                (reset! msg/messages [])
                (f)))

(deftest test-register-command
  (let [handler (fn [proj] (assoc proj :test :ok))]
    (cmd/register-command! :test.cmd/simple handler :description "A test command")
    (let [found (cmd/lookup-command :test.cmd/simple)]
      (is found)
      (is (= :test.cmd/simple (:id found)))
      (is (= "A test command" (:description found))))))

(deftest test-register-overwrite
  (let [h1 (fn [p] (assoc p :v 1))
        h2 (fn [p] (assoc p :v 2))]
    (cmd/register-command! :test.cmd/over h1)
    (cmd/register-command! :test.cmd/over h2)
    (let [found (cmd/lookup-command :test.cmd/over)
          handler (:handler found)]
      (is (= 2 (:v (handler {})))))))

(deftest test-lookup-unknown-command
  (is (nil? (cmd/lookup-command :test.cmd/nonexistent))))

(deftest test-execute-command
  (let [called (atom false)
        handler (fn [proj]
                  (reset! called true)
                  (assoc proj :modified true))]
    (cmd/register-command! :test.cmd/exec handler)
    (cmd/execute-command! :test.cmd/exec)
    (is @called)
    (is (:modified @proj/project))))

(deftest test-execute-command-with-args
  (let [captured (atom nil)
        handler (fn [proj x y]
                  (reset! captured [x y])
                  (assoc proj :sum (+ x y)))]
    (cmd/register-command! :test.cmd/with-args handler)
    (cmd/execute-command! :test.cmd/with-args 3 4)
    (is (= [3 4] @captured))
    (is (= 7 (:sum @proj/project)))))

(deftest test-execute-unknown-command-errors
  (reset! msg/messages [])
  (cmd/execute-command! :test.cmd/ghost)
  (is (some #(and (= (:type %) :error)
                  (str/includes? (:content %) "Unknown command"))
            @msg/messages)))

(defcmd adder [project a b]
        :description "Add a and b into project."
        (assoc project :result (+ a b)))

(deftest test-defcmd-registers-command
  (let [cmd-info (cmd/lookup-command :top.kzre.krro.core.command-test/adder)]
    (is cmd-info)
    (is (= "Add a and b into project." (:description cmd-info)))))

(deftest test-defcmd-execution
  (cmd/execute-command! :top.kzre.krro.core.command-test/adder 10 20)
  (is (= 30 (:result @proj/project))))