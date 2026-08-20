# 迭代 2 开发规格:多 Item 采集 + OPC2ECU UDP 发送 + 心跳

- 制定:2026-08-20(Hermes 审核,与用户设计讨论收敛)
- 开发:Codex
- 基线:feat/iteration-1-resilience(迭代 1,待合入;本迭代基于该分支开发)
- 契约:docs/OPC-DA驱动自定义UDP通讯协议v1.2.docx(同事,V1.2 向下兼容 V1.1)
- 目标:把单 Item 探针变成生产形态——多测点周期采集,经 30B UDP 记录发送到
  网关 OPC-DA 驱动(端口 5353),附带心跳在线探测;ECU C++ 应用零改动

## 范围

### 做
1. points.json 点位表配置格式 + 加载器(连接参数仍走 opc.properties)
2. 采集循环多 Item 泛化(ReconnectManager / UtgardOpcDaClient 支持点位列表)
3. UDP 测点发送器:周期快照组包、48 块/包拆包、单向发送、统计与离线照发
4. 心跳模块:1000ms 请求、500ms 超时、会话 ID 自增匹配、连续 3 次判离线、
   收到应答即恢复、离线告警日志
5. 时间戳用 OPC 服务端采集时间;品质码原样 16-bit 编码
6. md5Charset 配置项(默认 US-ASCII,支持 GBK;集成时与 C++ 端逐字节对照)
7. 新增 --collect 生产模式 CLI(持续运行,shutdown hook 干净退出)
8. 单元测试:组包器、心跳状态机、配置加载、多 Item 采集调度(fake 驱动)

### 不做(明确砍掉)
- 写操作(客户确认不要求)
- 平台点位表导入格式/驱动框架适配层(迭代 3,等同事完整规范)
- 报文重传/确认机制(协议明确无应答不重传)
- 任何 C++ 应用改动

## 协议要点(契约摘要,详阅 docx)

- 测点数据报文:载荷 = N×30B 连续数据块,1≤N≤48,载荷≤1440B;按载荷长度
  区分报文类型(4B=心跳,30 整数倍且≤1440=业务,其它丢弃)
- 数据块:0-15 MD5 原始 16 字节(路径=serverName.groupName.itemName);
  16-23 double 小端;24-27 uint32 Unix 秒(0=无效);28-29 uint16 品质码小端
- 心跳请求(客户端→服务端):4B uint32 会话 ID,小端,自增,溢出回 0
- 心跳应答(服务端→客户端):4B,原样回填会话 ID;从 5353 端口回来源 IP/端口
- 参数:端口 5353;心跳周期 1000ms;应答超时 500ms;离线阈值连续 3 次
- 品质判定:192(0xC0)/216(0xD8)=正常,其余故障(接收端判定,发送端原样编码)
- 业务报文单向无应答;服务端离线期间业务照常按采集节奏发送

## 任务清单

### T1 points.json 配置
- 新增配置文件 schema(示例入 config/points.json.example):
  ```json
  {
    "periodMillis": 1000,
    "udp": { "host": "127.0.0.1", "port": 5353, "md5Charset": "US-ASCII" },
    "items": [ "OPCServerDemo.Group01.TAG001", "..." ]
  }
  ```
- 新增 PointsConfig 加载器(core 包):必填校验(items 非空、periodMillis>0、
  port 1-65535、charset 白名单 US-ASCII/GBK),错误走 IllegalArgumentException
- opc.properties 保持连接/重连参数,互不耦合

### T2 多 Item 采集泛化
- OpcDaClient.bindSyncRead(OpcDataCallback) → bindSyncRead(List<String> items,
  OpcDataCallback);回调带 itemId(现有 OpcReadValue 已含 itemId,保持)
- UtgardOpcDaClient:单 Group 绑定全部 items(SyncAccess 批量读,单周期一次
  RPC 批量读);周期统一为 periodMillis
- ReconnectManager:start() 接收点位列表;重连后重建 Group 并重绑全部 items;
  缺口统计语义不变(周期维度)
- 看门狗:阈值仍为 3×periodMillis;注意大点位表下批量读耗时可能接近周期,
  若实测周期超时,记录并在规格备注(不允许静默跳过)

