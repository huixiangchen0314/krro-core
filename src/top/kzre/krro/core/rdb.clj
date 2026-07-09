(ns top.kzre.krro.core.rdb
  "关系型数据库抽象工具库，基于原子 map 提供软事务一致性。
   所有操作需要用户传入数据库原子（例如 proj/project）。
   支持表定义、约束验证（非空、唯一、外键）、CRUD 操作、
   事务性批量更新以及关联查询（inner join / left join）。
   所有写操作（insert!、update!、delete!）返回受影响的主键。
   外键支持 :column 或 :extractor 动态计算引用值。")

;; ═══════════════════════════════════════════════════════════
;; Schema 定义（全局共享，与特定 db 实例无关）
;; ═══════════════════════════════════════════════════════════
(defonce schemas (atom {}))

(defn get-schema
  "获取指定表的 schema，若无返回 nil。"
  [table-id]
  (get @schemas table-id))

(defn defschema
  "定义一个表的结构和约束。
   参数：
     table-id      - 表名关键字
     :primary-key  - 主键列名，默认 :id
     :unique       - 唯一列或列向量
     :not-null     - 非空列向量
     :foreign-keys - 外键向量，每个元素为 {:column :col          ; 直接列名
                                            :extractor (fn [row] value) ; 或计算函数
                                            :references {:table :other-table
                                                         :column :other-col}
                                            :on-delete  :cascade 或 :restrict (默认 :restrict)
                                            :on-update  :cascade 或 :restrict (默认 :restrict)}
                    :column 和 :extractor 至少提供一个。"
  [table-id & {:keys [primary-key unique not-null foreign-keys]
               :or   {primary-key :id}}]
  ;; 验证外键定义
  (doseq [fk foreign-keys]
    (when-not (or (:column fk) (:extractor fk))
      (throw (ex-info "Foreign key must have :column or :extractor" {:fk fk}))))
  (swap! schemas assoc table-id
         {:primary-key  primary-key
          :unique       (if (coll? unique) (set unique) (set (when unique [unique])))
          :not-null     (set not-null)
          :foreign-keys (vec foreign-keys)}))

;; ═══════════════════════════════════════════════════════════
;; 内部辅助
;; ═══════════════════════════════════════════════════════════
(defn- table-path [table-id] [table-id])
(defn- get-table [db table-id] (get-in db (table-path table-id) {}))
(defn- set-table [db table-id new-table] (assoc-in db (table-path table-id) new-table))

(defn- validate-row [schema row]
  (when schema
    (let [{:keys [primary-key not-null]} schema]
      (when (and (contains? not-null primary-key) (nil? (get row primary-key)))
        (throw (ex-info (str "Primary key " primary-key " cannot be null") {:row row})))
      (doseq [col not-null]
        (when (nil? (get row col))
          (throw (ex-info (str "Column " col " cannot be null") {:row row})))))))

(defn- unique-check [table schema row]
  (let [pk (:primary-key schema)
        pk-val (get row pk)
        unique-cols (:unique schema)]
    (when (seq unique-cols)
      (doseq [col unique-cols]
        (let [val (get row col)]
          (when (some (fn [[k v]] (and (not= k pk-val) (= (get v col) val))) table)
            (throw (ex-info (str "Unique constraint violation on column " col) {:row row}))))))))

(defn- fk-value [row fk]
  (if-let [col (:column fk)]
    (get row col)
    ((:extractor fk) row)))

(defn- check-foreign-keys [db table-id row]
  (when-let [schema (get-schema table-id)]
    (doseq [fk (:foreign-keys schema)]
      (let [ref-table-id (get-in fk [:references :table])
            ref-col      (get-in fk [:references :column])
            fk-val       (fk-value row fk)]
        (when fk-val
          (let [ref-table   (get-table db ref-table-id)
                ref-exists? (some #(= fk-val (get % ref-col)) (vals ref-table))]
            (when-not ref-exists?
              (throw (ex-info (str "Foreign key violation: " (or (:column fk) "extractor") "=" fk-val
                                   " not found in " ref-table-id "." ref-col)
                              {:table table-id :row row :fk fk})))))))))

;; ── 级联删除辅助 ──────────────────────────────


(defn- referencing-foreign-keys
  "返回所有引用 table-id 的外键描述（带所属表）。"
  [table-id]
  (for [[other-table schema] @schemas
        fk (:foreign-keys schema)
        :when (= (get-in fk [:references :table]) table-id)]
    {:table other-table :schema schema :fk fk}))

(defn- cascade-delete
  "递归删除主键及其关联的级联子行。在 db 上进行纯变换。"
  [db table-id pks visited]
  (reduce (fn [db pk]
            (if (contains? visited [table-id pk])
              db
              (let [visited (conj visited [table-id pk])
                    ;; 处理子表级联
                    db (reduce (fn [db' {:keys [table schema fk]}]
                                 (if (= (:on-delete fk :restrict) :cascade)
                                   (let [child-table  (get-table db' table)
                                         child-pk     (:primary-key schema :id)
                                         child-rows   (filter #(= (fk-value % fk) pk) (vals child-table))
                                         child-pks    (mapv #(get % child-pk) child-rows)]
                                     (cascade-delete db' table child-pks visited))
                                   db'))
                               db
                               (referencing-foreign-keys table-id))]
                ;; 最后删除本行
                (let [table (get-table db table-id)]
                  (set-table db table-id (dissoc table pk))))))
          db
          pks))

;; ═══════════════════════════════════════════════════════════
;; 条件构造器 (where 子句)
;; ═══════════════════════════════════════════════════════════
(defmacro where
  "构建查询条件谓词。示例: (where (= :name \"Alice\") (>= :age 18))"
  [& conditions]
  `(fn [row#]
     (and ~@(map (fn [cond]
                   (let [[op col val] cond]
                     `(~op (~col row#) ~val)))
                 conditions))))

;; ═══════════════════════════════════════════════════════════
;; 主键与路径查询
;; ═══════════════════════════════════════════════════════════
(defn key-select
  "返回匹配行的主键。
   db-atom - 数据库原子
   table-id - 表名
   pred     - 谓词函数或单个主键值
   返回：
     - 如果匹配单行，返回该行的主键值
     - 如果匹配多行，返回主键值的向量
     - 无匹配返回 nil"
  [db-atom table-id pred]
  (let [schema (get-schema table-id)
        pk (:primary-key schema :id)
        table (get-table @db-atom table-id)]
    (if (fn? pred)
      (let [matched (filter pred (vals table))]
        (if (<= (count matched) 1)
          (when-let [row (first matched)]
            (get row pk))
          (mapv #(get % pk) matched)))
      ;; 主键值直接查找
      (when (contains? table pred)
        pred))))

(defn path-select
  "返回匹配行的数据库路径，便于直接操作原子。
   db-atom - 数据库原子
   table-id - 表名
   pred     - 谓词函数或单个主键值
   返回：
     - 如果匹配单行，返回 [table-id pk-val] 路径向量
     - 如果匹配多行，返回路径向量的向量 [[table-id pk1] [table-id pk2] ...]
     - 无匹配返回 nil"
  [db-atom table-id pred]
  (let [keys (key-select db-atom table-id pred)]
    (cond
      (nil? keys) nil
      (vector? keys) (mapv #(vector table-id %) keys)
      :else [table-id keys])))

;; ═══════════════════════════════════════════════════════════
;; CRUD 操作 (注入 db-atom)
;; ═══════════════════════════════════════════════════════════
(defn insert!
  [db-atom table-id & rows]
  (let [schema (get-schema table-id)
        pk     (:primary-key schema :id)
        ;; 预先为主键缺失的行生成 UUID 关键词
        rows' (mapv (fn [row]
                      (if (get row pk)
                        row
                        (assoc row pk (keyword (str (java.util.UUID/randomUUID))))))
                    rows)]
    (swap! db-atom
           (fn [db]
             (let [table (get-table db table-id)]
               (reduce (fn [db' row]
                         (let [pk-val (get row pk)]
                           (when (contains? table pk-val)
                             (throw (ex-info (str "Primary key " pk-val " already exists")
                                             {:table table-id :pk pk-val})))
                           (validate-row schema row)
                           (unique-check table schema row)
                           (check-foreign-keys db' table-id row)
                           (set-table db' table-id (assoc table pk-val row))))
                       db rows'))))
    ;; 直接返回已知的主键
    (let [keys (mapv #(get % pk) rows')]
      (if (= (count keys) 1)
        (first keys)
        keys))))

(defn select
  "查询表中符合条件的行。db-atom 为数据库原子，pred 为谓词函数或 nil（返回所有行）。"
  [db-atom table-id pred]
  (let [table (get-table @db-atom table-id)
        rows  (vals table)]
    (if pred
      (filter pred rows)
      rows)))

(defn update!
  "更新表中符合条件的行。pred 为谓词函数，f 为 (fn [row] -> new-row)。
   若新行导致约束违反则失败。返回受影响行的主键（单个或向量）。"
  [db-atom table-id pred f]
  (let [keys (key-select db-atom table-id pred)]
    (swap! db-atom
           (fn [db]
             (let [table (get-table db table-id)
                   schema (get-schema table-id)]
               (set-table db table-id
                          (reduce-kv (fn [m k v]
                                       (if (pred v)
                                         (if-let [new-v (f v)]
                                           (do
                                             (validate-row schema new-v)
                                             (unique-check table schema new-v)
                                             (check-foreign-keys db table-id new-v)
                                             (assoc m k new-v))
                                           m)
                                         (assoc m k v)))
                                     {} table)))))
    keys))

(defn delete!
  "删除表中符合条件的行。返回被删除行的主键（单个或向量）。
   若外键配置了 :on-delete :cascade，将自动递归删除关联子行。"
  [db-atom table-id pred]
  (let [keys (key-select db-atom table-id pred)
        ks   (if (coll? keys) keys (vector keys))]
    (swap! db-atom
           (fn [db]
             (cascade-delete db table-id ks #{})))
    keys))

;; ═══════════════════════════════════════════════════════════
;; 基于主键的高效操作
;; ═══════════════════════════════════════════════════════════
(defn select-by-id [db-atom table-id pk-val]
  (when-let [path (path-select db-atom table-id pk-val)]
    (get-in @db-atom path)))

(defn update-by-id! [db-atom table-id pk-val f]
  (if-let [path (path-select db-atom table-id pk-val)]
    (swap! db-atom update-in path f)
    pk-val))

(defn delete-by-id! [db-atom table-id pk-val]
  (if-let [path (path-select db-atom table-id pk-val)]
    (swap! db-atom update (first path) dissoc (second path))
    pk-val))

;; ═══════════════════════════════════════════════════════════
;; 关联查询 (JOIN)
;; ═══════════════════════════════════════════════════════════
(defn inner-join
  "内连接两个表。db-atom 为数据库原子，table-a 与 table-b 为表名，
   join-on 为 {:local :local-col :foreign :foreign-col}，merge-fn 为合并函数，
   接收左表和右表的行，返回合并后的结果。"
  [db-atom table-a table-b join-on merge-fn]
  (let [db @db-atom
        rows-a (vals (get-table db table-a))
        rows-b (vals (get-table db table-b))]
    (for [a rows-a
          b rows-b
          :when (= (get a (:local join-on)) (get b (:foreign join-on)))]
      (merge-fn a b))))

(defn left-join
  "左外连接两个表。右表无匹配时 merge-fn 的第二个参数为 nil。"
  [db-atom table-a table-b join-on merge-fn]
  (let [db @db-atom
        rows-a (vals (get-table db table-a))
        rows-b (vals (get-table db table-b))]
    (for [a rows-a]
      (if-let [b (some #(and (= (get a (:local join-on)) (get % (:foreign join-on))) %) rows-b)]
        (merge-fn a b)
        (merge-fn a nil)))))

;; ═══════════════════════════════════════════════════════════
;; 事务宏
;; ═══════════════════════════════════════════════════════════
(defmacro transact
  "在 db-atom 上执行多个操作，保证原子性。body 在 swap! 内执行，可返回 nil。
   用法: (rdb/transact my-db-atom
           (rdb/insert! my-db-atom :users {:name \"Alice\"})
           (rdb/update! my-db-atom :users some-pred updater))"
  [db-atom & body]
  `(swap! ~db-atom
          (fn [db#]
            (let [result# (do ~@body)]
              db#))))