(ns top.kzre.krro.core.use-plugin
  "use-plugin macro"
  (:require [top.kzre.krro.core.plugin :as plugin]))

(defrecord PluginInfo [])

(defonce plugin-infos (atom {}))

(defmacro use-plugin
  [plugin-id & {:keys [requires init config keymap custom] :as opts}]
  (let [plugin-map (merge
                     {:id plugin-id
                      :requires (or requires [])
                      :init init
                      :config config}
                     (dissoc opts :requires :init :config))]
    `(let [p# (merge ~plugin-map {:enabled false})]
       (plugin/reg-plugin p#)
       plugin-id)))

