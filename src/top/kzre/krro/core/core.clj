(ns top.kzre.krro.core.core
  "Krrō 核心入口。初始化项目，创建默认 Frame，绑定动态变量。"
  (:require
   [top.kzre.krro.core.command :as cmd]
   [top.kzre.krro.core.commands]
   [top.kzre.krro.core.custom]
   [top.kzre.krro.core.frame :as frame]
   [top.kzre.krro.core.hook]
   [top.kzre.krro.core.keymap]
   [top.kzre.krro.core.message]
   [top.kzre.krro.core.mode :as mode]
   [top.kzre.krro.core.plugin]
   [top.kzre.krro.core.plugins]
   [top.kzre.krro.core.project :as proj]
   [top.kzre.krro.core.rdb :as rdb]
   [top.kzre.krro.core.resource]
   [top.kzre.krro.core.resources]
   [top.kzre.krro.core.ui.protocol]
   [top.kzre.krro.core.util.naming :as naming]))

(defonce ^:private initialized? (atom false))

(defn init!
  "初始化 Krrō 核心系统。创建默认 Frame 并设置为当前活动 Frame。
   此函数可多次调用但只会执行一次。"
  []
  (when (compare-and-set! initialized? false true)
    (proj/init-project!)
    (let [f (frame/create-frame :id :default)]
      (alter-var-root #'frame/*current-frame* (constantly f))
      (mode/fundamental-activate! f)
      (println "Krrō core initialized."))))

;; ── 便捷启动宏 ──────────────────────────────────────
(defmacro with-core [& body]
  `(do
     (init!)
     ~@body))



;; ═══════════════════════════════════════════════════════
;; 模式定义宏：自动注册模式与命令
;; ═══════════════════════════════════════════════════════

(defmacro defmajor
  [mode-id docstring & {:as opts}]
  (let [activate-cmd   (naming/naming-keyword-around mode-id "activate-" "-mode!")
        deactivate-cmd (naming/naming-keyword-around mode-id "deactivate-" "deactivate-mode!")]
    `(do
       (mode/register-mode! (mode/make-major-mode ~mode-id ~docstring ~@(flatten (seq opts))))
       (cmd/register-command! ~activate-cmd
                              (fn [project#] (mode/activate-major-mode! ~mode-id) project#)
                              :description (str "Activate " ~docstring " mode"))
       (cmd/register-command! ~deactivate-cmd
                              (fn [project#] (mode/deactivate-mode! (mode/get-mode-spec ~mode-id)) project#)
                              :description (str "Deactivate " ~docstring " mode")))))

(defmacro defminor
  "定义副模式并注册 activate / deactivate / toggle 命令。"
  [mode-id docstring & {:as opts}]
  (let [activate-cmd   (naming/naming-keyword-around mode-id "activate-" "-mode!")
        deactivate-cmd (naming/naming-keyword-around mode-id "deactivate-" "-mode!")
        toggle-cmd     (naming/naming-keyword-around mode-id "toggle-" "-mode!")]
    `(do
       (mode/register-mode! (mode/make-minor-mode ~mode-id ~docstring ~@(flatten (seq opts))))
       (cmd/register-command! ~activate-cmd
                              (fn [project#] (mode/activate-minor-mode! ~mode-id) project#)
                              :description (str "Activate " ~docstring " minor mode"))
       (cmd/register-command! ~deactivate-cmd
                              (fn [project#] (mode/deactivate-minor-mode! ~mode-id) project#)
                              :description (str "Deactivate " ~docstring " minor mode"))
       (cmd/register-command! ~toggle-cmd
                              (fn [project#] (mode/toggle-minor-mode! ~mode-id) project#)
                              :description (str "Toggle " ~docstring " minor mode")))))


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