# 迭代 2 验收记录

日期：2026-08-20

## 已完成

- points.json 独立配置采集周期、UDP 目标、MD5 字符集和非空点位列表。
- 一个 Utgard SyncAccess 绑定全部点位；重连通过工厂创建全新的客户端、
  Server 和底层 JISession，并重新绑定完整点位列表。
- 每周期快照编码为 30 字节小端记录，48 条/包，超过上限拆包且不跨周期混包。
- 时间戳来自 ItemState.getTimestamp()；Quality 按原始 16 bit 发送。
- 心跳与业务共用一个 DatagramSocket，固定 1000/500/3 参数，支持 uint32
  会话 ID 回绕、应答匹配、离线和恢复状态。
- --collect <points.json> [opc.properties] 持续运行并通过 shutdown hook
  关闭采集、心跳线程和 socket。
- 保留 J-Interop 首次 DCOM 激活的 NTLM packet privacy/integrity 反射修正。

## 实现假设

Utgard SyncAccess 每周期为每个绑定 Item 产生一次回调。本实现收到全部配置 Item
各一次后发送该周期快照；若完整前先收到重复 Item，则丢弃未完成快照，避免把不同
周期的数据混入同一个 UDP 包。大点位表下若一次批量同步读取超过配置周期，程序不
跳过看门狗检查，现场应增大 periodMillis 或记录后续改用 Async20Access 的决策。

## 自动化验证

~~~text
mvn clean test
Tests run: 88, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
~~~

其中保留迭代 1 的 51 个回归测试，并新增 37 个测试，覆盖：

- 配置 schema、端口边界、字符集白名单、重复/空点位。
- 30/1440 字节边界、49 条拆包、Unix 秒、Quality 原样编码、发送失败计数。
- 心跳小端会话 ID、自增/回绕、匹配/丢弃、连续超时、离线恢复和离线继续探测。
- fake OPC 客户端的多 Item 初次绑定、断线后完整重绑和全新实例保证。
- 周期快照完整性及重复 Item 时不跨周期混包。

Java 编译目标为 class-file major version 52（Java 8）。

## 待现场验收

- Windows + Matrikon/真实 OPC Server 抓包：确认记录 MD5 与 C++ 接收端逐字节
  一致，长度为 30 的整数倍且不超过 1440 字节。
- 停止 UDP 驱动验证 3 次超时后离线、恢复后在线，以及离线期间业务持续发送。
- i.MX6UL 上以至少 50 点连续运行 1 小时，记录 -Xmx48m 下 RSS/CPU。
- 连续 50 次 OPC 重连，确认 RSS 不增长。
