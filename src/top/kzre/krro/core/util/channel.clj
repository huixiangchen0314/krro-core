(ns top.kzre.krro.core.util.channel
  (:refer-clojure :exclude [empty?])
  (:import (java.util.function Consumer)
           (top.kzre.krro.core.util BufferedChannel BlockingChannel IChannel)))

;; ==================== 创建 ====================

(defn chan
  "创建一个通道。
   capacity 默认 0；传 0 时创建无缓冲通道（BlockingChannel）。
   on-dispose 为可选的丢弃回调 fn，接收被丢弃的元素。"
  ([]
   (chan 0 nil))
  ([capacity]
   (chan capacity nil))
  ([capacity on-dispose]
   {:pre [(nat-int? capacity)]}
   (let [disposer
         (when on-dispose
           (reify Consumer
             (accept [_ v] (on-dispose v))))]
     (if (zero? capacity)
       (BlockingChannel. disposer)
       (BufferedChannel. capacity disposer)))))

(defn chan?
  "判断是否为 IChannel 实例（包括 Channel 和 BlockingChannel）。"
  [x]
  (instance? IChannel x))

;; ==================== 写入 ====================

(defn >|
  "非阻塞写入。
   有缓冲：满时丢弃最旧元素，返回 true。
   无缓冲：无人接收时直接丢弃，返回 false。
   通道已关闭时丢弃元素并返回 false。"
  [^IChannel ch value]
  (.send ch value))

(defn >!
  "阻塞写入。
   有缓冲：满时阻塞等待空位。
   无缓冲：阻塞等待接收者配对。
   通道已关闭时返回 false。
   可被中断，抛出 InterruptedException。"
  {:throws [InterruptedException]}
  [^IChannel ch value]
  (.offer ch value))

;; ==================== 读取 ====================

(defn <|
  "非阻塞读取。
   通道空时返回 nil。
   无缓冲通道始终返回 nil。"
  [^IChannel ch]
  (let [v (.poll ch)]
    (if (nil? v) nil v)))

(defn <!
  "阻塞读取。
   通道空时等待直到有数据或关闭。
   返回元素，通道关闭且空时返回 nil。"
  {:throws [InterruptedException]}
  [^IChannel ch]
  (.get ch))

;; ==================== 生命周期 ====================

(defn close!
  "关闭通道。
   已入队元素仍可被读取。
   关闭后写入失败，阻塞的读写操作被唤醒。"
  [^IChannel ch]
  (.close ch))

(defn clear!
  "清空通道中所有待消费数据。
   对每个被丢弃的元素调用 on-dispose 回调。
   注意：此方法不关闭通道，清空后仍可正常读写。"
  [^IChannel ch]
  (.clear ch))

;; ==================== 状态 ====================

(defn chan-size
  "返回当前通道中的元素数量。"
  [^IChannel ch]
  (.size ch))

(defn chan-capacity
  "返回通道容量。无缓冲通道返回 0。"
  [^IChannel ch]
  (.capacity ch))

(defn full?
  "判断通道是否已满。"
  [^IChannel ch]
  (.isFull ch))

(defn empty?
  "判断通道是否为空。"
  [^IChannel ch]
  (.isEmpty ch))

(defn closed?
  "判断通道是否已关闭。"
  [^IChannel ch]
  (.isClosed ch))