### T3 UDP 测点发送器
- 新增 core 包 UdpRecordSender:
  - 每采集周期结束,该周期全部点位记录组包;≤48 块/包,超出拆包(载荷必须
    30 整数倍且≤1440B);同周期数据不跨周期混包
  - 发送统计:包数/块数/发送失败计数/丢包告警日志;失败不重传、不阻塞采集
  - 服务端离线状态不阻断业务发送(协议 5.1.1)
- EcuRecordCodec 复用:encode(itemPath, charset, value, unixSeconds, quality);
  新增批量组包方法或独立 Batcher(48 上限、拆包),单元测试覆盖边界

### T4 心跳模块
- 新增 core 包 HeartbeatSession:
  - 单 DatagramSocket 与业务共用(发送业务+心跳,接收 4B 应答)
  - 1000ms 周期发送心跳请求;会话 ID uint32 自增,溢出回 0
  - 接收线程:只处理 4B 应答,比对会话 ID(不匹配丢弃)
  - 状态机:500ms 超时失败计数+1;≥3 判离线+告警日志;收到有效应答计数清零
    恢复在线;离线期间继续发心跳探测
- 参数按协议固定(1000/500/3),常量集中定义,不开放配置

### T5 时钟与品质
- 时间戳来源:OPC ItemState 的服务端时间戳转 Unix 秒(现有 OpcReadValue 已带
  timestamp;转换用 state.getTimestamp(),非本地时钟)
- 品质:原样 16-bit 编码(协议判定在接收端,发送端不修改)

### T6 md5Charset
- points.json 的 udp.md5Charset 控制 MD5 输入编码;默认 US-ASCII;支持 GBK;
- 字符集解析失败/非法值 → 配置错误退出码 2

### T7 --collect 生产模式
- 新 CLI 模式:java -jar opcda-probe.jar --collect <points.json> [opc.properties]
- 持续运行直到 SIGTERM/SIGINT;shutdown hook 干净停止(停心跳、停采集、关 socket)
- 运行日志走 slf4j;stdout 只保留关键事件行(启动/离线/恢复/致命错误)
- 退出码沿用 ExitCodes;launcher/systemd 示例脚本更新 scripts/opc2ecu-launcher.sh
  (参考 README 的 i.MX6UL 运行参数:-Xms16m -Xmx48m)

### T8 单元测试(新增 ≥25 用例)
- UdpRecordSenderTest:48 块边界、49 块拆 2 包、载荷长度校验、失败计数
- HeartbeatSessionTest:ID 自增/溢出、应答匹配/不匹配丢弃、超时计数、
  3 次离线、应答恢复、离线期间继续发送(fake socket 或注入)
- PointsConfigTest:schema 校验、charset 白名单、端口边界
- 多 Item 采集:fake OpcDaClient 验证 ReconnectManager 重绑全部 items
- 回归:迭代 1 全部 51 用例保持绿

## 验收标准

1. mvn clean test 全绿(迭代 1 + 迭代 2 用例)
2. 集成对照(需 Windows+Matrikon 或真实环境):
   - 抓包验证:业务报文载荷=30 整数倍≤1440、MD5 与 C++ 端一致、小端、
     时间戳为 OPC 服务端时间、品质码正确
   - 心跳:1000ms 周期 4B 报文;停服务端→3 次后离线告警;恢复→在线,
     业务报文全程未中断
3. 实机 i.MX6UL:N 点(≥50)采集+UDP 发送连续运行 1 小时,RSS 稳定,
   -Xmx48m 内不 OOM;50 次重连 RSS 不增长
4. 离线期间业务照发(抓包确认)

## 风险与注意

- Utgard SyncAccess 多 Item 批量读:单周期 RPC 耗时随点位增长;若 periodMillis
  小于批量读耗时,采集会持续延迟——验收标准 3 实测,必要时调周期或改
  Async20Access(记录决策,不静默)
- J-Interop NTLM 安全反射修正保持;重连仍走全新 JISession
- 心跳 socket 与业务共用:接收线程只处理 4B 应答,不得阻塞发送线程
- 目标地址默认 127.0.0.1:客户端与驱动同机;若未来驱动在远端网关,改 udp.host
- 文档(同事)为 .docx,变更时人工核对;版本 V1.2 向下兼容 V1.1,本实现按 V1.2

## 交付物

- 代码 + 测试(Codex 提交,分功能 commit,英文 summary)
- docs/iter2-acceptance.md 验收记录(含抓包证据、资源实测)
- 本规格如有变更,更新并标注日期
