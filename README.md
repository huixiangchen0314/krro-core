# krro-core

Krrō 的微内核，灵感来自 Emacs。提供可扩展的交互式应用核心，不包含任何领域逻辑。

## 设计哲学

- **数据即接口**：所有状态为纯 EDN map，存储在单一的 `atom` 中。
- **命令驱动**：一切用户操作皆为命令 `(fn [project] → project)`。
- **模式上下文**：major/minor 模式管理键绑定、UI 布局和局部变量。
- **绝对最小**：内核不预定义任何 UI 标签、不包含 undo/vcs 等具体功能。

## 模块概览

| 模块 | 文件 | 职责 |
|------|------|------|
| 项目容器 | `project.clj` | 全局不可变 EDN 容器，初始化和路径访问 |
| 命令系统 | `command.clj` | 全局命令注册表，`M-x` 风格执行 |
| 键图 | `keymap.clj` | 键绑定定义、上下文查找、键序列状态机 |
| 钩子 | `hook.clj` | 简单的观察者列表 |
| 自定义变量 | `custom.clj` | `defcustom` 变量、局部值栈、类型验证 |
| 模式 | `mode.clj` | major/minor 模式生命周期、键图/变量继承 |
| UI 协议 | `ui/protocol.clj` | 平台无关的渲染器协议 |
| UI 规格 | `ui/spec.clj` | UI 元素结构的 spec（仅约束语法） |
| 插件 | `plugin.clj` | 多方法分发的插件注册入口 |

## 快速开始

### 初始化项目

```clojure
(require '[top.kzre.krro.core.project :as proj])
(proj/init-project!)   ;; 创建一个空项目