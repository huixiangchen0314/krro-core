(ns top.kzre.krro.core.window-impl
  (:require
   [top.kzre.krro.core.frame :as frame]
   [top.kzre.krro.core.message :as msg]
   [top.kzre.krro.core.mode :as mode]
   [top.kzre.krro.core.ui.protocol :as ui]
   [top.kzre.krro.core.window :as win]
   [top.kzre.krro.core.window-layout :as window-layout]))


;; ── Window 实现（委托原生窗口操作给 native） ────
(defrecord Window
  [id frames-atom layout-atom current-frame-id-atom native]

  win/IWindow
  (window-id [_] id)
  (get-frame [_ frame-id] (get @frames-atom frame-id))
  (current-frame [_]
    (when-let [fid @current-frame-id-atom]
      (get @frames-atom fid)))
  (set-current-frame! [_ f]
    (reset! current-frame-id-atom (frame/frame-id f)))

  (frames [_] (vals @frames-atom))
  (split-frame! [this direction {:keys [ratio reversed?]}]
    (if-let [focus-frame (win/current-frame this)]
      (let [ratio (or ratio 0.5)
            reversed? (or reversed? false)
            new-frame (frame/make-frame this)
            current-frame-id (frame/frame-id focus-frame)
            new-frame-id (frame/frame-id new-frame)]
        (mode/fundamental-activate! new-frame)
        (swap! frames-atom assoc new-frame-id new-frame)
        (let [old-leaf (window-layout/make-leaf current-frame-id)
              new-leaf (window-layout/make-leaf new-frame-id)
              split-node (if reversed?
                           (window-layout/make-split direction new-leaf old-leaf :ratio ratio)
                           (window-layout/make-split direction old-leaf new-leaf :ratio ratio))]
          (swap! layout-atom window-layout/replace-leaf  current-frame-id split-node))
        new-frame)
      (msg/error "focus-frame is null")))

  (delete-frame! [this frame-id]
    (let [frame (win/get-frame this frame-id)]
      (mode/deactivate-major-mode! (frame/major-mode frame) frame)
      (ui/destroy-frame! frame)
      (let [new-layout (window-layout/remove-leaf @layout-atom frame-id)]
        (if (window-layout/empty-layout? new-layout)
          (win/close-window! this)                     ;; 无 Frame 剩余，关闭窗口
          (do
            (reset! layout-atom new-layout)
            (frame/destroy-frame! frame-id)
            (when-let [next-id (first (window-layout/all-frames new-layout))]
              (win/set-current-frame! this (get @frames-atom next-id))))))))


  ;; 窗口元数据全部委托给 native
  (window-title [_] (when native (win/native-title native)))
  (set-window-title! [_ title] (when native (win/native-set-title! native title)))
  (window-bounds [_] (when native (win/native-bounds native)))
  (set-window-bounds! [_ bounds] (when native (win/native-set-bounds! native bounds)))
  (window-visible? [_] (when native (win/native-visible? native)))
  (show-window! [_] (when native (win/native-show! native)))
  (hide-window! [_] (when native (win/native-hide! native)))
  (close-window! [this]
    (when native (win/native-close! native))
    (win/unregister-window! this)
    nil)
  (native-window [_] native)
  (layout-desc [_] @layout-atom) )

;; ── 创建窗口 ────────────────────────────────────
(defn create-window!
  [native
   & {:keys [id]
      :or   {id (keyword (str "window-" (gensym)))}}]
  (let [;; 创建窗口对象，并立即赋予三个原子（初始为空）
        win (->Window id (atom {}) (atom []) (atom nil) native)
        ;; 在窗口对象完全就绪后，创建初始 Frame
        initial-frame (frame/make-frame win)
        fid (frame/frame-id initial-frame)]
    ;; 将初始 Frame 注册到窗口的集合和布局树中
    (swap! (:frames-atom win) assoc fid initial-frame)
    (reset! (:layout-atom win) (window-layout/make-leaf fid))
    (reset! (:current-frame-id-atom win) fid)
    (win/register-window! win)
    (mode/fundamental-activate! initial-frame)
    win))


(defn split-frame-left! [window & {:as opts}]
  (win/split-frame! window :horizontal (assoc opts :reversed? true)))

(defn split-frame-right! [window & {:as opts}]
  (win/split-frame! window :horizontal (assoc opts :reversed? true)))

(defn split-frame-up! [window & {:as opts}]
  (win/split-frame! window :vertical (assoc opts :reversed? true)))

(defn split-frame-down! [window & {:as opts}]
  (win/split-frame! window :vertical (assoc opts :reversed? true)))