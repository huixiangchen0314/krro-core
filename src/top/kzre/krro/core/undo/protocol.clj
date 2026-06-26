(ns top.kzre.krro.core.undo.protocol
  "UndoTree 风格的撤销/重做系统协议，灵感来自 Emacs 的 undo-tree 库。
   核心思想：编辑历史不是线性的，而是一棵树，每次 undo 后的新编辑会形成分支。")

;; ── 单个历史节点 ──────────────────────────────────────
(defprotocol IUndoNode
  "表示编辑树中的一个节点，保存项目状态的快照。"
  (node-state [this]        "返回该节点存储的项目状态（快照）")
  (parent [this]            "返回父节点，如果没有（根节点）则返回 nil")
  (children [this]          "返回子节点序列，每个子节点代表从本节点后的一次编辑")
  (timestamp [this]         "返回创建时间戳（java.util.Date 或 long）")
  (metadata [this]          "返回附加的元数据 map，例如包含 :command 和 :description"))

;; ── 整个编辑树 ────────────────────────────────────────
(defprotocol IUndoTree
  "管理编辑历史树，支持状态添加、undo/redo 以及分支切换。"
  (root-node [this]         "返回树根节点")
  (current-node [this]      "返回当前节点")
  (add-state! [this project-state metadata]
    "记录一个新的编辑状态。
     将 project-state 作为当前节点的子节点插入，并移动当前指针到新节点。
     返回更新后的 IUndoTree。")
  (undo! [this]
    "回退到父节点，即撤销最近一次操作。
     如果已经在根节点或没有父节点，则无操作。
     返回更新后的 IUndoTree。")
  (redo! [this]
    "前进到最近创建的子节点，即重做上一次被撤销的操作。
     如果没有可前进的子节点，则无操作。
     返回更新后的 IUndoTree。")
  (switch-branch! [this branch-index]
    "在子节点中选择不同的分支。
     branch-index 是 children 序列中的索引。
     返回更新后的 IUndoTree。")
  (history-path [this]
    "返回从根节点到当前节点的路径，顺序为 [root ... current]，用于可视化。"))