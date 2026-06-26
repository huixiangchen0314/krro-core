(ns top.kzre.krro.core.custom-test
  (:require
   [clojure.test :refer :all]
   [top.kzre.krro.core.custom :as custom :refer [defcustom]]))

;; 为了测试 defcustom 宏，需要在测试中引用声明的变量
;; 这里先定义一些变量用于测试
(defcustom test-var-a 42 "A test variable" :type :integer :group :test)
(defcustom test-var-b "hello" "Another test variable" :type :string)

(deftest test-defcustom-creates-atom
  (is (instance? clojure.lang.Atom test-var-a))
  (is (instance? clojure.lang.Atom test-var-b)))

(deftest test-defcustom-defaults
  (is (= 42 @test-var-a))
  (is (= "hello" @test-var-b)))

(deftest test-get-custom
  (is (= 42 (custom/get-custom test-var-a)))
  (is (= "hello" (custom/get-custom test-var-b))))

(deftest test-set-custom
  (custom/set-custom! test-var-a 99)
  (is (= 99 @test-var-a))
  (custom/set-custom! test-var-b "world")
  (is (= "world" @test-var-b))
  ;; 恢复
  (custom/set-custom! test-var-a 42)
  (custom/set-custom! test-var-b "hello"))

(deftest test-all-customs
  (let [all (custom/all-customs)]
    (is (contains? all 'test-var-a))
    (is (contains? all 'test-var-b))
    (is (= 42 (:default (get all 'test-var-a))))
    (is (= :integer (:type (get all 'test-var-a))))))

(deftest test-push-pop-local-value
  ;; 推送局部值
  (custom/push-local-value! test-var-a 7)
  (is (= 7 @test-var-a))
  ;; 再次推送
  (custom/push-local-value! test-var-a 3)
  (is (= 3 @test-var-a))
  ;; 弹出回到上一层
  (custom/pop-local-value! test-var-a)
  (is (= 7 @test-var-a))
  ;; 再次弹出回到全局默认
  (custom/pop-local-value! test-var-a)
  (is (= 42 @test-var-a))
  ;; 安全弹出无栈情形
  (custom/pop-local-value! test-var-a)
  (is (= 42 @test-var-a)))  ;; 保持全局默认

(deftest test-local-value-isolation
  ;; 确保局部值不影响全局注册表中的默认值
  (let [default-val (get-in @custom/custom-registry ['test-var-a :default])]
    (is (= 42 default-val))
    (custom/push-local-value! test-var-a 100)
    (is (= 100 @test-var-a))
    ;; 注册表中的 default 不变
    (is (= 42 (get-in @custom/custom-registry ['test-var-a :default])))
    (custom/pop-local-value! test-var-a)
    (is (= 42 @test-var-a))))