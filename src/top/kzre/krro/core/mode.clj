(ns top.kzre.krro.core.mode
  "模式系统，参照 Emacs 的 major/minor-mode。
   模式是数据，激活时接管键图、布局、局部变量与 Hook。
   Hook 直接基于 mode-id 生成，例如 :krro.painting/painting 生成 :painting-mode-enter-hook。
   错误通过消息系统输出。"
  (:require
   [top.kzre.krro.core.custom :as custom]
   [top.kzre.krro.core.frame :as frame]
   [top.kzre.krro.core.hook :as hook]
   [top.kzre.krro.core.keymap :as km]
   [top.kzre.krro.core.message :as msg]
   [top.kzre.krro.core.ui.protocol :as ui]
   [top.kzre.krro.core.util.naming :as naming]))


;; ── Hook 关键字生成 ──────────────────────────────
(defn- enter-hook-key [mode-id]
  (naming/naming-keyword-subfix mode-id "-mode-enter-hook"))

(defn- exit-hook-key [mode-id]
  (naming/naming-keyword-subfix mode-id "-mode-exit-hook"))

;; ── 模式注册表 ──────────────────────────────────
(defonce ^:private mode-registry (atom {}))

(defn make-major-mode
  [id name & {:keys [parent keymap layout variables after-hook]
              :or {parent :krro.core/fundamental}}]
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


(defn keymaps
  "根据 Frame 的当前模式状态动态构建有效的键图列表。
   优先级从高到低：
   1. 所有激活的副模式键图（顺序任意）
   2. 主模式及其父链键图（子优先，即当前主模式最先）
   3. 全局键图
   返回键图向量列表。"
  [f]
  (let [major-id (frame/major-mode f)
        minors   (frame/minor-modes f)
        ;; 收集副模式键图
        minor-kms (keep #(when-let [spec (get-mode-spec %)]
                           (:keymap spec))
                        minors)
        ;; 沿父链向上收集主模式键图，再反转使得子模式在前
        major-chain (take-while some? (iterate (fn [id]
                                                 (when-let [spec (get-mode-spec id)]
                                                   (:parent spec)))
                                               major-id))
        major-kms   (reverse (keep #(when-let [spec (get-mode-spec %)]
                                      (:keymap spec))
                                   (cons major-id major-chain)))]
    (vec (concat minor-kms major-kms [@km/global-keymap]))))




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

;; ── 内部停用辅助 ──────────────────────────────
(defn- deactivate-mode-internal [spec all-vars f]
  ;; 使用新的 custom API：pop-local-value! 接受关键字 ID
  (doseq [id (keys all-vars)]
    (custom/pop-local-value! id))
  (hook/run-hook! (exit-hook-key (:id spec)) f))

;; ═══════════════════════════════════════════════════════
;; 模式激活与切换
;; ═══════════════════════════════════════════════════════

; ── 主模式专用：进入与退出 ──────────────────────
(defn- exit-major-mode! [mode-id f]
  (let [chain (cons mode-id (parent-chain mode-id))]
    (doseq [id (keys (resolve-variables mode-id))]
      (custom/pop-local-value! id))
    (doseq [mid chain]
      (hook/run-hook! (exit-hook-key mid) f))))

(defn- enter-major-mode! [mode-id f all-vars spec]
  (doseq [[id {:keys [default]}] all-vars]
    (custom/push-local-value! id default))
  (when-let [layout (:layout spec)]
    (ui/render-layout! layout f))
  (doseq [mid (reverse (cons mode-id (parent-chain mode-id)))]
    (hook/run-hook! (enter-hook-key mid) f)))

;; ── 副模式专用：进入与退出 ──────────────────────
(defn- exit-minor-mode! [mode-id f]
  (doseq [id (keys (resolve-variables mode-id))]
    (custom/pop-local-value! id))
  (hook/run-hook! (exit-hook-key mode-id) f))

(defn- enter-minor-mode! [mode-id f all-vars spec]
  (doseq [[id {:keys [default]}] all-vars]
    (custom/push-local-value! id default))
  (hook/run-hook! (enter-hook-key mode-id) f))



(defn activate-major-mode!
  ([mode-id] (activate-major-mode! mode-id frame/*current-frame*))
  ([mode-id f]
   (if-let [spec (get-mode-spec mode-id)]
     (let [old-major (frame/major-mode f)]
       (when (and old-major (not= old-major mode-id))
         (exit-major-mode! old-major f))
       (frame/set-major-mode! f mode-id)
       (let [all-vars (resolve-variables mode-id)]
         (enter-major-mode! mode-id f all-vars spec))
       mode-id)
     (msg/error (str "Mode not registered: " mode-id)))))


(defn fundamental-activate!
  ([] (fundamental-activate! frame/*current-frame*))
  ([f] (activate-major-mode! :krro.core/fundamental f)))

(defn deactivate-mode!
  ([spec] (deactivate-mode! spec frame/*current-frame*))
  ([spec f]
   (let [mode-id (:id spec)]
     (exit-major-mode! mode-id f)
     (fundamental-activate! f))))

(defn activate-minor-mode!
  ([mode-id] (activate-minor-mode! mode-id frame/*current-frame*))
  ([mode-id f]
   (if-let [spec (get-mode-spec mode-id)]
     (let [active-minors (frame/minor-modes f)]
       (when-not (contains? active-minors mode-id)
         (let [all-vars (resolve-variables mode-id)]
           (enter-minor-mode! mode-id f all-vars spec)
           (frame/add-minor-mode! f mode-id)
           mode-id)))
     (msg/error (str "Minor mode not registered: " mode-id)))))

(defn deactivate-minor-mode!
  ([mode-id] (deactivate-minor-mode! mode-id frame/*current-frame*))
  ([mode-id f]
   (if-let [spec (get-mode-spec mode-id)]
     (let [active-minors (frame/minor-modes f)]
       (when (contains? active-minors mode-id)
         (exit-minor-mode! mode-id f)
         (frame/remove-minor-mode! f mode-id)
         mode-id))
     (msg/error (str "Minor mode not registered: " mode-id)))))

(defn toggle-minor-mode!
  ([mode-id] (toggle-minor-mode! mode-id frame/*current-frame*))
  ([mode-id f]
   (if (contains? (frame/minor-modes f) mode-id)
     (deactivate-minor-mode! mode-id f)
     (activate-minor-mode! mode-id f))))