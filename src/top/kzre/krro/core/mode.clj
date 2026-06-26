(ns top.kzre.krro.core.mode
  "模式系统，参照 Emacs 的 major/minor-mode。
   模式是数据，激活时接管键图、布局、局部变量与 Hook。"
  (:require [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.project :as proj]))

(defonce ^:private mode-registry (atom {}))

;; ── 模式规格创建 ─────────────────────────────────
(defn make-major-mode
  [id name & {:keys [parent keymap layout variables hooks after-hook]}]
  (merge {:mode/id id
          :mode/name name
          :mode/parent (or parent :krro.mode/fundamental)}
         (when keymap {:mode/keymap keymap})
         (when layout {:mode/layout layout})
         (when variables {:mode/variables variables})
         (when hooks {:mode/hooks hooks})
         (when after-hook {:mode/after-hook after-hook})))

(defn make-minor-mode
  [id name & {:keys [keymap variables hooks after-hook]}]
  (merge {:mode/id id :mode/name name :mode/minor? true}
         (when keymap {:mode/keymap keymap})
         (when variables {:mode/variables variables})
         (when hooks {:mode/hooks hooks})
         (when after-hook {:mode/after-hook after-hook})))

(defn register-mode! [mode-spec]
  (swap! mode-registry assoc (:mode/id mode-spec) mode-spec))

(defn get-mode-spec [mode-id]
  (get @mode-registry mode-id))

;; ── 模式继承辅助 ─────────────────────────────────
(defn- resolve-variables
  "沿 parent 链合并变量声明。"
  [mode-id]
  (loop [id mode-id, vars {}]
    (if-let [spec (get-mode-spec id)]
      (let [parent (or (:mode/parent spec) (when (:mode/minor? spec) nil))]
        (recur parent (merge (:mode/variables spec) vars)))
      vars)))


(defn deactivate-mode! [project-atom spec]
  ;; 移除键图
  (km/pop-keymap!)
  ;; 恢复局部变量
  (doseq [[var _] (:mode/variables spec)]
    (custom/pop-local-value! (var-get (resolve var))))
  ;; 运行退出 Hook
  (when-let [on-exit (get-in spec [:mode/hooks :on-exit])]
    (hook/run-hooks on-exit @project-atom)))

;; ── 激活与停用 ──────────────────────────────────────
(defn activate-major-mode! [project-atom mode-id]
  (let [old-major (get-in @project-atom [:krro/modes :major])
        spec (get-mode-spec mode-id)]
    ;; 停用旧 major mode
    (when (and old-major (not= old-major mode-id))
      (when-let [old-spec (get-mode-spec old-major)]
        (deactivate-mode! project-atom old-spec)))
    ;; 设置局部变量
    (doseq [[var {:keys [default]}] (resolve-variables mode-id)]
      (custom/push-local-value! (var-get (resolve var)) default))
    ;; 更新项目活动模式
    (swap! project-atom assoc-in [:krro/modes :major] mode-id)
    ;; 激活键图
    (km/push-keymap! (:mode/keymap spec))
    ;; 渲染布局（TODO: 调用 UI 渲染器）
    ;; (ui/render-layout! (:mode/layout spec))
    ;; 运行 Hook
    (when-let [on-enter (get-in spec [:mode/hooks :on-enter])]
      (hook/run-hooks on-enter @project-atom))
    (when-let [after-hook (:mode/after-hook spec)]
      (hook/run-hooks after-hook @project-atom))))


(defn toggle-minor-mode! [project-atom mode-id]
  (let [active-minors (get-in @project-atom [:krro/modes :minors])
        enabled? (contains? active-minors mode-id)
        spec (get-mode-spec mode-id)]
    (if enabled?
      (do
        (deactivate-mode! project-atom spec)
        (swap! project-atom update-in [:krro/modes :minors] disj mode-id))
      (do
        (doseq [[var {:keys [default]}] (:mode/variables spec)]
          (custom/push-local-value! (var-get (resolve var)) default))
        (km/push-keymap! (:mode/keymap spec))
        ;; 渲染等...
        (swap! project-atom update-in [:krro/modes :minors] conj mode-id)
        (when-let [on-enter (get-in spec [:mode/hooks :on-enter])]
          (hook/run-hooks on-enter @project-atom))
        (when-let [after-hook (:mode/after-hook spec)]
          (hook/run-hooks after-hook @project-atom))))))

;; ── 便捷宏 ──────────────────────────────────────
(defmacro define-major-mode
  "定义一个主模式并注册。"
  [name docstring & {:as opts}]
  (let [mode-id (keyword (str *ns*) (str name))
        activate-fn (symbol (str name "-activate"))]
    `(do
       (register-mode! (make-major-mode ~mode-id ~docstring ~@(flatten (seq opts))))
       (defn ~activate-fn [project-atom#] (activate-major-mode! project-atom# ~mode-id))
       (def ~name ~mode-id))))

(defmacro define-minor-mode
  "定义一个副模式并注册。"
  [name docstring & {:as opts}]
  (let [mode-id (keyword (str *ns*) (str name))
        toggle-fn (symbol (str name "-toggle"))]
    `(do
       (register-mode! (make-minor-mode ~mode-id ~docstring ~@(flatten (seq opts))))
       (defn ~toggle-fn [project-atom#] (toggle-minor-mode! project-atom# ~mode-id))
       (def ~name ~mode-id))))