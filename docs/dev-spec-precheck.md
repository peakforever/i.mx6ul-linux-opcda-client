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
- 永久修改点表或把 OPC 数组隐式降级为首元素
- 预检失败自动阻断 --collect(运维流程人工把关;CLI 只给退出码)
- 写操作 / 任何协议改动

### 现场缺陷补充（2026-08-27，Kepware）

- 缺陷案例：Kepware `VT_R4` 标量值 `789.0` 经 Utgard 返回为 `JIVariant`，旧实现
  直接向核心层传递包装对象，日志显示 `OPC value is not numeric: [[789.0]]`，并在
  UDP 编码完成前中断整轮发送。
- 决策：Utgard 边界解包标量 `JIVariant`；运行时单条记录编码失败时记录 item 和
  原因、累计跳过数，其余有效记录继续按 48 条/包发送。真正的 `VT_ARRAY` 仍视为
  非数值，不隐式取首元素。预检继续作为上线前第一道门禁。

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
       布尔:VT_BOOL(11)，按 true → 1.0、false → 0.0 编码 → PASS
       非数值:VT_BSTR/VT_DATE/VT_ARRAY* 等其余类型 → FAIL(non-numeric)
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

## 布尔点支持补充（2026-09-17，用户已确认）

- 目标：布尔标签可以预检通过并上传，true → 1.0、false → 0.0。
- 实现：Utgard 继续解包为 Boolean；UDP 发送边界转换为 double；预检接受标量 VT_BOOL。
- 协议：沿用 30 字节小端记录，MD5、时间戳和 quality 保持原规则，无新增配置项。
- 边界：字符串（包括 "true"）、null、数组（包括布尔数组）仍跳过，不隐式转换。
- 缺陷案例 BOOL-20260917：现场 tag_bool=true、Boolean1=false 均出现
  `OPC record skipped ... OPC value is not numeric`，导致布尔点没有业务记录。
  根因是 sender 仅接受 Number，预检也排除了 VT_BOOL。
- 验收：真实 JIVariant 布尔解包；VT_BOOL 预检 PASS/退出码 0；布尔数组预检拒绝；
  混合周期中 true/false/数值的实际报文字节分别解码为 1.0/0.0/原值，
  时间戳和 quality 保留，字符串/null/数组跳过且不阻断有效记录。
- 开发门禁：本节记录规格、授权、缺陷案例与验收标准；当前环境未找到
  dev-gate-checklist 技能文件，按仓库现有规格文档结构补充。

## 连接诊断补充（2026-09-17）

- 缺陷案例 DIAG-20260917：未映射的 JIException 只输出 UNKNOWN/Unexpected failure，
  丢失原始错误码；分类遍历提前退出，可能掩盖内层超时或已知 HRESULT。
- 规格：未知代码继续遍历 cause；普通 ERROR 保留阶段、异常链类型、原始十六进制
  错误码及每层首个堆栈位置。异常自由文本不复制进该摘要，避免暴露凭据。
- 阶段仅报告程序可观察到的 security/server-create/server-connect/opcenum-list，
  不声称能分辨库内部所有 DCOM 步骤；未知错误明确标注根因未确定。
- 验收：未知包装异常不掩盖内层已知 HRESULT/超时；DNS/TCP 异常分类；普通 CLI
  输出保留未知原码和阶段，摘要不泄漏模拟密码。保持已有退出码。

- 现场证据 DIAG-20260917-B：0x80010111 = RPC_E_INVALID_HEADER（Microsoft COM
  Error Codes）。本项目 J-Interop 2.1.8 字节码/行号确认 JIRemActivation.read:225
  在读取响应 HRESULT 后因非零抛异常，属于远程激活失败，不能当作点位值解码失败，
  也不能由此证明认证成功。新增明确映射及回归测试；具体非法字段需服务端日志/抓包。

- 现场证据 DIAG-20260917-C：JIException(0x8001FFFF) 包装 NoRouteToHostException，
  分类应为 HOST_UNREACHABLE/network，保留原始错误码；不能据此断言密码错误、
  缺失路由或具体 TCP 端口。新增对应异常链回归测试。
