(ns top.kzre.krro.core.util.heartbeat-flag
  (:import (top.kzre.krro.core.util HeartbeatFlag)))

(defmacro heartbeat-flag
  ([default-value] `(HeartbeatFlag. ~default-value))
  ([default-value timeout] `(HeartbeatFlag. ~default-value ~timeout)))

(defmacro beat![flag key]
  `(.beat ^HeartbeatFlag ~flag ~key))


(defmacro clear! [flag key]
  `(.clear ^HeartbeatFlag ~flag ~key))