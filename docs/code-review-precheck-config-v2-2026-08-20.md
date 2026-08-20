# 代码审查记录:迭代 2 收尾(点表预检 + 配置架构 v2)

- 审查:2026-08-20(Hermes)
- 基线:feat/iteration-2-udp → feat/precheck-points(8 commit)
- 验证:mvn clean test 110 用例全绿(迭代 1+2 回归 101 + 新增 9);
  fat jar 构建;冒烟见下

## 一、点表预检(--precheck-points,F1/F2 修复)

- PointValidation:VARTYPE 数值判定表精确(VT_I1/I2/I4/I8/UI1/UI2/UI4/UI8/
  R4/R8/CY),其余非数值
- 预检流程:连接 → validateItems 批量校验(50/批)→ 逐点 PASS/FAIL
  (not-readable/non-numeric/ok)→ 汇总 + 退出码(全过 0,有失败 4)
- 不创建 UDP socket、不发业务/心跳报文 ✓
- 周期级看门狗(CollectionCycle):3×period 无完整快照 → 单次告警 + 计数,
  只告警不重连(死点非连接问题);TimeSource/SnapshotListener 可注入 ✓
- ClientProvider 抽象:main 装配 Utgard,测试注入 Fake ✓

## 二、配置架构 v2(points.json 单文件完整配置)

- PointsConfig 新增 server 段解析:host/user/password 必填,domain 可空,
  progId/clsid 至少一个,socketTimeoutMillis(默认 30000)/useNtlmV2(默认 true)
- reconnect 段(可省略):enabled/initialDelayMillis/maxDelayMillis/maxAttempts,
  默认 1000/30000/0,交叉校验完整
- ProbeConfig.fromPointsConfig:points.json server 段 → ProbeConfig 单源转换
- --collect/--precheck-points:points.json 有 server 段用之;无则降级
  opc.properties + [DEPRECATED] 警告
- 探针模式(--list-server/--list-items/--export-catalog/默认读)保持 opc.properties
- 密码安全:stdout/日志/导出均不打印(冒烟实测 0 泄漏)

## 冒烟实测

- v2 points.json 单文件(无 opc.properties)→ 正确发起连接 ✓
- 密码泄漏扫描:输出全文无 password 明文 ✓
- 降级路径:[DEPRECATED] 警告 + 回退 opc.properties ✓
- 坏配置(periodMillis=0)→ 明确报错 ✓;无服务器 → 连接错误 ✓

## 观察(非阻塞)

1. 对接文档 docs/points-config-format.md v2 已与代码一致;密码明文入文件
   (方案 B),文件权限 600 + 禁止入库已写入文档安全注意
2. 热重载(watcher)仍未实现,文档按"重启生效"表述;定案后补
3. 探针模式与 collect 双配置源并存(opc.properties + points.json),部署时
   注意区分;探针为测试工具,不属生产契约

## 待现场验收(实机)

- 实机 Windows+Matrikon:预检全过 → --collect 发 UDP + 心跳 → 抓包对照 MD5
- 坏点位场景:预检 FAIL 报告正确;collect 中死点触发快照停滞告警
- points.json v2 单文件部署(i.MX6UL)冒烟
