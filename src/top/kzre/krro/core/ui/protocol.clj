(ns top.kzre.krro.core.ui.protocol
  "UI 渲染协议，将 EDN 界面描述委托给平台特定渲染器。
   内核不管理任何 UI 状态，仅提供协议骨架。
   支持多 Frame：渲染布局时需指定所属 Frame。")

(defprotocol IRenderer
  (render-frame [this ui-desc frame]
    "渲染整个布局树到指定 Frame 的界面容器中。
     ui-desc 为 Hiccup 风格向量的根元素。
     frame 为当前 Frame 实例（IFrame 实现）。")
  (destroy-frame [this frame]
    "销毁当前渲染的 UI 树，释放平台资源。"))

(defonce ^:private current-renderer (atom nil))

(defn set-renderer! [renderer]
  (reset! current-renderer renderer))

(defn render-frame!
  "使用已安装的渲染器渲染布局到当前 Frame。
   若 ui-desc 是函数，则先调用 (ui-desc frame) 获取布局向量。
   如果渲染器未安装，则抛出异常。"
  [ui-desc frame]
  (if-let [r @current-renderer]
    (let [layout (if (fn? ui-desc)
                   (ui-desc frame)
                   ui-desc)]
      (render-frame r layout frame))
    (throw (ex-info "Renderer not installed, cannot render frame" {:frame frame}))))

(defn destroy-frame!
  "通知当前渲染器销毁 UI。
   如果渲染器未安装，则抛出异常。"
  [frame]
  (if-let [r @current-renderer]
    (destroy-frame r frame)
    (throw (ex-info "Renderer not installed, cannot destroy frame" {:frame frame}))))
