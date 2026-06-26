(ns top.kzre.krro.core.vcs.protocol
  "Krrō 版本控制核心协议，与 Git 风格类似但深度集成项目数据语义。
   支持基于资产的细粒度版本管理、语义合并与冲突解决。")

;; ═══════════════════════════════════════════════════════════
;; 基础数据结构
;; ═══════════════════════════════════════════════════════════

;; 提交 ID，使用 UUID 或整数递增
(defprotocol ISnapshot
  "一个不可变的项目状态快照，包含元数据和资产键的引用。"
  (snapshot-id [this]    "返回唯一标识")
  (snapshot-data [this]  "返回完整的项目数据 map")
  (snapshot-meta [this]  "返回元数据 map（作者、时间戳、消息）"))

;; 分支引用
(defprotocol IBranchRef
  "指向一个分支的当前 HEAD 快照。"
  (branch-name [this])
  (branch-head [this] "返回当前 HEAD 的 ISnapshot"))

;; ═══════════════════════════════════════════════════════════
;; 资产解析器（插件实现，提供语义分块能力）
;; ═══════════════════════════════════════════════════════════
(defprotocol IAssetResolver
  "将项目数据分解为有意义的资产块，以及从资产块重建项目数据。
   由各个插件（建模、材质、动画等）实现，以提供领域语义。"
  (resolve-assets [this project-data]
    "返回一个 map，键为资产路径（如 [:meshes \"cube\"]），值为资产数据。
     资产路径是全局唯一的，用于标识独立的可合并单元。")
  (assemble-project [this asset-map]
    "从资产 map 重新构建项目数据。逆操作。"))

;; ═══════════════════════════════════════════════════════════
;; 差异与冲突
;; ═══════════════════════════════════════════════════════════
(defprotocol IDiff
  "表示两个快照之间的差异，以及冲突信息。"
  (diff-assets [this]    "返回变更的资产路径集合")
  (diff-detail [this asset-path]  "返回指定资产的详细变更（before / after）"))

(defprotocol IConflict
  "表示合并冲突。"
  (conflict-asset [this]  "返回冲突的资产路径")
  (conflict-ours [this]   "返回我们的版本")
  (conflict-theirs [this] "返回对方的版本")
  (resolve-with [this resolution] "用指定解决方案解决冲突，返回新快照"))

;; ═══════════════════════════════════════════════════════════
;; 核心版本控制树
;; ═══════════════════════════════════════════════════════════
(defprotocol IVCTree
  "Krrō 的版本控制树，提供 Git 风格的操作，但基于资产语义。"
  (commit! [this message]
    "将当前项目状态保存为一次提交，返回新的 IVCTree 和提交 ID。"
    "消息记录为元数据的一部分。")
  (checkout! [this ref]
    "将项目状态切换到指定的提交 ID 或分支名，返回新的 IVCTree。")
  (create-branch! [this name]
    "在当前 HEAD 处创建一个新分支，返回新的 IVCTree。")
  (merge-branch! [this branch-name]
    "将指定分支合并到当前分支。可能产生冲突。
     返回 {:status :ok/:conflict, :tree new-tree, :conflicts [...]}")
  (create-tag! [this name]
    "为当前 HEAD 创建一个命名标签，返回新的 IVCTree。")
  (log [this]
    "返回当前分支的提交历史，每个元素为 ISnapshot。")
  (current-branch [this]
    "返回当前分支的 IBranchRef。")
  (diff [this snapshot-a snapshot-b]
    "比较两个快照，返回 IDiff 对象，按资产粒度显示差异。"))