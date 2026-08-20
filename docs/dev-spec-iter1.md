# 迭代 1 开发规格:断线重连 + 单元测试基建 + 异常场景

- 制定:2026-08-20(Hermes 审核)
- 开发:Codex
- 基线:commit 2885b1a(原型,已通过构建与自检)
- 目标:在不破坏现有 CLI 行为的前提下,补上可回归测试的健壮性底座

## 范围

### 做
1. 单元测试基建(JUnit 4 + surefire,Java 8 兼容)
2. OPC 连接抽象层(OpcDaClient),把 Utgard 依赖隔离在实现里
3. 断线重连(指数退避 + 抖动,状态机,缺口记录)
4. 异常场景的明确错误与退出码
5. 日志从 println 升级为 slf4j + 简单 pattern(可开关)

### 不做(明确砍掉)
- 写操作(2026-08-20 客户确认不要求,所有迭代均不做,需求变更除外)
- 多 Item 采集与 UDP 发送(迭代 2)
- 驱动框架适配层(迭代 3,等同事规范)
- 任何 Windows/MFC 工程改动、Wine

## 任务清单

### T1 测试基建
- pom.xml 增加:junit 4.13.2(test scope)、maven-surefire-plugin 2.22.2
- src/test/java 目录,首批测试:
  - EcuRecordCodecTest:固定向量(复用 --self-test-protocol 的字节)、
    边界(空 itemPath、unixSeconds 越界、quality 越界、非 ASCII 字符集)
  - ProbeConfigTest:缺必填项、非法整数、密码缺失、LIST_SERVERS 模式的可选字段
  - JsonWriterTest:转义(引号/反斜杠/控制字符)、null 字段、缩进格式
  - ReconnectPolicyTest:退避序列、抖动范围、最大延迟封顶、maxAttempts=0 无限
  - ReconnectManagerTest:用 fake OpcDaClient 模拟 断开→恢复、持续失败→封顶、
    恢复后 Item 重建、缺口计数
- `mvn test` 全绿;--self-test-protocol 保留(作为实机冒烟)

### T2 连接抽象层
- 新增 com.taiji.opc2ecu.core.OpcDaClient(接口):
  connect() / disconnect() / isConnected() / browseItems() / exportCatalog() /
  readItem(itemId) / bindSyncRead(callback) / unbind()
- 新增 UtgardOpcDaClient(实现):把现有 OpcDaProbe 里的
  ConnectionInformation/Server/Group/SyncAccess 逻辑搬入,行为不变
- OpcDaProbe.main 改为经 OpcDaClient 调用;所有现有 CLI 模式行为与输出保持一致
  (README 中的验收记录不能被破坏)
- 目的:重连逻辑可对 fake 做单元测试,也为迭代 3 适配层留缝

### T3 断线重连
- 状态机:STOPPED / CONNECTING / CONNECTED / RECONNECTING
- 触发:readItem/bind 抛异常、同步读超时、回调停止(带看门狗:超过
  N×periodMillis 无回调即判定失联)
- 策略:初始 1s,×2,封顶 30s,±20% 抖动;maxAttempts 配置(0=无限)
- 重连流程:destroy 旧 JISession(防泄漏)→ 重新 connect → 重建 Group →
  重新 bind Item → 回到 CONNECTED
- 数据缺口:重连期间计数 missedSamples + 记录 gapStart/gapEnd,恢复后打印摘要
- 配置项(properties,全可省,有默认):
  reconnect.enabled=true / reconnect.initialDelayMillis=1000 /
  reconnect.maxDelayMillis=30000 / reconnect.maxAttempts=0
- 退出语义:收到关闭信号时干净停止(不再重连),现有 finally 释放逻辑保留

### T4 异常场景
- 错误分类并映射退出码:
  配置错误=2 / 连接失败(凭据/CLSID/DCOM 拒绝)=3 / 连接后读失败=4 /
  超时=5 / 内部错误=1
- 错误消息包含可操作的修复提示(如"检查 OPC_PASSWORD 环境变量"、
  "确认 Windows 防火墙放行 TCP 135 与 RPC 动态端口")
- 验证:任何异常路径都不挂死、不无限等待(socketTimeout 生效)、
  JISession 必定 destroy(用 try/finally 审计)

### T5 日志
- slf4j-api + slf4j-simple(已是依赖,统一到 slf4j)
- [CONFIG]/[CONNECT]/[READ]/[RESULT] 等关键行保留 stdout 机器可读输出;
  诊断细节走 logger
- 日志绝不打印密码/凭据

## 验收标准

1. `mvn clean test` 全绿(新增 ≥20 个测试用例)
2. 所有现有 CLI 模式(--check-config/--list-server/--list-items/
   --export-catalog/--self-test-protocol/默认读)输出与基线一致(README 记录对照)
3. 集成验证(需要 Windows+Matrikon 环境,时间窗由用户安排):
   - 读取中拔掉网关网络 30s 再恢复 → 自动重连成功,输出缺口摘要
   - 错误凭据 → 退出码 3,提示清晰,不挂死
   - 连续 10 次重连失败 → 按策略退避,不疯狂重试
4. 内存:重连循环不增长(重复 connect/disconnect 50 次后 RSS 稳定,实机验收)
5. fat jar 构建正常,`--self-test-protocol` 在 ARM 实机通过

## 风险与注意

- Utgard/J-Interop 是旧库,断线后重连的 COM 引用释放可能不干净——重连必须
  走"新 JISession + 新 Server"而不是复用旧对象;若发现 prepareForReleaseRef
  泄漏,记录并在 T3 实现中显式 destroy
- 反射修改 JIComServer.defaults 的安全初始化必须保持(Windows DCOM 加固要求)
- 不改动现有包的类名/行为,新增代码放 core/ 与 test/;重构以行为等价为前提

## 交付物

- 代码 + 测试(Codex 提交,分功能 commit)
- 本迭代验收记录追加到 docs/imx6ul-porting.md 或独立 docs/iter1-acceptance.md
