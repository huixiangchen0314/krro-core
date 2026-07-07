(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'top.kzre/krro-core)
(def version "0.1.0")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/krro-core-0.1.0.jar")            ;; 硬编码，与 Makefile 一致
(def uber-file "target/krro-core-0.1.0-standalone.jar")

(defn clean [_]
      (b/delete {:path "target"}))

(defn jar [_]
      (clean nil)
      (b/write-pom {:class-dir class-dir
                    :lib lib
                    :version version
                    :basis basis
                    :src-dirs ["src"]
                    :scm {:url "https://github.com/topkzre/krro-core"
                          :connection "scm:git:git://github.com/topkzre/krro-core.git"
                          :developerConnection "scm:git:ssh://git@github.com:topkzre/krro-core.git"}})
      (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
      (b/jar {:class-dir class-dir
              :jar-file jar-file})
      (println "Jar created:" jar-file))

(defn test-all [_]
      (b/process {:command-args ["clojure" "-M:test"]}))