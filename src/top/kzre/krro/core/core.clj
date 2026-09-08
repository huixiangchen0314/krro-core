(ns top.kzre.krro.core.core
  "Krrō 核心入口. krro 核心包括两个部分，应用的核心抽象，已经推荐使用应用模式."
  (:require
   [top.kzre.krro.core.command :as cmd]
   [top.kzre.krro.core.commands]
   [top.kzre.krro.core.custom :as custom]
   [top.kzre.krro.core.frame :as frame]
   [top.kzre.krro.core.hook]
   [top.kzre.krro.core.keymap :as km]
   [top.kzre.krro.core.message]
   [top.kzre.krro.core.mode :as mode]
   [top.kzre.krro.core.plugin :as plugin]
   [top.kzre.krro.core.plugins]
   [top.kzre.krro.core.project :as proj]
   [top.kzre.krro.core.rdb :as rdb]
   [top.kzre.krro.core.reframe]
   [top.kzre.krro.core.resource :as res]
   [top.kzre.krro.core.resources]
   [top.kzre.krro.core.ui.protocol :as ui]
   [top.kzre.krro.core.util.naming :as naming]
   [top.kzre.krro.core.window :as win]
   [top.kzre.krro.core.window-impl :as window-impl]))

(defn rerender!
  "重新渲染当前 Frame 的布局。可从模式中重新获取 layout 并触发 UI 更新。"
  ([] (rerender! (win/active-frame)))
  ([f]
   (when-let [mode-id (frame/major-mode f)]
     (when-let [spec (mode/get-mode-spec mode-id)]
       (when-let [layout (:layout spec)]
         (ui/render-frame! layout f))))))

(def set-renderer! ui/set-renderer!)
(def render-layout! ui/render-frame!)

(defonce  ^:deprecated ^:private initialized? (atom false))

(def create-window! window-impl/create-window!)
(def  active-frame win/active-frame)
(def  active-window win/active-window)
(def  all-windows win/all-windows)
(def all-frames win/all-frames)
(def frames-with-param win/frames-with-param)
(def ensure-param! frame/ensure-param!)
(def split-frame-horizontal! win/split-frame-horizontal!)
;; custom
(def defcustom custom/defcustom)
(def get-custom custom/get-custom)
(def set-custom-global! custom/set-custom-global!)
(def set-custom-local! custom/set-custom-local!)
(def kill-local-custom! custom/kill-local-custom!)
(def global-value custom/global-value)
(def custom-modified? custom/custom-modified?)
(def reset-custom! custom/reset-custom!)
(def all-customs custom/all-customs)
(def custom-group custom/custom-group)
(def exe-command! cmd/exe-command!)
(def reg-command cmd/reg-command)

(def reg-resource res/reg-resource)
;; frame
(def current-frame (win/active-frame))
(def ^:deprecated create-frame! frame/make-frame)

(def get-plugin plugin/get-plugin)
(def reg-plugin plugin/reg-plugin)
(def reg-plugin! plugin/reg-plugin!)
(def unreg-plugin plugin/unreg-plugin)
(def enable-plugin! plugin/enable-plugin!)
(def disable-plugin! plugin/disable-plugin!)
(def all-plugins plugin/all-plugins)
(def all-enabled-plugins plugin/all-enabled-plugins)
(def plugin-enable? plugin/plugin-enabled?)
(def plugin-unmountable? plugin/plugin-unmountable?)

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
(def fundamental-activate! mode/fundamental-activate!)
(def  ^:deprecated make-major-mode mode/make-major-mode)
(def  ^:deprecated make-minor-mode mode/make-minor-mode)
(def ^:deprecated register-mode! mode/register-mode!)
(def reg-mode mode/reg-mode)
(def get-mode-spec mode/get-mode-spec)
(def define-major-mode mode/define-major-mode)
(def define-minor-mode mode/define-minor-mode)

(def set-global-key! km/set-global-key!)

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

;; ═══════════════════════════════════════════════════════
;; 命令注册工具
;; ═══════════════════════════════════════════════════════

(defn- table->command-ids [table-id]
  (let [name-part (name table-id)]
    {:insert         (keyword (str "insert-" name-part "!"))
     :update         (keyword (str "update-" name-part "!"))
     :update-by-id   (keyword (str "update-" name-part "-by-id!"))
     :select         (keyword (str "select-" name-part))
     :select-by-id   (keyword (str "select-" name-part "-by-id"))
     :delete         (keyword (str "delete-" name-part "!"))
     :delete-by-id   (keyword (str "delete-" name-part "-by-id!"))
     :select-path    (keyword (str "select-" name-part "-path"))
     :select-key     (keyword (str "select-" name-part "-key"))}))

(defn register-crud-commands!
  "为指定表注册全套 CRUD 命令。提供两套变异操作：基于谓词的通用版本和基于主键的 -by-id 高效版本。"
  [table-id]
  (let [{:keys [insert update update-by-id select select-by-id delete delete-by-id
                select-path select-key]} (table->command-ids table-id)
        schema (rdb/get-schema table-id)
        pk (:primary-key schema :id)]

    ;; INSERT
    (cmd/register-command! insert
                           (fn [project & rows]
                             (apply insert! table-id rows))
                           :description (str "Insert rows into " table-id))

    ;; SELECT (通用谓词或主键快速访问)
    (cmd/register-command! select
                           (fn [project arg]
                             (select table-id arg))
                           :description (str "Select from " table-id " by predicate or primary key"))

    ;; SELECT-BY-ID (直接路径，O(1))
    (cmd/register-command! select-by-id
                           (fn [project id]
                             (select-by-id table-id id))
                           :description (str "Select row from " table-id " by primary key"))

    ;; SELECT-PATH
    (cmd/register-command! select-path
                           (fn [project arg]
                             (path-select table-id arg))
                           :description (str "Return path(s) to rows in " table-id))

    ;; SELECT-KEY
    (cmd/register-command! select-key
                           (fn [project arg]
                             (key-select table-id arg))
                           :description (str "Return primary key(s) of matching rows in " table-id))

    ;; UPDATE (通用谓词)
    (cmd/register-command! update
                           (fn [project arg1 arg2]
                             (let [pred (if (fn? arg1) arg1 (fn [row] (= (get row pk) arg1)))]
                               (update! table-id pred arg2)))
                           :description (str "Update rows in " table-id " by predicate"))

    ;; UPDATE-BY-ID (主键直接路径)
    (cmd/register-command! update-by-id
                           (fn [project id updater]
                             (update-by-id! table-id id updater))
                           :description (str "Update row in " table-id " by primary key"))

    ;; DELETE (通用谓词)
    (cmd/register-command! delete
                           (fn [project arg]
                             (let [pred (if (fn? arg) arg (fn [row] (= (get row pk) arg)))]
                               (delete! table-id pred)))
                           :description (str "Delete rows from " table-id " by predicate"))

    ;; DELETE-BY-ID (主键直接路径)
    (cmd/register-command! delete-by-id
                           (fn [project id]
                             (delete-by-id! table-id id))
                           :description (str "Delete row from " table-id " by primary key"))))

