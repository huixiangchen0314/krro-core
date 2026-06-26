(ns top.kzre.krro.core.plugin.data
  "数据结构提供者插件协议。")

(defprotocol IDataProvider
  "提供项目数据结构的插件。"
  (init-data [this project] "首次加载时初始化数据")
  (migrate-data [this project] "升级/迁移已有数据"))