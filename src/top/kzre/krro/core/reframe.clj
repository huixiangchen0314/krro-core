(ns top.kzre.krro.core.reframe
  "多实例事件流框架，完全对齐 re-frame API 风格。
   事件处理器按 app-id 隔离；
   副作用及订阅按 app-id 隔离；
   每个 record 拥有独立的 store 与反应式追踪。

   底层响应式原语使用 top.kzre.krro.core.util.signal。"
  (:require [top.kzre.krro.core.reframe.reframe :as rf]))


(def ^:deprecated path rf/path)
(def ^:deprecated inject-cofx rf/inject-cofx)
(def ^:deprecated reg-event-record rf/reg-event-record)
(def ^:deprecated reg-event-fx rf/reg-event-fx)
(def ^:deprecated reg-event-ctx rf/reg-event-ctx)
(def ^:deprecated reg-event-co rf/reg-event-co)
(def ^:deprecated reg-fx rf/reg-fx)
(def ^:deprecated reg-sub rf/reg-sub)
(def ^:deprecated reg-store rf/reg-store) ; 移除 store
(def ^:deprecated subscribe rf/subscribe)
(def ^:deprecated on-record-change rf/on-record-change)
(def ^:deprecated dispatch rf/dispatch)