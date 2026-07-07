(ns top.kzre.krro.core.resources
  (:require [top.kzre.krro.core.resource :as res]))

;; float-array
(res/register-codec! :float-array
                     (fn [fa] {:krro/type :float-array :data (vec fa)})
                     (fn [m] (float-array (:data m))))

;; int-array
(res/register-codec! :int-array
                     (fn [ia] {:krro/type :int-array :data (vec ia)})
                     (fn [m] (int-array (:data m))))

;; double-array
(res/register-codec! :double-array
                     (fn [da] {:krro/type :double-array :data (vec da)})
                     (fn [m] (double-array (:data m))))

;; long-array
(res/register-codec! :long-array
                     (fn [la] {:krro/type :long-array :data (vec la)})
                     (fn [m] (long-array (:data m))))

;; short-array
(res/register-codec! :short-array
                     (fn [sa] {:krro/type :short-array :data (vec sa)})
                     (fn [m] (short-array (:data m))))

;; byte-array
(res/register-codec! :byte-array
                     (fn [ba] {:krro/type :byte-array :data (vec ba)})
                     (fn [m] (byte-array (:data m))))

;; boolean-array
(res/register-codec! :boolean-array
                     (fn [ba] {:krro/type :boolean-array :data (vec ba)})
                     (fn [m] (boolean-array (:data m))))

;; char-array
(res/register-codec! :char-array
                     (fn [ca] {:krro/type :char-array :data (vec ca)})
                     (fn [m] (char-array (:data m))))