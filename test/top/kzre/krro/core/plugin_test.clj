(ns top.kzre.krro.core.plugin-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.plugin :as plugin]))

(use-fixtures :each
              (fn [f]
                (reset! plugin/plugin-registry {})
                (f)))

;; ── 模拟插件定义 ──────────────────────────────────────
(defrecord TestPlugin [id name type])   ;; 添加 type 字段

;; ── 测试多方法分派 ────────────────────────────────────
(deftest test-register-plugin-default
  (let [p (->TestPlugin :p1 "test" :unknown)]
    (is (nil? (plugin/register-plugin! p)))
    (is (empty? (plugin/registered-plugins)))))

;; 注册 :test-plugin 类型的具体实现
(defmethod plugin/register-plugin! :test-plugin [p]
  (swap! plugin/plugin-registry assoc (:id p) p)
  (:id p))

(deftest test-register-known-type
  (let [p (->TestPlugin :p2 "known" :test-plugin)]
    (is (= :p2 (plugin/register-plugin! p)))
    (is (contains? (plugin/registered-plugins) :p2))
    (is (= p (get @plugin/plugin-registry :p2)))))

(deftest test-unregister-plugin
  (let [p (->TestPlugin :p3 "temp" :test-plugin)]
    (plugin/register-plugin! p)
    (is (contains? (plugin/registered-plugins) :p3))
    (plugin/unregister-plugin :p3)
    (is (not (contains? (plugin/registered-plugins) :p3)))))

(deftest test-registered-plugins-empty
  (is (empty? (plugin/registered-plugins))))

(deftest test-multiple-plugins
  (let [p1 (->TestPlugin :a "first" :test-plugin)
        p2 (->TestPlugin :b "second" :test-plugin)]
    (plugin/register-plugin! p1)
    (plugin/register-plugin! p2)
    (let [all (plugin/registered-plugins)]
      (is (= 2 (count all)))
      (is (contains? all :a))
      (is (contains? all :b)))))