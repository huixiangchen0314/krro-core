(ns top.kzre.krro.core.plugin.command
  "命令提供者插件协议。")

(defprotocol ICommandProvider
  "提供一组命令的插件。"
  (commands [this] "返回 {command-id handler-fn} 的 map"))