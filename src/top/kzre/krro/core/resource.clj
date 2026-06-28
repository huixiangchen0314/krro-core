(ns top.kzre.krro.core.resource
  "资源虚拟代理机制。内核提供加载器注册与分派，具体加载/缓存由插件管理。")

(defprotocol IResourceLoader
  (load-resource [this proxy]
    "根据代理 map 加载实际资源。返回类型由实现决定。"))

(defonce ^:private loaders (atom {}))

(defn register-resource-loader!
  "注册一个资源加载器，:type 关键字用于匹配代理中的 :krro.resource/type。"
  [type loader]
  (swap! loaders assoc type loader))

(defn deref-resource
  "解析资源代理。根据代理的 :krro.resource/type 查找对应加载器，并调用 load-resource。
   若未找到匹配的加载器，抛出异常。"
  [proxy]
  (if-let [loader (get @loaders (:krro.resource/type proxy))]
    (load-resource loader proxy)
    (throw (ex-info "No resource loader for type" {:type (:krro.resource/type proxy)
                                                   :proxy proxy}))))


(defn make-proxy
  "创建一个资源代理 map。至少需要 :type，其他键值对将合并到代理中。"
  [type & {:as attrs}]
  (assoc attrs :krro.resource/type type))