(ns top.kzre.krro.core.ui.protocol
  "UI 渲染协议，将界面描述委托给平台实现。")

(defprotocol IRenderer
  (render-element [this element]
    "将 UI 元素描述渲染为平台特定组件。")
  (render-layout [this root-element]
    "渲染整个布局树。")
  (destroy-ui! [this]
    "销毁当前渲染的 UI。")
  (bind-command-events [this element]   ;; 新增
    "将元素描述中的 :on-command 绑定到平台事件，回调到 command/execute-command。"))

(defonce ^:private current-renderer (atom nil))

(defn set-renderer! [renderer]
  (reset! current-renderer renderer))

(defn render-layout! [root-element]
  (when-let [r @current-renderer]
    (render-layout r root-element)))

(defn destroy-global-ui! []
  (when-let [r @current-renderer]
    (destroy-ui! r)))