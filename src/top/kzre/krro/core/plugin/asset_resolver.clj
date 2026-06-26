(ns top.kzre.krro.core.plugin.asset-resolver
  "资产解析器插件协议。")

(defprotocol IAssetResolver
  "将项目数据分解为有意义的资产块。"
  (resolve-assets [this project-data]
    "返回 {asset-path asset-data} 的 map")
  (assemble-project [this asset-map]
    "从资产 map 重新构建项目数据"))