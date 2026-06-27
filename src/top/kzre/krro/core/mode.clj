(ns top.kzre.krro.core.mode
  "模式系统，参照 Emacs 的 major/minor-mode。
   模式是数据，激活时接管键图、布局、局部变量与 Hook。
   变量直接以 atom 值引用，无需符号解析。"
  (:require [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.ui.protocol :as ui]))

(defonce ^:private mode-registry (atom {}))

(defn make-major-mode
  [id name & {:keys [parent keymap layout variables hooks after-hook]}]
  (merge {:mode/id id, :mode/name name, :mode/parent (or parent :krro.mode/fundamental)}
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

(defn- resolve-variables
  "沿 parent 链合并变量声明。变量键是 atom 对象。"
  [mode-id]
  (loop [id mode-id, vars {}]
    (if-let [spec (get-mode-spec id)]
      (let [parent (when-not (:mode/minor? spec) (:mode/parent spec))]
        (recur parent (merge (:mode/variables spec) vars)))
      vars)))

(defn- deactivate-mode-internal
  "停用模式核心逻辑，接收已解析的完整变量。"
  [project-atom spec all-vars]
  (km/pop-keymap!)
  (doseq [[var-atom _] all-vars]
    (custom/pop-local-value! var-atom))
  (when-let [on-exit (get-in spec [:mode/hooks :on-exit])]
    (hook/run-hooks on-exit)))

(defn activate-major-mode! [project-atom mode-id]
  (let [old-major (get-in @project-atom [:krro/modes :major])
        spec (get-mode-spec mode-id)
        all-vars (resolve-variables mode-id)]
    (when-not (get-mode-spec mode-id)
      (throw (ex-info "Mode not registered" {:mode-id mode-id})))
    ;; 停用旧主模式
    (when (and old-major (not= old-major mode-id))
      (when-let [old-spec (get-mode-spec old-major)]
        (deactivate-mode-internal project-atom old-spec (resolve-variables old-major))))
    ;; 设置局部变量
    (doseq [[var-atom {:keys [default]}] all-vars]
      (custom/push-local-value! var-atom default))
    (swap! project-atom assoc-in [:krro/modes :major] mode-id)
    (km/push-keymap! (:mode/keymap spec))
    ;; 渲染 UI 布局（若存在）
    (when-let [layout (:mode/layout spec)]
      (ui/render-layout! layout))
    (when-let [on-enter (get-in spec [:mode/hooks :on-enter])]
      (hook/run-hooks on-enter))
    (when-let [after-hook (:mode/after-hook spec)]
      (hook/run-hooks after-hook))))

(defn deactivate-mode!
  "外部停用（传入 spec），会解析 spec 的模式 ID。"
  [project-atom spec]
  (deactivate-mode-internal project-atom spec (resolve-variables (:mode/id spec))))

(defn toggle-minor-mode! [project-atom mode-id]
  (let [active-minors (get-in @project-atom [:krro/modes :minors])
        enabled? (contains? active-minors mode-id)
        spec (get-mode-spec mode-id)
        all-vars (resolve-variables mode-id)]
    (when-not (get-mode-spec mode-id)
      (throw (ex-info "Mode not registered" {:mode-id mode-id})))
    (if enabled?
      (do
        (deactivate-mode-internal project-atom spec all-vars)
        (swap! project-atom update-in [:krro/modes :minors] disj mode-id))
      (do
        (doseq [[var-atom {:keys [default]}] all-vars]
          (custom/push-local-value! var-atom default))
        (km/push-keymap! (:mode/keymap spec))
        (swap! project-atom update-in [:krro/modes :minors] conj mode-id)
        (when-let [on-enter (get-in spec [:mode/hooks :on-enter])]
          (hook/run-hooks on-enter))
        (when-let [after-hook (:mode/after-hook spec)]
          (hook/run-hooks after-hook))))))

(defmacro define-major-mode
  [name docstring & {:as opts}]
  (let [mode-id (keyword (str *ns*) (str name))
        activate-fn (symbol (str name "-activate"))]
    `(do
       (register-mode! (make-major-mode ~mode-id ~docstring ~@(flatten (seq opts))))
       (defn ~activate-fn [project-atom#] (activate-major-mode! project-atom# ~mode-id))
       (def ~name ~mode-id))))

(defmacro define-minor-mode
  [name docstring & {:as opts}]
  (let [mode-id (keyword (str *ns*) (str name))
        toggle-fn (symbol (str name "-toggle"))]
    `(do
       (register-mode! (make-minor-mode ~mode-id ~docstring ~@(flatten (seq opts))))
       (defn ~toggle-fn [project-atom#] (toggle-minor-mode! project-atom# ~mode-id))
       (def ~name ~mode-id))))