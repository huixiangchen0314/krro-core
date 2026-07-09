(ns top.kzre.krro.core.hook-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.hook :as hook]))

(deftest test-make-hook
  (let [h (hook/make-hook)]
    (is (instance? clojure.lang.Atom h))
    (is (= [] @h))))

(deftest test-add-hook
  (let [h (hook/make-hook)
        f (fn [] :called)]
    (hook/add-hook! h f)
    (is (= [f] @h))
    ;; 可重复添加相同函数
    (hook/add-hook! h f)
    (is (= [f f] @h))))

(deftest test-remove-hook
  (let [h (hook/make-hook)
        a (fn [] :a)
        b (fn [] :b)]
    (hook/add-hook! h a)
    (hook/add-hook! h b)
    (is (= [a b] @h))
    (hook/remove-hook! h a)
    (is (= [b] @h))
    ;; 移除不存在的函数无影响
    (hook/remove-hook! h a)
    (is (= [b] @h))
    ;; 移除全部
    (hook/remove-hook! h b)
    (is (= [] @h))))

(deftest test-run-hooks
  (let [h (hook/make-hook)
        calls (atom [])]
    (hook/add-hook! h #(swap! calls conj [:first %]))
    (hook/add-hook! h #(swap! calls conj [:second %]))
    (hook/run-hook! h "arg")
    (is (= [[:first "arg"] [:second "arg"]] @calls))))

(deftest test-run-empty-hook
  (let [h (hook/make-hook)]
    (is (nil? (hook/run-hook! h)))) ; doseq 返回 nil
  )

(deftest test-hook-order
  (let [h (hook/make-hook)
        result (atom [])
        f1 #(swap! result conj 1)
        f2 #(swap! result conj 2)]
    (hook/add-hook! h f1)
    (hook/add-hook! h f2)
    (hook/run-hook! h)
    (is (= [1 2] @result)))) ; 保持添加顺序