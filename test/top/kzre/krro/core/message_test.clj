(ns top.kzre.krro.core.message-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.message :as msg]))

(use-fixtures :each
              (fn [f]
                (reset! msg/messages [])
                (msg/set-max-messages! 100)   ;; 恢复默认容量
                (f)))

(deftest test-add-message
  (msg/message "info message")
  (is (= [{:content "info message" :type :info}] @msg/messages))
  (msg/warn "warning")
  (is (= [{:content "info message" :type :info}
          {:content "warning" :type :warn}] @msg/messages))
  (msg/error "error")
  (is (= [{:content "info message" :type :info}
          {:content "warning" :type :warn}
          {:content "error" :type :error}] @msg/messages)))

(deftest test-max-messages
  (msg/set-max-messages! 3)
  (msg/message "m1")
  (msg/message "m2")
  (msg/message "m3")
  (is (= 3 (count @msg/messages)))
  (msg/message "m4")  ;; 应丢弃最旧的 m1
  (let [msgs @msg/messages]
    (is (= 3 (count msgs)))
    (is (= "m2" (:content (first msgs))))
    (is (= "m3" (:content (second msgs))))
    (is (= "m4" (:content (nth msgs 2))))))

(deftest test-drain-messages  ;; 应用层手动清空
  (msg/message "hello")
  (msg/error "fail")
  (let [drained @msg/messages]
    (reset! msg/messages [])
    (is (= 2 (count drained)))
    (is (= "hello" (:content (first drained))))
    (is (= :error (:type (second drained)))))
  (is (empty? @msg/messages)))