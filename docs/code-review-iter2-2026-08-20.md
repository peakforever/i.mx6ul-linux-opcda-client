# 代码审查记录:迭代 2(UDP 采集与发送)

- 审查:2026-08-20(Hermes)
- 基线:feat/iteration-1-resilience → feat/iteration-2-udp(6 commit,+801/-22)
- 验证:mvn clean test 88 用例全绿(迭代 1 回归 51 + 新增 37);fat jar 构建;
  --self-test-protocol / --check-config 冒烟通过;错误路径退出码正确

## 结论

实现与 docs/dev-spec-iter2.md 全部任务对齐,协议符合
docs/OPC-DA驱动自定义UDP通讯协议v1.2.docx。无阻断缺陷,可进入现场验收。
发现 2 项非阻塞风险(见下),建议以"点表预检"模式一并解决。

## 逐任务核对

| 任务 | 结论 | 说明 |
|---|---|---|
| T1 points.json | ✓ | schema 校验完备(周期>0/端口 1-65535/字符集白名单/非空/去重),不可变对象 |
| T2 多 Item 泛化 | ✓ | 单 Group 全点位绑定;重连工厂建全新实例并重绑全表;CollectionCycle 按 itemOrder 组快照,重复 Item 触发"丢弃未完成快照"防跨周期混包 |
| T3 UDP 发送器 | ✓ | 30B 记录/48 条拆包/载荷≤1440;同周期不混包;失败计数不重传不阻塞;统计齐全 |
| T4 心跳 | ✓ | 1000/500/3 常量;uint32 会话 ID 回绕;应答 ID 匹配、不匹配丢弃;超时归属正确(仅计数 awaiting ID);离线恢复状态机;业务离线照发 |
| T5 时钟品质 | ✓ | 时间戳=ItemState 服务端时间;品质 16-bit 原样编码 |
| T6 md5Charset | ✓ | 默认 US-ASCII,GBK 支持,非法值→配置错误 |
| T7 --collect | ✓ | 生产模式完整装配;shutdown hook 关采集+心跳+socket;[START]/[OFFLINE]/[RECOVERED] 输出 |
| T8 测试 | ✓ | 37 新增用例(规格要求 ≥25),含 fake 驱动的多 Item 重绑验证 |

## 发现(非阻塞,建议迭代 2 收尾解决)

### F1:坏点位可能导致静默停摆(中风险)
CollectionCycle 依赖"全部点位各回调一次"才发周期快照。若某点位绑定后不产生回调
(而非返回坏品质——坏品质仍会回调),其余点位回调持续更新 ReconnectManager 的
lastSampleMillis,看门狗不触发;结果:程序不发送任何数据且无任何告警。
缓解:点表预检(上线前逐点验证)+ 周期级看门狗(超时无完整快照→告警/重连)。
注:绑定失败场景会走重连(有日志,非静默);真正静默的是"绑定成功但不回调"。

### F2:非数值点位使周期失败(中风险)
UdpRecordSender.numericValue 对非 Number 抛 IllegalArgumentException;点位表含
字符串/布尔(如 VT_BSTR/VT_BOOL)时,该周期发送失败,且无明确告警。
缓解:点表预检按 --export-catalog 的 canonicalDataType(VARTYPE)过滤非数值点。

### 共同解法:新增 --precheck-points 模式
喂 points.json + 连接参数 → 连服务器 → 逐点验证(存在/可读/数值类型)→ 输出报告
(通过/失败清单)→ 通过后才允许 --collect 上线。与 export-catalog 数据同源。

## 待现场验收(摘录 iter2-acceptance.md)

- Windows+Matrikon 抓包:MD5 与 C++ 端逐字节一致、载荷 30 整数倍≤1440
- 停 UDP 驱动:3 次超时离线、恢复在线、离线期间业务持续发送
- i.MX6UL ≥50 点连续 1 小时,-Xmx48m 下 RSS/CPU
- 连续 50 次 OPC 重连 RSS 不增长
