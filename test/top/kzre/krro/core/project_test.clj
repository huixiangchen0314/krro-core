(ns top.kzre.krro.core.project-test
  (:require [clojure.test :refer :all]
            [top.kzre.krro.core.project :as proj]))

(use-fixtures :each
              (fn [f]
                (proj/init-project!)
                (f)))

(deftest test-init-project
  ;; 项目原子不再包含 :krro/modes
  (is (= {:krro/meta   {:version "0.1.0"
                        :name "Untitled"
                        :created-at (get-in @proj/project [:krro/meta :created-at])
                        :modified-at (get-in @proj/project [:krro/meta :modified-at])}
          :krro/plugins {:active #{}}}
         @proj/project)))

(deftest test-init-project-with-name
  (proj/init-project! :name "MyProject")
  (is (= "MyProject" (get-in @proj/project [:krro/meta :name]))))

(deftest test-get-in-project
  (is (= "Untitled" (proj/get-in-project [:krro/meta :name])))
  ;; :krro/modes 不再存在于项目原子
  (is (nil? (proj/get-in-project [:krro/modes :major])))
  (is (nil? (proj/get-in-project [:nonexistent :path])))
  (is (= 42 (proj/get-in-project [:nonexistent :path] 42))))

(deftest test-update-project
  (proj/update-project! #(assoc % :custom-key "hello"))
  (is (= "hello" (proj/get-in-project [:custom-key])))
  (is (= "Untitled" (proj/get-in-project [:krro/meta :name]))))

(deftest test-update-project-multiple
  (proj/update-project! #(update % :count (fnil inc 0)))
  (proj/update-project! #(update % :count (fnil inc 0)))
  (is (= 2 (proj/get-in-project [:count]))))