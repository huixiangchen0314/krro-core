(ns top.kzre.krro.core.plugins
  "内置插件类型的扩展。例如资源加载器。"
  (:require [top.kzre.krro.core.plugin :refer [define-plugin]]
            [top.kzre.krro.core.resource :as resource]))

(define-plugin :krro.plugin/resource-loader [resource-type loader]
               (resource/register-resource-loader! resource-type loader))