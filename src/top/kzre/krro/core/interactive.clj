(ns top.kzre.krro.core.interactive
  "交互抽象。应用层实现此协议以提供参数收集和消息展示。")

(defprotocol IInteractor
  (read-args [this spec]
    "根据交互规格 spec 收集用户输入，返回参数向量。
     spec 是一个向量，每个元素可以是：
       - 关键字（:string, :number, :keyword, :choice, :completing-read 等），使用默认提示
       - 向量 [keyword prompt & opts]，如 [:string \"Enter name:\"]
       - 对于 :choice，opts 可为 (fn [] -> options) 或直接选项集合
       - 对于 :completing-read，opts 可包含 :backend 实例
     实现应负责解析所有类型并依次获取用户输入。"))


(defprotocol ICompletionBackend
  (candidates [this spec input]
    "根据交互规格 spec 和用户当前输入 input（字符串），返回候选集合。
     spec 是命令的 :interactive 规范中对应元素的完整向量，例如：
       [:completing-read :prompt \"Layer:\" :backend layer-backend :canvas-id current-canvas]
     后端可从中提取所需上下文（如 :canvas-id）来生成动态候选。
     返回值类型：
       - 集合或惰性序列
       - core.async channel（异步数据源）
       - 无参函数（延迟计算）")

  (annotate [this candidate]
    "可选：为候选提供展示注解，返回 map，可包含：
     :display - 展示字符串
     :doc     - 文档说明
     :icon    - 图标")

  (action [this candidate]
    "可选：当用户选择候选后执行的动作。
     默认返回候选本身，可供命令使用。"))

(defonce ^:private global-completion-backend (atom nil))

(defonce ^:private current-interactor (atom nil))

(defn set-interactor! [interactor]
  (reset! current-interactor interactor))

(defn interactor []
  @current-interactor)

(defn set-completion-backend! [backend]
  (reset! global-completion-backend backend))

(defn completion-backend []
  @global-completion-backend)