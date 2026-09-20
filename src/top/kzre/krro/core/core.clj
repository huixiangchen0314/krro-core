(ns top.kzre.krro.core.core
  "Krrō 核心入口. krro 核心包括两个部分，应用的核心抽象，已经推荐使用应用模式."
  (:require
    [top.kzre.krro.core.command :as cmd]
    [top.kzre.krro.core.commands]
    [top.kzre.krro.core.reframe.core]
    [top.kzre.krro.core.custom ]
    [top.kzre.krro.core.frame :as frame]
    [top.kzre.krro.core.hook]
    [top.kzre.krro.core.util.promise]
    [top.kzre.krro.core.keymap]
    [top.kzre.krro.core.message]
    [top.kzre.krro.core.mode :as mode]
    [top.kzre.krro.core.plugin ]
    [top.kzre.krro.core.plugins]
    [top.kzre.krro.core.project :as proj]
    [top.kzre.krro.core.rdb :as rdb]
    [top.kzre.krro.core.reframe]
    [top.kzre.krro.core.resource]
    [top.kzre.krro.core.resources]
    [top.kzre.krro.core.ui.protocol :as ui]
    [top.kzre.krro.core.util.naming :as naming]
    [top.kzre.krro.core.util.re-export :refer [re-export]]
    [top.kzre.krro.core.window :as win]
    [top.kzre.krro.core.window-impl]
    [top.kzre.krro.core.util]))

(defn rerender!
  "重新渲染当前 Frame 的布局。可从模式中重新获取 layout 并触发 UI 更新。"
  (^:deprecated [] (rerender! (win/active-frame)))
  ([f]
   (when-let [mode-id (frame/major-mode f)]
     (when-let [spec (mode/get-mode-spec mode-id)]
       (when-let [layout (:layout spec)]
         (ui/render-frame! layout f))))))

(re-export
  [top.kzre.krro.core.hook
   :refer
   [add-hook! remove-hook! run-hook!]])


(re-export
  [top.kzre.krro.core.message
   :refer [message warn error]])

(re-export
  [top.kzre.krro.core.window-impl
   :refer [create-window!]])

(re-export
  [top.kzre.krro.core.window
   :refer [active-window active-frame
           all-windows all-frames frames-with-param
           split-frame-horizontal!]])

(re-export
  [top.kzre.krro.core.frame
   :refer [ensure-param!]])


(re-export
  [top.kzre.krro.core.custom
   :refer [defcustom get-custom
           set-custom-global! set-custom-local!
           kill-local-custom! global-value
           custom-modified? reset-custom!
           all-customs custom-group]])

(re-export
  [top.kzre.krro.core.command
   :refer
   [exe-command! reg-command ]])

(re-export
  [top.kzre.krro.core.resource
   :refer [reg-resource]])


(re-export
  [top.kzre.krro.core.plugin
   :refer
   [get-plugin reg-plugin reg-plugin! unreg-plugin
    enable-plugin! disable-plugin! all-plugins
    all-enabled-plugins plugin-enabled?
    plugin-unmountable? ]])

(re-export
  [top.kzre.krro.core.util
   :refer
   [topo-sort merge-deep take-padded]])

(re-export
  [top.kzre.krro.core.mode
   :refer
   [reg-mode get-mode-spec define-major-mode define-minor-mode
    fundamental-activate!]])

(re-export
  [top.kzre.krro.core.keymap
   :refer
   [set-global-key!]])


;; ═══════════════════════════════════════════════════════
;; 全局项目绑定的 RDB 操作
;; ═══════════════════════════════════════════════════════

(defn insert!
  "向表中插入一行或多行数据，表由 table-id 指定。若行缺少主键，将自动生成 UUID 关键词。
   返回插入行的主键（单个或向量）。"
  [table-id & rows]
  (apply rdb/insert! proj/project table-id rows))

(defn select
  "查询表中符合条件的行。pred 为谓词函数或主键值（直接路径获取）。"
  [table-id pred]
  (if (fn? pred)
    (rdb/select proj/project table-id pred)
    (if-let [path (rdb/path-select proj/project table-id pred)]
      (get-in @proj/project path)
      nil)))

(defn update!
  "更新表中符合条件的行。pred 为谓词函数，f 为 (fn [row] -> new-row)。返回受影响行的主键。"
  [table-id pred f]
  (rdb/update! proj/project table-id pred f))

(defn delete!
  "删除表中符合条件的行。pred 为谓词函数。返回被删除行的主键。"
  [table-id pred]
  (rdb/delete! proj/project table-id pred))

