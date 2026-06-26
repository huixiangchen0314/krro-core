(ns top.kzre.krro.core.plugin.mode
  "模式提供者插件协议。模式独立注册，生命周期通过核心 Hook 系统管理。")

(defprotocol IModeProvider
  "提供单个模式的插件。"
  (mode-id [this]   "返回模式唯一标识关键字，如 :krro.plugin.modeling")
  (mode-spec [this] "返回模式规格 map，包含 :mode/id、:mode/name、:mode/keymap、:mode/layout、:mode/hooks 等"))