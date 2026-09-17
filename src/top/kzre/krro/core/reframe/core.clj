(ns top.kzre.krro.core.reframe.core
  (:require
   [top.kzre.krro.core.reframe.reframe :as rf]))


(def path rf/path)
(def inject-cofx rf/inject-cofx)
(def reg-event-record rf/reg-event-record)
(def reg-event-fx rf/reg-event-fx)
(def reg-event-ctx rf/reg-event-ctx)
(def reg-event-co rf/reg-event-co)
(def reg-fx rf/reg-fx)
(def reg-sub rf/reg-sub)
(def reg-store rf/reg-store) ; 移除 store
(def subscribe rf/subscribe)
(def on-record-change rf/on-record-change)
(def dispatch rf/dispatch)