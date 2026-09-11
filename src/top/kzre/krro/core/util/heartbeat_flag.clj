(ns top.kzre.krro.core.util.heartbeat-flag
  (:import (top.kzre.krro.core.util HeartbeatFlag)))

(defmacro heartbeat-flag
  "创建一个 HeartbeatFlag。

   不传 timeout 时默认 3 秒。
   语义：从未 beat 或已超时 → false；存在活跃心跳 → true。

   用法：
     (heartbeat-flag)
     (heartbeat-flag (* 5 1e9))"
  ([]
   `(HeartbeatFlag.))
  ([timeout-nanos]
   `(HeartbeatFlag. ~timeout-nanos)))

(defmacro beat!
  [flag key]
  `(.beat ^HeartbeatFlag ~flag ~key))

(defmacro clear!
  [flag key]
  `(.clear ^HeartbeatFlag ~flag ~key))