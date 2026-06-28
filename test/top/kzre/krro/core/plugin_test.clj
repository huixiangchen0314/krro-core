(ns top.kzre.krro.core.plugin-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.plugin :as plugin]))

(use-fixtures :each
              (fn [f]
                (reset! plugin/plugin-registry [])
                (f)))

;; ── 提前定义 captured-calls ──────────────────────────
(defonce captured-calls (atom []))

;; ── 顶层宏定义，使用已有的 captured-calls ─────────────
(plugin/define-plugin :test.macro [resource-type loader]
                      (swap! captured-calls conj [resource-type loader]))

;; ── 测试 ────────────────────────────────────────────
(deftest test-register-plugin-default
  (let [called (atom false)
        p {:name :p1
           :type :default
           :init (fn [] (reset! called true))}]
    (is (= :p1 (plugin/register-plugin! p)))
    (is @called)
    (is (some #(= (:name %) :p1) (plugin/registered-plugins)))))

(deftest test-register-plugin-without-type
  (let [p {:name :p2
           :init (fn [] nil)}]
    (is (= :p2 (plugin/register-plugin! p)))
    (is (some #(= (:name %) :p2) (plugin/registered-plugins)))))

(deftest test-unregister-plugin
  (let [p1 {:name :p3
            :type :test}
        p2 {:name :p3
            :type :test}]
    (plugin/register-plugin! p1)
    (plugin/register-plugin! p2)
    (is (= 2 (count (filter #(= (:name %) :p3) (plugin/registered-plugins)))))
    (plugin/unregister-plugin :p3)
    (is (empty? (filter #(= (:name %) :p3) (plugin/registered-plugins))))))

(deftest test-registered-plugins-empty
  (is (empty? (plugin/registered-plugins))))

(deftest test-multiple-plugins
  (let [p1 {:name :a
            :type :test}
        p2 {:name
            :b :type :test}]
    (plugin/register-plugin! p1)
    (plugin/register-plugin! p2)
    (is (= 2 (count (plugin/registered-plugins))))
    (is (some #(= (:name %) :a) (plugin/registered-plugins)))
    (is (some #(= (:name %) :b) (plugin/registered-plugins)))))

(deftest test-define-plugin-macro
  (reset! captured-calls [])
  (let [p {:name :test
           :type :test.macro
           :resource-type :mesh
           :loader :my-loader}]
    (plugin/register-plugin! p)
    (is (= [[:mesh :my-loader]] @captured-calls))))