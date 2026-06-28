(ns top.kzre.krro.core.mode
  "模式系统，参照 Emacs 的 major/minor-mode。
   模式是数据，激活时接管键图、布局、局部变量与 Hook。
   Frame 持有运行时状态，通过 IFrame 协议操作。
   错误通过消息系统输出。"
  (:require [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.frame :as frame]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.message :as msg]
            [top.kzre.krro.core.ui.protocol :as ui]))

(defonce ^:private mode-registry (atom {}))

(defn make-major-mode
  [id name & {:keys [parent keymap layout variables hooks after-hook]
              :or {parent :krro.mode/fundamental}}]
  (merge {:mode/id id, :mode/name name, :mode/parent parent}
         (when keymap {:mode/keymap keymap})
         (when layout {:mode/layout layout})
         (when variables {:mode/variables variables})
         (when hooks {:mode/hooks hooks})
         (when after-hook {:mode/after-hook after-hook})))

(defn make-minor-mode
  [id name & {:keys [keymap variables hooks after-hook]}]
  (merge {:mode/id id, :mode/name name, :mode/minor? true}
         (when keymap {:mode/keymap keymap})
         (when variables {:mode/variables variables})
         (when hooks {:mode/hooks hooks})
         (when after-hook {:mode/after-hook after-hook})))

(defn register-mode! [spec] (swap! mode-registry assoc (:mode/id spec) spec))
(defn get-mode-spec [id] (get @mode-registry id))

(defn- resolve-variables [mode-id]
  (loop [id mode-id, vars {}]
    (if-let [spec (get-mode-spec id)]
      (let [parent (when-not (:mode/minor? spec) (:mode/parent spec))]
        (recur parent (merge (:mode/variables spec) vars)))
      vars)))

(defn- parent-chain
  "返回 mode-id 的所有祖先模式 ID（从直接父模式开始，直到最顶层根模式 fundamental，包含 fundamental）。"
  [mode-id]
  (take-while some?
              (iterate (fn [id]
                         (when-let [spec (get-mode-spec id)]
                           (:mode/parent spec)))
                       (when-let [spec (get-mode-spec mode-id)]
                         (:mode/parent spec)))))

(defn- resolve-keymap [mode-id]
  (letfn [(build [id]
            (if-let [spec (get-mode-spec id)]
              (let [own-km   (:mode/keymap spec)
                    parent-km (when-not (:mode/minor? spec)
                                (when-let [p (:mode/parent spec)]
                                  (build p)))]
                (if parent-km
                  (if own-km (km/make-keymap (:keys own-km) parent-km) parent-km)
                  own-km))
              nil))]
    (build mode-id)))

(defn- deactivate-mode-internal [spec all-vars f]
  (frame/pop-keymap f)
  (doseq [[var-atom _] all-vars]
    (custom/pop-local-value! var-atom))
  (when-let [on-exit (get-in spec [:mode/hooks :on-exit])]
    (hook/run-hooks on-exit)))

