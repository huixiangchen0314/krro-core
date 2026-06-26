(ns top.kzre.krro.core.ui.protocol
  "UI 渲染协议，将界面描述（EDN）委托给平台实现。
   具体渲染器在应用层（JavaFX、Web、终端等）实现，实现本协议即可。")

(defprotocol IRenderer
  "UI 渲染器协议，任何平台只需实现本协议即可接入 Krrō UI 系统。"
  (render-element [this element]
    "将单个 UI 元素描述渲染为平台特定组件（如 JavaFX Node、DOM 元素）。
     element 为 Hiccup 风格向量 [tag attrs? & children]。")
  (render-layout [this root-element]
    "渲染整个布局树，通常用于模式切换时替换主界面区域。
     root-element 为根 UI 元素描述。")
  (destroy-ui! [this]
    "销毁当前渲染的 UI，清理平台资源。"))

(defonce ^:private current-renderer (atom nil))

(defn set-renderer!
  "安装全局 UI 渲染器。由应用启动时调用。"
  [renderer]
  (reset! current-renderer renderer))

(defn render-layout!
  "使用当前渲染器渲染布局树。若未安装渲染器则静默忽略。"
  [root-element]
  (when-let [r @current-renderer]
    (render-layout r root-element)))    ; 调用协议方法，无冲突

(defn destroy-global-ui!
  "通知当前渲染器销毁 UI。注意：函数名故意加 global 避免与协议 destroy-ui! 冲突。"
  []
  (when-let [r @current-renderer]
    (destroy-ui! r)))   ; 这里调用的是协议方法，完全没问题