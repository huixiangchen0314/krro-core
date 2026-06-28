(ns top.kzre.krro.core.mode
  "模式系统，参照 Emacs 的 major/minor-mode。
   模式是数据，激活时接管键图、布局、局部变量与 Hook。
   直接使用全局项目原子，不再通过参数传递。
   错误通过消息系统输出。"
  (:require [top.kzre.krro.core.project :as proj]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.ui.protocol :as ui]
            [top.kzre.krro.core.message :as msg]))

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

(defn- deactivate-mode-internal [spec all-vars]
  (km/pop-keymap!)
  (doseq [[var-atom _] all-vars]
    (custom/pop-local-value! var-atom))
  (when-let [on-exit (get-in spec [:mode/hooks :on-exit])]
    (hook/run-hooks on-exit)))

(defn activate-major-mode!
  "激活指定 major mode。直接操作全局项目原子。"
  [mode-id]
  (if-let [spec (get-mode-spec mode-id)]
    (let [old-major (get-in @proj/project [:krro/modes :major])
          all-vars  (resolve-variables mode-id)]
      (when (and old-major (not= old-major mode-id))
        (when-let [old-spec (get-mode-spec old-major)]
          (deactivate-mode-internal old-spec (resolve-variables old-major))))
      (doseq [[var-atom {:keys [default]}] all-vars]
        (custom/push-local-value! var-atom default))
      (swap! proj/project assoc-in [:krro/modes :major] mode-id)
      (km/push-keymap! (resolve-keymap mode-id))
      (when-let [layout (:mode/layout spec)]
        (ui/render-layout! layout))
      (when-let [on-enter (get-in spec [:mode/hooks :on-enter])]
        (hook/run-hooks on-enter))
      (when-let [after-hook (:mode/after-hook spec)]
        (hook/run-hooks after-hook))
      mode-id)
    (msg/error (str "Mode not registered: " mode-id))))

;; Fundamental mode
(let [spec (make-major-mode :krro.mode/fundamental "Fundamental"
                            :parent nil
                            :layout [:v-box {:id :fundamental}]
                            :keymap (km/make-keymap {}))]
  (register-mode! spec))

(defn fundamental-activate! []
  (activate-major-mode! :krro.mode/fundamental))

(defn deactivate-mode!
  "停用指定模式，并自动回退到 fundamental 模式。"
  [spec]
  (deactivate-mode-internal spec (resolve-variables (:mode/id spec)))
  (fundamental-activate!))

(defn toggle-minor-mode! [mode-id]
  (if-let [spec (get-mode-spec mode-id)]
    (let [active-minors (get-in @proj/project [:krro/modes :minors])
          enabled? (contains? active-minors mode-id)
          all-vars (resolve-variables mode-id)]
      (if enabled?
        (do
          (deactivate-mode-internal spec all-vars)
          (swap! proj/project update-in [:krro/modes :minors] disj mode-id))
        (do
          (doseq [[var-atom {:keys [default]}] all-vars]
            (custom/push-local-value! var-atom default))
          (km/push-keymap! (:mode/keymap spec))
          (swap! proj/project update-in [:krro/modes :minors] conj mode-id)
          (when-let [on-enter (get-in spec [:mode/hooks :on-enter])]
            (hook/run-hooks on-enter))
          (when-let [after-hook (:mode/after-hook spec)]
            (hook/run-hooks after-hook))))
      mode-id)
    (msg/error (str "Minor mode not registered: " mode-id))))

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