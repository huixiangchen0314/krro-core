(ns top.kzre.krro.core.core
  "Krrō 核心入口。初始化项目，创建默认 Frame，绑定动态变量。"
  (:require [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.frame :as frame]
            [top.kzre.krro.core.command]
            [top.kzre.krro.core.commands]
            [top.kzre.krro.core.keymap]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.custom]
            [top.kzre.krro.core.hook]
            [top.kzre.krro.core.message]
            [top.kzre.krro.core.plugin]
            [top.kzre.krro.core.plugins]
            [top.kzre.krro.core.resource]
            [top.kzre.krro.core.resources]
            [top.kzre.krro.core.ui.protocol]))

(defonce ^:private initialized? (atom false))

(defn init!
  "初始化 Krrō 核心系统。创建默认 Frame 并设置为当前活动 Frame。
   此函数可多次调用但只会执行一次。"
  []
  (when (compare-and-set! initialized? false true)
    (proj/init-project!)
    (let [f (frame/create-frame :id :default)]
      (alter-var-root #'frame/*current-frame* (constantly f))
      (mode/fundamental-activate! f)
      (println "Krrō core initialized."))))

;; ── 便捷启动宏 ──────────────────────────────────────
(defmacro with-core [& body]
  `(do
     (init!)
     ~@body))