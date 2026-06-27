(ns top.kzre.krro.core.commands
  "内核内置的一些命令。"
  (:require [clojure.string :as str]
            [top.kzre.krro.core.command :as cmd]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.mode :as mode]
            [top.kzre.krro.core.plugin :as plugin]))

;; ── 内置命令定义 ──────────────────────────────────────

(defn- inspect
  "打印当前 Krrō 系统状态。"
  [project]
  (let [major (get-in project [:krro/modes :major])
        minors (get-in project [:krro/modes :minors])
        plugins @plugin/plugin-registry
        commands @cmd/command-registry
        customs @custom/custom-registry]
    (println "=== Krrō Inspect ===")
    (println "Active major mode:" major)
    (println "Active minor modes:" minors)
    (println "Keymap stack depth:" (count @km/keymap-stack))
    (println "Registered plugins:" (keys plugins))
    (println "Registered commands:" (count commands))
    (println "Custom variables:" (keys customs))
    (println "Project keys:" (keys project)))
  project)

(defn- describe-mode
  "打印当前 major mode 的详细信息。"
  [project]
  (let [major (get-in project [:krro/modes :major])
        spec (when major (mode/get-mode-spec major))]
    (if spec
      (println "Major mode:" (:mode/id spec) "\n"
               "Name:" (:mode/name spec) "\n"
               "Parent:" (:mode/parent spec) "\n"
               "Keymap present:" (boolean (:mode/keymap spec)) "\n"
               "Layout present:" (boolean (:mode/layout spec)) "\n"
               "Variables:" (keys (:mode/variables spec)))
      (println "No major mode active.")))
  project)

(defn- apropos
  "搜索匹配的命令名称。"
  [project query]
  (let [matches (filter #(str/includes? (name %) query)
                        (keys @cmd/command-registry))]
    (println "Commands matching" (pr-str query) ":")
    (doseq [m (sort matches)]
      (println " " m)))
  project)

;; ── 注册命令 ──────────────────────────────────────────

(cmd/register-command! :krro.command/inspect inspect
                       :description "Print current system state")

(cmd/register-command! :krro.command/describe-mode describe-mode
                       :description "Describe the current major mode")

(cmd/register-command! :krro.command/apropos apropos
                       :description "Search registered commands by name")