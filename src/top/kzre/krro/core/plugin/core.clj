(ns top.kzre.krro.core.plugin.core
  "插件系统核心：统一的插件注册、分发与管理。
   用户只需提供插件实例，系统自动识别其能力并集成到对应的子系统。"
  (:require [top.kzre.krro.core.plugin.command :as pc]
            [top.kzre.krro.core.plugin.mode :as pm]
            [top.kzre.krro.core.plugin.data :as pd]
            [top.kzre.krro.core.plugin.asset-resolver :as pa]
            [top.kzre.krro.core.command :refer [register-command!]]))

;; ── 全局插件注册表 ────────────────────────────────────
(defonce plugin-registry (atom {}))

;; ── 注册单个插件 ──────────────────────────────────────
(defn register-plugin
  "将插件实例注册到系统中。自动检测并分发其提供的能力。
   返回注册后的插件标识符。"
  [plugin]
  (let [plugin-id (or (and (satisfies? pm/IModeProvider plugin) (pm/mode-id plugin))
                      (gensym "krro.plugin/"))]

    ;; 1. 命令提供者（立即生效）
    (when (satisfies? pc/ICommandProvider plugin)
      (doseq [[cmd-id handler] (pc/commands plugin)]
        (register-command! cmd-id handler)))

    ;; 2. 模式提供者
    ;; TODO: 调用 mode/register-mode! 将 (pm/mode-spec plugin) 注册到模式系统
    ;; 该函数将在 mode 模块中实现，用于将模式规格存入全局模式表。

    ;; 3. 数据提供者（暂存，实际调用由项目生命周期触发）
    (when (satisfies? pd/IDataProvider plugin)
      ;; 仅记录，不立即执行 init-data / migrate-data
      (swap! plugin-registry update :data-providers conj plugin))

    ;; 4. 资产解析器
    ;; TODO: 调用 vcs/register-asset-resolver! 将插件注册到版本控制系统
    ;; 该函数将在 VCS 模块中实现，用于语义分块。

    ;; 记录到全局注册表
    (swap! plugin-registry assoc plugin-id plugin)
    plugin-id))

;; ── 卸载插件 ──────────────────────────────────────────
(defn unregister-plugin
  "卸载一个已注册的插件。"
  [plugin-id]
  (when-let [plugin (get @plugin-registry plugin-id)]
    (swap! plugin-registry dissoc plugin-id)
    (println "Plugin" plugin-id "unregistered.")))

;; ── 列出已注册插件 ────────────────────────────────────
(defn registered-plugins []
  (keys @plugin-registry))