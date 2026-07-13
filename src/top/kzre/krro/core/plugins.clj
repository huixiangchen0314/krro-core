(ns top.kzre.krro.core.plugins
  "内置插件类型的扩展。例如资源加载器、编解码器注册。"
  (:require [top.kzre.krro.core.plugin :refer [defplugin]]
            [top.kzre.krro.core.resource :as res]))

(defplugin :krro.plugin/resource-codec [resource pred encoder decoder]
           (res/register-codec! resource pred encoder decoder))