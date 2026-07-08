(ns top.kzre.krro.core.commands
  "内核内置的一些命令。"
  (:require [clojure.string :as str]
            [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.message :as msg]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.plugin :as plugin]))

(defn- inspect
  "输出当前 Krrō 系统状态。"
  [project]
  (let [major (get-in project [:krro/modes :major])
        minors (get-in project [:krro/modes :minors])
        plugins @plugin/plugin-registry
        commands @cmd/command-registry
        customs @custom/custom-registry]
    (msg/message "=== Krrō Inspect ===")
    (msg/message (str "Active major mode: " major))
    (msg/message (str "Active minor modes: " minors))
    (msg/message (str "Registered plugins: " (keys plugins)))
    (msg/message (str "Registered commands: " (count commands)))
    (msg/message (str "Custom variables: " (keys customs)))
    (msg/message (str "Project keys: " (keys project))))
  project)

(defn- describe-mode
  "输出当前 major mode 的详细信息。"
  [project]
  (let [major (get-in project [:krro/modes :major])
        spec (when major (mode/get-mode-spec major))]
    (if spec
      (do
        (msg/message (str "Major mode: " (:mode/id spec)))
        (msg/message (str "Name: " (:mode/name spec)))
        (msg/message (str "Parent: " (:mode/parent spec)))
        (msg/message (str "Keymap present: " (boolean (:mode/keymap spec))))
        (msg/message (str "Layout present: " (boolean (:mode/layout spec))))
        (msg/message (str "Variables: " (keys (:mode/variables spec)))))
      (msg/message "No major mode active.")))
  project)

(defn- apropos
  "搜索匹配的命令名称。"
  [project query]
  (let [matches (filter #(str/includes? (name %) query)
                        (keys @cmd/command-registry))]
    (msg/message (str "Commands matching \"" query "\":"))
    (doseq [m (sort matches)]
      (msg/message (str "  " m))))
  project)

;; ── 注册命令 ──────────────────────────────────────────

(cmd/register-command! :krro.core/inspect inspect
                       :description "Print current system state")

(cmd/register-command! :krro.core/describe-mode describe-mode
                       :description "Describe the current major mode")

(cmd/register-command! :krro.core/apropos apropos
                       :description "Search registered commands by name"
                       :interactive [:string])