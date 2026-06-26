(ns top.kzre.krro.core.ui.spec
  "Krrō UI 描述语言的规格定义。所有界面均使用纯 EDN 数据描述，
   与具体 UI 实现（JavaFX、Web 等）完全解耦。"
  (:require [clojure.spec.alpha :as s]))

;; ═══════════════════════════════════════════════════════════
;; 基础元素：Hiccup 风格的向量 [tag attributes? children*]
;; ═══════════════════════════════════════════════════════════
(s/def ::tag keyword?)
(s/def ::attrs (s/nilable map?))
(s/def ::children (s/coll-of ::ui-element :kind vector?))
(s/def ::ui-element
  (s/or :string string?
        :vector (s/and vector? (s/cat :tag ::tag :attrs (s/? ::attrs) :children (s/* ::ui-element)))))

;; ═══════════════════════════════════════════════════════════
;; 通用属性（可在任何元素上使用）
;; ═══════════════════════════════════════════════════════════
(s/def ::id keyword?)                                   ; 元素唯一标识
(s/def ::class (s/coll-of keyword? :kind set?))        ; CSS 类（语义样式）
(s/def ::style map?)                                    ; 内联样式（平台相关）
(s/def ::visible? boolean?)                             ; 可见性
(s/def ::disabled? boolean?)                            ; 禁用状态

;; 数据绑定：从项目状态中提取值（只读），或者监听 atom 更新
(s/def ::bind (s/keys :req-un [::path] :opt-un [::transform]))
(s/def ::path (s/coll-of keyword? :kind vector?))       ; 项目 atom 中的路径，如 [:scenes 0 :objects 0 :name]
(s/def ::transform (s/nilable symbol?))                 ; 对提取值进行转换的函数名

;; 命令触发：将用户交互映射为全局命令
(s/def ::on-command keyword?)                            ; 触发命令的 id
(s/def ::command-args (s/coll-of any? :kind vector?))   ; 传递给命令的额外参数

;; 布局约束
(s/def ::h-grow ::priority)                             ; 水平增长优先级
(s/def ::v-grow ::priority)                             ; 垂直增长优先级
(s/def ::margin number?)
(s/def ::padding number?)
(s/def ::priority #{:never :always :auto})

;; ═══════════════════════════════════════════════════════════
;; 核心 UI 标签（布局容器与基础控件）
;; ═══════════════════════════════════════════════════════════

;; --- 布局容器 ---
(def layout-tags
  #{:v-box          ; 垂直排列子元素
    :h-box          ; 水平排列子元素
    :split-pane     ; 可调整分隔的容器，子元素按顺序分配空间
    :stack          ; 层叠容器，子元素按顺序叠加
    :grid           ; 网格布局，需要额外的 :columns 属性
    :scroll-pane    ; 滚动容器
    :tab-panel      ; 选项卡面板
    :tool-bar       ; 工具栏容器
    :menu-bar})     ; 菜单栏容器

;; --- 基础控件 ---
(def control-tags
  #{:button
    :label
    :text-field
    :text-area
    :slider
    :checkbox
    :combo-box
    :color-picker
    :separator})

;; --- 特殊视图 ---
(def view-tags
  #{:viewport        ; 通用视口（2D 或 3D），由插件提供具体实现
    :node-editor     ; 节点图编辑器
    :timeline        ; 时间轴
    :properties      ; 属性面板（根据选中对象动态生成）
    :outliner})      ; 大纲视图

;; 所有已知标签（仅用于验证，实际允许任意自定义标签）
(s/def ::tag (into layout-tags (into control-tags view-tags)))

;; ═══════════════════════════════════════════════════════════
;; 扩展机制说明
;; ═══════════════════════════════════════════════════════════
;; 插件可以通过实现 `UIElementRenderer` 协议来注册新的标签。
;; 新标签可以携带任意属性，渲染器负责解释并生成最终 UI。
;; 因此，::tag 的 spec 是开放的，不限于上述集合。
;; 我们使用 (s/def ::tag keyword?) 允许任意关键字。
;; 上面定义的标签集合仅作为文档和默认支持列表。