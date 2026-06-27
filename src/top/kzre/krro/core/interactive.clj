(ns top.kzre.krro.core.interactive
  "交互抽象。应用层实现此协议以提供参数收集和消息展示。")

(defprotocol IInteractor
  (read-text [this prompt]
    "从用户处读取一行文本，返回字符串。")
  (read-number [this prompt]
    "从用户处读取一个数字，返回数值。")
  (read-choice [this prompt options]
    "让用户从 options（向量）中选择一项，返回选中项。"))

(defonce ^:private current-interactor (atom nil))

(defn set-interactor! [interactor]
  (reset! current-interactor interactor))

(defn interactor []
  @current-interactor)