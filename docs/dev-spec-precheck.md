# 迭代 2 收尾规格:点表预检(--precheck-points)+ 周期级看门狗

- 制定:2026-08-20(Hermes,针对审核记录 F1/F2)
- 开发:Codex
- 基线:feat/iteration-2-udp(88 测试全绿)
- 目标:消除两个中风险——坏点位静默停摆(F1)、非数值点位周期失败(F2)

## 范围

### 做
1. 新增 CLI 模式 --precheck-points <points.json> [opc.properties]
2. 周期级看门狗:超时无完整快照 → 告警
3. 测试与文档

### 不做
- 自动剔除坏点位(预检只报告,由人工修点位表)
- 预检失败自动阻断 --collect(运维流程人工把关;CLI 只给退出码)
- 写操作 / 任何协议改动

## 任务清单

### T1 --precheck-points 模式
- CLI 入口:java -jar opcda-probe.jar --precheck-points <points.json> [opc.properties]
  (Command.parse 新增 ProbeMode.PRECHECK_POINTS;复用 pointsPath 参数机制)
- 流程:
  1. 加载 opc.properties(连接参数)+ points.json(点位表,现有 PointsConfig)
  2. 连接 OPC server(复用 UtgardOpcDaClient.connect;失败 → 退出码 3,
     错误消息带防火墙/凭据提示,与现有连接错误一致)
  3. 逐点验证(复用 exportCatalog 的 validateItems 批量逻辑,50/批):
     - 存在且可读:validateItems 返回 valid
     - 数值类型:OPCITEMRESULT.getCanonicalDataType() 判定——
       数值:VT_I1/I2/I4/I8/UI1/UI2/UI4/UI8/R4/R8/CY(2,3,4,5,6,16,17,18,19,20,21)
       非数值:VT_BSTR/VT_BOOL/VT_DATE/VT_ARRAY* 等其余类型 → FAIL(non-numeric)
  4. 输出报告(机器可读):
     [PRECHECK i/n] item=... result=PASS|FAIL reason=<ok|not-readable|non-numeric>
     [PRECHECK] summary passed=N failed=M
  5. 退出码:全 PASS → 0;有 FAIL → 4(READ_ERROR);连接/配置失败沿用现有映射
- 本模式不创建 UDP socket、不发送业务报文、不启动心跳

### T2 周期级看门狗(F1)
- CollectionCycle 记录最近一次完整快照发出时间(lastSnapshotAt)
- 新增检查:超过 3×periodMillis 无完整快照 → LOGGER.warn 告警(含最后快照时间、
  已收点位计数),并统计 snapshotStalls 计数(提供 getter)
- 设计决策:只告警不重连——死点不是连接问题,重连无济于事,预检才是正道;
  告警让运维发现并修点位表
- 接入位置:ReconnectManager.checkWatchdog 所在主循环(runCollection 的
  await 循环),与现有"无任何回调"看门狗并列;两者语义区分:
  - 无任何回调 → 连接级失联 → 现有重连路径
  - 有回调但无完整快照 → 周期级停滞 → 新告警(不重连)
- CollectionCycle 增加 onSnapshot 通知机制(简单回调或共享状态,测试可注入)

### T3 测试(新增 ≥10 用例)
- VARTYPE 数值/非数值判定表(全类型映射)
- Precheck 报告输出格式与退出码(fake OpcDaClient:全过/有 not-readable/有
  non-numeric 三种场景)
- 周期级看门狗:部分点位回调 → 触发告警+计数;全点位 → 不触发
- 回归:迭代 1+2 全部 88 用例保持绿

## 验收标准

1. mvn clean test 全绿(88 + 新增)
2. --precheck-points 冒烟:无服务器时退出码 3 + 明确错误;坏 points.json 退出码 2
3. README 增加 --precheck-points 用法说明;docs/iter2-acceptance.md 补记
4. 字节码目标 Java 8;fat jar 构建正常

## 交付物

- 代码 + 测试(Codex 提交,英文 summary,功能/测试/文档分 commit)
- 推 origin feat/precheck-points
