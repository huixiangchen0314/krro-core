(ns top.kzre.krro.core.util.naming
  "统一的命名工具。")

(defn naming-keyword-subfix
  "在 mode-id 的名称后追加后缀，保留原始命名空间。
   例如: (naming-keyword-subfix :krro.painting/painting \"activate-mode!\")
         => :krro.painting/painting-activate-mode!"
  [mode-id suffix]
  (let [ns-part    (namespace mode-id)
        name-part  (name mode-id)
        prefix     (if ns-part (str ns-part "/") "")]
    (keyword (str prefix name-part suffix))))

(defn naming-keyword-prefix
  "在 mode-id 的名称前添加前缀，保留原始命名空间。
   例如: (naming-keyword-prefix :krro.painting/painting \"toggle-\")
         => :krro.painting/toggle-painting"
  [mode-id prefix]
  (let [ns-part    (namespace mode-id)
        name-part  (name mode-id)
        ns-prefix  (if ns-part (str ns-part "/") "")]
    (keyword (str ns-prefix prefix name-part))))

(defn naming-keyword-around
  "在 mode-id 的名称前后同时添加前缀和后缀，保留原始命名空间。
   例如: (naming-keyword-around :krro.painting/painting \"toggle-\" \"-mode!\")
         => :krro.painting/toggle-painting-mode!"
  [mode-id prefix suffix]
  (let [ns-part    (namespace mode-id)
        name-part  (name mode-id)
        ns-prefix  (if ns-part (str ns-part "/") "")]
    (keyword (str ns-prefix prefix name-part suffix))))