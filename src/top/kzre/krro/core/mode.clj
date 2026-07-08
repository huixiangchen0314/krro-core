(ns top.kzre.krro.core.mode
  "模式系统，参照 Emacs 的 major/minor-mode。
   模式是数据，激活时接管键图、布局、局部变量与 Hook。
   Hook 直接基于 mode-id 生成，例如 :krro.painting/painting 生成 :painting-mode-enter-hook。
   错误通过消息系统输出。"
  (:require [top.kzre.krro.core.custom :as custom]
            [top.kzre.krro.core.frame :as frame]
            [top.kzre.krro.core.hook :as hook]
            [top.kzre.krro.core.keymap :as km]
            [top.kzre.krro.core.message :as msg]
            [top.kzre.krro.core.ui.protocol :as ui]))

;; ── Hook 关键字生成 ──────────────────────────────
(defn- enter-hook-key [mode-id]
  (keyword (str (name mode-id) "-mode-enter-hook")))

(defn- exit-hook-key [mode-id]
  (keyword (str (name mode-id) "-mode-exit-hook")))

;; ── 模式注册表 ──────────────────────────────────
(defonce ^:private mode-registry (atom {}))

(defn make-major-mode
  [id name & {:keys [parent keymap layout variables after-hook]
              :or {parent :krro.mode/fundamental}}]
  ;; 内部属性用普通关键字
  (merge {:id id, :name name, :parent parent}
         (when keymap {:keymap keymap})
         (when layout {:layout layout})
         (when variables {:variables variables})
         (when after-hook {:after-hook after-hook})))

(defn make-minor-mode
  [id name & {:keys [keymap variables after-hook]}]
  (merge {:id id, :name name, :minor? true}
         (when keymap {:keymap keymap})
         (when variables {:variables variables})
         (when after-hook {:after-hook after-hook})))

(defn register-mode! [spec] (swap! mode-registry assoc (:id spec) spec))
(defn get-mode-spec [id] (get @mode-registry id))

;; ── 变量与键图解析 ────────────────────────────
(defn- resolve-variables [mode-id]
  (loop [id mode-id, vars {}]
    (if-let [spec (get-mode-spec id)]
      (let [parent (when-not (:minor? spec) (:parent spec))]
        (recur parent (merge (:variables spec) vars)))
      vars)))

(defn- parent-chain [mode-id]
  (take-while some?
              (iterate (fn [id]
                         (when-let [spec (get-mode-spec id)]
                           (:parent spec)))
                       (when-let [spec (get-mode-spec mode-id)]
                         (:parent spec)))))

(defn- resolve-keymap [mode-id]
  (letfn [(build [id]
            (if-let [spec (get-mode-spec id)]
              (let [own-km   (:keymap spec)
                    parent-km (when-not (:minor? spec)
                                (when-let [p (:parent spec)]
                                  (build p)))]
                (if parent-km
                  (if own-km (km/make-keymap (:keys own-km) parent-km) parent-km)
                  own-km))
              nil))]
    (build mode-id)))

;; ── 内部停用辅助 ──────────────────────────────
(defn- deactivate-mode-internal [spec all-vars f]
  (frame/pop-keymap f)
  (doseq [[var-atom _] all-vars]
    (custom/pop-local-value! var-atom))
  (hook/run-hook (exit-hook-key (:id spec))))

;; ═══════════════════════════════════════════════════════
;; 模式激活与切换
;; ═══════════════════════════════════════════════════════

(defn activate-major-mode!
  ([mode-id] (activate-major-mode! mode-id frame/*current-frame*))
  ([mode-id f]
   (if-let [spec (get-mode-spec mode-id)]
     (let [old-major (frame/major-mode f)
           all-vars (resolve-variables mode-id)]
       (when (and old-major (not= old-major mode-id))
         (when-let [old-spec (get-mode-spec old-major)]
           (doseq [_ (cons old-major (parent-chain old-major))]
             (frame/pop-keymap f))
           (doseq [[var-atom _] (resolve-variables old-major)]
             (custom/pop-local-value! var-atom))
           (hook/run-hook (exit-hook-key old-major))))
       (doseq [[var-atom {:keys [default]}] all-vars]
         (custom/push-local-value! var-atom default))
       (frame/set-major-mode! f mode-id)
       (doseq [p-id (reverse (parent-chain mode-id))]
         (when-let [p-spec (get-mode-spec p-id)]
           (when-let [km (:keymap p-spec)]
             (frame/push-keymap f km))))
       (when-let [km (resolve-keymap mode-id)]
         (frame/push-keymap f km))
       (when-let [layout (:layout spec)]
         (ui/render-layout! layout f))
       (hook/run-hook (enter-hook-key mode-id))
       (when-let [after-hook (:after-hook spec)]
         (hook/run-hook after-hook))
       mode-id)
     (msg/error (str "Mode not registered: " mode-id)))))

(defn fundamental-activate!
  ([] (fundamental-activate! frame/*current-frame*))
  ([f] (activate-major-mode! :krro.mode/fundamental f)))

(defn deactivate-mode!
  ([spec] (deactivate-mode! spec frame/*current-frame*))
  ([spec f]
   (let [mode-id (:id spec)
         all-vars (resolve-variables mode-id)]
     (deactivate-mode-internal spec all-vars f)
     (doseq [_ (cons mode-id (parent-chain mode-id))]
       (frame/pop-keymap f))
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
           (frame/push-keymap f (:keymap spec))
           (frame/add-minor-mode! f mode-id)
           (hook/run-hook (enter-hook-key mode-id))
           (when-let [after-hook (:after-hook spec)]
             (hook/run-hook after-hook))))
       mode-id)
     (msg/error (str "Minor mode not registered: " mode-id)))))