(ns top.kzre.krro.core.resources
  (:require [top.kzre.krro.core.resource :as res]))

;; ── 数组类常量（避免重复 Class/forName） ──────────
(defonce float-array-class   (Class/forName "[F"))
(defonce int-array-class     (Class/forName "[I"))
(defonce double-array-class  (Class/forName "[D"))
(defonce long-array-class    (Class/forName "[J"))
(defonce short-array-class   (Class/forName "[S"))
(defonce byte-array-class    (Class/forName "[B"))
(defonce boolean-array-class (Class/forName "[Z"))
(defonce char-array-class    (Class/forName "[C"))

;; ── 注册编解码器，直接使用 Class 作为 pred ──────────
(res/reg-resource :float-array
                     float-array-class   ;; 直接传 Class，内部转为 instance? + 快速索引
                     (fn [fa _ctx] {:krro/type :float-array :data (vec fa)})
                     (fn [m] (float-array (:data m))))

(res/reg-resource :int-array
                     int-array-class
                     (fn [ia _ctx] {:krro/type :int-array :data (vec ia)})
                     (fn [m] (int-array (:data m))))

(res/reg-resource :double-array
                     double-array-class
                     (fn [da _ctx] {:krro/type :double-array :data (vec da)})
                     (fn [m] (double-array (:data m))))

(res/reg-resource :long-array
                     long-array-class
                     (fn [la _ctx] {:krro/type :long-array :data (vec la)})
                     (fn [m] (long-array (:data m))))

(res/reg-resource :short-array
                     short-array-class
                     (fn [sa _ctx] {:krro/type :short-array :data (vec sa)})
                     (fn [m] (short-array (:data m))))

(res/reg-resource :byte-array
                     byte-array-class
                     (fn [ba _ctx] {:krro/type :byte-array :data (vec ba)})
                     (fn [m] (byte-array (:data m))))

(res/reg-resource :boolean-array
                     boolean-array-class
                     (fn [ba _ctx] {:krro/type :boolean-array :data (vec ba)})
                     (fn [m] (boolean-array (:data m))))

(res/reg-resource :char-array
                     char-array-class
                     (fn [ca _ctx] {:krro/type :char-array :data (vec ca)})
                     (fn [m] (char-array (:data m))))