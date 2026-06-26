(ns top.kzre.krro.core.ui.protocol
  "UI 渲染协议，将界面描述委托给平台实现。")

(defprotocol IRenderer
  (render-element [this element]
    "将 UI 元素描述渲染为平台特定组件。")
  (render-layout [this root-element]
    "渲染整个布局树。")
  (destroy-ui! [this]
    "销毁当前渲染的 UI。"))

(defonce ^:private current-renderer (atom nil))

(defn set-renderer! [renderer]
  (reset! current-renderer renderer))

(defn render-layout! [root-element]
  (when-let [r @current-renderer]
    (render-layout r root-element)))

(defn destroy-global-ui! []
  (when-let [r @current-renderer]
    (destroy-ui! r)))