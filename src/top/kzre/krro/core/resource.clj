(ns top.kzre.krro.core.resource
  "资源虚拟代理机制。内核提供加载器注册与分派，具体加载/缓存由插件管理。
   错误信息通过 message 系统输出，加载失败时返回 nil。"
  (:require [top.kzre.krro.core.message :as msg]))

(defprotocol IResourceLoader
  (load-resource [this proxy]
    "根据代理 map 加载实际资源。返回类型由实现决定。"))

(defonce ^:private loaders (atom {}))

(defn register-resource-loader!
  "注册一个资源加载器，:type 关键字用于匹配代理中的 :krro.resource/type。"
  [type loader]
  (swap! loaders assoc type loader))

(defn deref-resource
  "解析资源代理。根据代理的 :krro.resource/type 查找对应加载器并加载。
   若找不到匹配的加载器或加载失败，输出错误消息并返回 nil。"
  [proxy]
  (if-let [loader (get @loaders (:krro.resource/type proxy))]
    (try
      (load-resource loader proxy)
      (catch Exception e
        (msg/error (str "Failed to load resource " (pr-str proxy) ": " (.getMessage e)))
        nil))
    (do
      (msg/error (str "No resource loader for type " (:krro.resource/type proxy) " when loading " (pr-str proxy)))
      nil)))

(defn make-proxy
  "创建一个资源代理 map。至少需要 :type，其他键值对将合并到代理中。"
  [type & {:as attrs}]
  (assoc attrs :krro.resource/type type))