(defn activate-major-mode!
  ([mode-id] (activate-major-mode! mode-id frame/*current-frame*))
  ([mode-id f]
   (if-let [spec (get-mode-spec mode-id)]
     (let [old-major (frame/major-mode f)
           all-vars (resolve-variables mode-id)]
       (when (and old-major (not= old-major mode-id))
         (when-let [old-spec (get-mode-spec old-major)]
           ;; 弹出旧模式及其所有父键图
           (doseq [_ (cons old-major (parent-chain old-major))]
             (frame/pop-keymap f))
           (doseq [[var-atom _] (resolve-variables old-major)]
             (custom/pop-local-value! var-atom))
           (when-let [on-exit (get-in old-spec [:mode/hooks :on-exit])]
             (hook/run-hooks on-exit))))
       (doseq [[var-atom {:keys [default]}] all-vars]
         (custom/push-local-value! var-atom default))
       (frame/set-major-mode! f mode-id)
       ;; 从父到子依次推入键图（父先入，子后入，子在最顶）
       (doseq [p-id (reverse (parent-chain mode-id))]
         (when-let [p-spec (get-mode-spec p-id)]
           (when-let [km (:mode/keymap p-spec)]
             (frame/push-keymap f km))))
       (when-let [km (resolve-keymap mode-id)]
         (frame/push-keymap f km))
       (when-let [layout (:mode/layout spec)]
         (ui/render-layout! layout))
       (when-let [on-enter (get-in spec [:mode/hooks :on-enter])]
         (hook/run-hooks on-enter))
       (when-let [after-hook (:mode/after-hook spec)]
         (hook/run-hooks after-hook))
       mode-id)
     (msg/error (str "Mode not registered: " mode-id)))))

(defn fundamental-activate!
  ([] (fundamental-activate! frame/*current-frame*))
  ([f] (activate-major-mode! :krro.mode/fundamental f)))

(defn deactivate-mode!
  "停用指定模式并回退到 fundamental。会触发模式的 on-exit hook。"
  ([spec] (deactivate-mode! spec frame/*current-frame*))
  ([spec f]
   (let [mode-id (:mode/id spec)]
     (deactivate-mode-internal spec (resolve-variables mode-id) f)
     ;; 弹出该模式及其父链的所有键图
     (doseq [_ (cons mode-id (parent-chain mode-id))]
       (frame/pop-keymap f))
     ;; 恢复变量
     (doseq [[var-atom _] (resolve-variables mode-id)]
       (custom/pop-local-value! var-atom))
     (fundamental-activate! f))))

(defn toggle-minor-mode!
  ([mode-id] (toggle-minor-mode! mode-id frame/*current-frame*))
  ([mode-id f]
   (if-let [spec (get-mode-spec mode-id)]
     (let [active-minors (frame/minor-modes f)
           enabled? (contains? active-minors mode-id)
           all-vars (resolve-variables mode-id)]
       (if enabled?
         (do
           (deactivate-mode-internal spec all-vars f)
           (frame/remove-minor-mode! f mode-id))
         (do
           (doseq [[var-atom {:keys [default]}] all-vars]
             (custom/push-local-value! var-atom default))
           (frame/push-keymap f (:mode/keymap spec))
           (frame/add-minor-mode! f mode-id)
           (when-let [on-enter (get-in spec [:mode/hooks :on-enter])]
             (hook/run-hooks on-enter))
           (when-let [after-hook (:mode/after-hook spec)]
             (hook/run-hooks after-hook))))
       mode-id)
     (msg/error (str "Minor mode not registered: " mode-id)))))

(defn make-major-mode-spec
  "创建并注册主模式，返回包含激活函数和 hook 的 map。"
  [id docstring & {:as opts}]
  (let [hook (hook/make-hook)
        spec (merge {:mode/id id, :mode/name docstring}
                    (dissoc opts :after-hook)
                    {:after-hook hook})
        activate-fn (fn [] (activate-major-mode! id))]
    (register-mode! spec)
    {:id id :spec spec :activate-fn activate-fn :hook hook}))

(defn make-minor-mode-spec
  "创建并注册副模式，返回包含切换函数和 hook 的 map。"
  [id docstring & {:as opts}]
  (let [hook (hook/make-hook)
        spec (merge {:mode/id id, :mode/name docstring, :mode/minor? true}
                    (dissoc opts :after-hook)
                    {:after-hook hook})
        toggle-fn (fn [] (toggle-minor-mode! id))]
    (register-mode! spec)
    {:id id :spec spec :toggle-fn toggle-fn :hook hook}))

(defmacro define-major-mode
  "定义主模式并自动创建相关的 Var。语法糖。"
  [name docstring & {:as opts}]
  (let [mode-id (keyword (str *ns*) (str name))
        activate-fn-sym (symbol (str name "-activate"))
        hook-sym (symbol (str name "-hook"))]
    `(let [result# (make-major-mode-spec ~mode-id ~docstring ~@(flatten (seq opts)))]
       (defonce ~hook-sym (:hook result#))
       (def ~activate-fn-sym (:activate-fn result#))
       (def ~name ~mode-id))))

(defmacro define-minor-mode
  "定义副模式并自动创建相关的 Var。语法糖。"
  [name docstring & {:as opts}]
  (let [mode-id (keyword (str *ns*) (str name))
        toggle-fn-sym (symbol (str name "-toggle"))
        hook-sym (symbol (str name "-hook"))]
    `(let [result# (make-minor-mode-spec ~mode-id ~docstring ~@(flatten (seq opts)))]
       (defonce ~hook-sym (:hook result#))
       (def ~toggle-fn-sym (:toggle-fn result#))
       (def ~name ~mode-id))))