(defn path-select
  "返回匹配行的路径向量，便于直接操作原子。pred 可为谓词或主键值。"
  [table-id pred]
  (rdb/path-select proj/project table-id pred))

(defn key-select
  "返回匹配行的主键值。pred 可为谓词或主键值。"
  [table-id pred]
  (rdb/key-select proj/project table-id pred))

(defn select-by-id [table-id pk-val]
  (rdb/select-by-id proj/project table-id pk-val))

(defn update-by-id! [table-id pk-val f]
  (rdb/update-by-id! proj/project table-id pk-val f))

(defn delete-by-id! [table-id pk-val]
  (rdb/delete-by-id! proj/project table-id pk-val))



(def ^:deprecated set-renderer! ui/set-renderer!)
(def ^:deprecated render-layout! ui/render-frame!)

(defonce  ^:deprecated ^:private initialized? (atom false))


(def ^:deprecated current-frame (win/active-frame))
(def ^:deprecated create-frame! frame/make-frame)

;; TODO 直接操作协议
(def ^:deprecated frame-id frame/frame-id)
(def ^:deprecated major-mode frame/major-mode)
(def ^:deprecated minor-modes frame/minor-modes)

;; mode TODO 优先使用 define-xxx-mode 和生成的特定命令
(def  ^:deprecated activate-major-mode! mode/activate-major-mode!)
(def  ^:deprecated activate-minor-mode! mode/activate-minor-mode!)
(def  ^:deprecated deactivate-minor-mode! mode/deactivate-minor-mode!)
(def  ^:deprecated toggle-minor-mode! mode/toggle-minor-mode!)
(def  ^:deprecated deactivate-mode! mode/deactivate-major-mode!)
(def  ^:deprecated make-major-mode mode/make-major-mode)
(def  ^:deprecated make-minor-mode mode/make-minor-mode)
(def ^:deprecated register-mode! mode/register-mode!)

(defn ^:deprecated init!
  "初始化 Krrō 核心系统。创建默认 Frame 并设置为当前活动 Frame。
   此函数可多次调用但只会执行一次。"
  []
  (when (compare-and-set! initialized? false true)
    (proj/init-project!)
    (let [f (frame/make-frame :id :default)]
      (alter-var-root #'frame/*current-frame* (constantly f))
      (mode/fundamental-activate! f)
      (println "Krrō core initialized."))))

;; ── 便捷启动宏 ──────────────────────────────────────
(defmacro  ^:deprecated with-core [& body]
  `(do
     (init!)
     ~@body))





(defn ^:deprecated defmajor
  "注册一个 major mode 并生成激活/停用命令。
   参数：
     - mode-id: mode 的唯一标识符（关键字）
     - name:    mode 的显示名称（字符串）
     - opts:    额外的键值对选项，会原样传递给 mode/make-major-mode"
  [mode-id name & {:as opts}]
  (let [activate-cmd   (naming/naming-keyword-around mode-id "activate-" "-mode!")
        deactivate-cmd (naming/naming-keyword-around mode-id "deactivate-" "deactivate-mode!")]
    (mode/register-mode! (apply mode/make-major-mode mode-id  (flatten (seq (assoc opts :name name)))))
    (cmd/reg-command activate-cmd
                           (fn [project] (mode/activate-major-mode! mode-id) project)
                           :description (str "Activate " name " mode"))
    (cmd/reg-command deactivate-cmd
                           (fn [project] (mode/deactivate-major-mode! mode-id) project)
                           :description (str "Deactivate " name " mode"))))

(defmacro ^:deprecated  defminor
  "定义副模式并注册 activate / deactivate / toggle 命令。"
  [mode-id name & {:as opts}]
  (let [activate-cmd   (naming/naming-keyword-around mode-id "activate-" "-mode!")
        deactivate-cmd (naming/naming-keyword-around mode-id "deactivate-" "-mode!")
        toggle-cmd     (naming/naming-keyword-around mode-id "toggle-" "-mode!")]
    `(do
       (mode/register-mode! (mode/make-minor-mode ~mode-id ~name ~@(flatten (seq (assoc opts :name name)))))
       (cmd/reg-command ~activate-cmd
                              (fn [project#] (mode/activate-minor-mode! ~mode-id) project#)
                              :description (str "Activate " ~name " minor mode"))
       (cmd/reg-command ~deactivate-cmd
                              (fn [project#] (mode/deactivate-minor-mode! ~mode-id) project#)
                              :description (str "Deactivate " ~name " minor mode"))
       (cmd/reg-command ~toggle-cmd
                              (fn [project#] (mode/toggle-minor-mode! ~mode-id) project#)
                              :description (str "Toggle " ~name " minor mode")))))

