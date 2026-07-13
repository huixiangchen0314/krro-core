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

(res/register-codec! :float-array
                     #(instance? float-array-class % )      ; % 是对象，class 在后
                     (fn [fa] {:krro/type :float-array :data (vec fa)})
                     (fn [m] (float-array (:data m))))

(res/register-codec! :int-array
                     #(instance? int-array-class % )
                     (fn [ia] {:krro/type :int-array :data (vec ia)})
                     (fn [m] (int-array (:data m))))

(res/register-codec! :double-array
                     #(instance? double-array-class % )
                     (fn [da] {:krro/type :double-array :data (vec da)})
                     (fn [m] (double-array (:data m))))

(res/register-codec! :long-array
                     #(instance? long-array-class % )
                     (fn [la] {:krro/type :long-array :data (vec la)})
                     (fn [m] (long-array (:data m))))

(res/register-codec! :short-array
                     #(instance? short-array-class % )
                     (fn [sa] {:krro/type :short-array :data (vec sa)})
                     (fn [m] (short-array (:data m))))

(res/register-codec! :byte-array
                     #(instance? byte-array-class % )
                     (fn [ba] {:krro/type :byte-array :data (vec ba)})
                     (fn [m] (byte-array (:data m))))

(res/register-codec! :boolean-array
                     #(instance? boolean-array-class % )
                     (fn [ba] {:krro/type :boolean-array :data (vec ba)})
                     (fn [m] (boolean-array (:data m))))

(res/register-codec! :char-array
                     #(instance? char-array-class % )
                     (fn [ca] {:krro/type :char-array :data (vec ca)})
                     (fn [m] (char-array (:data m))))