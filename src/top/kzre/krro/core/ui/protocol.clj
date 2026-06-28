(ns top.kzre.krro.core.ui.protocol
  "UI 渲染协议，将 EDN 界面描述委托给平台特定渲染器。
   内核不管理任何 UI 状态，仅提供协议骨架。")

(defprotocol IRenderer
  (render-element [this element]
    "将 EDN 元素渲染为平台特定组件，返回该节点对象。")
  (render-layout [this root-element]
    "渲染整个布局树，替换当前主界面内容。
     root-element 为 Hiccup 风格向量的根元素。")
  (destroy-ui! [this]
    "销毁当前渲染的 UI 树，释放平台资源。"))

(defonce ^:private current-renderer (atom nil))

(defn set-renderer! [renderer]
  (reset! current-renderer renderer))

(defn render-layout!
  "使用已安装的渲染器渲染布局。"
  [root-element]
  (when-let [r @current-renderer]
    (render-layout r root-element)))

(defn destroy-global-ui!
  "通知当前渲染器销毁 UI。"
  []
  (when-let [r @current-renderer]
    (destroy-ui! r)))