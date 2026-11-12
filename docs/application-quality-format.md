# OPC 标准品质与应用扩展品质约定 v1（2026-11-12）

用户确认采用“低 8 位 OPC 标准品质，高 8 位应用原因”，取代 [废弃的完整替换草案](dev-spec-application-quality-draft.md)。适用于 `--collect` 的 UDP 品质字段；预检和探测结果不改。配置字段不变，每条仍 30 字节、小端，品质仍位于偏移 28～29。

完整 Word 协议：[OPC‑DA 驱动自定义 UDP 通讯协议 v1.3](OPC‑DA驱动自定义UDP通讯协议v1.3.docx)。Word 第 4.4 节包含状态号、含义、示例和接收端判读，第 7 节记录 V1.3 修订。

## 编码与兼容边界

```text
quality = (applicationReason << 8) | opcQualityLowByte
opcQualityLowByte = quality & 0xFF
applicationReason = (quality >> 8) & 0xFF
opcClass = quality & 0xC0
```

成功取得可编码样本时，高字节为 0，低字节完整保留服务器的 QQSSSSLL（含品质类别、子状态和限值）。服务器原高字节可能是厂商扩展，本协议由 opc2ecu 接管，不透传、不与应用原因做 OR 混合。例如源 `0xABC0` 输出 `0x00C0`；源 `0xAB0D` 输出 `0x000D`。

兼容的是 OPC **低字节标准语义**，不是保留厂商原始 16 位。接收端应按掩码解析，不把完整 uint16 与旧值相等作为唯一判断。Good=`(quality & 0xC0)==0xC0`，Uncertain=`0x40`，Bad=`0`；保留类别按未知处理。服务器 Bad/Uncertain 仍原样保留低字节，高字节 0 表示“应用没有额外错误”，不代表数据一定 Good。

## 高字节原因表

| 高字节 | 名称 | 含义 |
|---|---|---|
| `00` | NONE | 取得当前可编码样本，没有应用额外故障 |
| `01` | WAITING_FOR_DATA | 初始化等待首次样本，尚无明确错误 |
| `10` | HOST_RESOLUTION_FAILED | 主机名解析失败 |
| `11` | HOST_UNREACHABLE | 明确网络/主机不可达异常，不由普通超时猜测 |
| `12` | CONNECT_FAILED | TCP 建立失败（未能明确判为超时） |
| `13` | TIMEOUT | 明确连接/请求超时，不推断是密码或防火墙 |
| `20` | AUTHENTICATION_FAILED | 明确登录/认证失败；不能进一步区分用户名、密码等具体错误 |
| `21` | ACCESS_DENIED | 拒绝访问，认证/授权未细分，例如 `0x80070005` |
| `22` | CLASS_NOT_REGISTERED | 目标 COM/OPC 组件未注册 |
| `23` | RPC_UNAVAILABLE | RPC 服务/端点不可用 |
| `24` | RPC_CALL_FAILED | RPC 调用失败/连接中断 |
| `25` | SERVER_BUSY | 明确的 RPC 服务繁忙 |
| `26` | PROTOCOL_ERROR | 已识别协议错误，当前包括 `0x80010111`；不声称具体报文损坏 |
| `30` | POINT_MISSING | 本轮没有该点结果，无更具体错误 |
| `31` | POINT_READ_FAILED | 该点返回失败，无更具体原因 |
| `32` | SAMPLE_NOT_ENCODABLE | 数值转换、类型或采样时间范围不满足编码要求 |
| `33` | READ_STALE | 达到 3 个配置周期没有读取轮次更新 |
| `FF` | OTHER_FAILURE | 未归类故障，详细证据保留在脱敏诊断中 |

`02～0F` 等未列值保留；未知应用原因不能当成 NONE。品质不是 HRESULT，原始错误码通过诊断日志保留，不塞入高字节。

## 低字节合成规则与示例

尚未取得可编码值的点保持初始值 0、采样时间 0，低字节为 `0x20`（Bad/Waiting for Initial Data）。已有可编码值而当前失败的点保持该值及原采样时间，低字节为 `0x14`（Bad/Last Known Value）。高字节补充当前故障原因，初始化不再掩盖已知连接错误。

| 场景 | 品质 |
|---|---|
| 刚启动等待 | `0x0120` |
| 读到 Good | `0x00C0`（原低字节有附加子状态/限值时按原样保留） |
| 读到服务器 Bad／Uncertain | 如 `0x000C`／`0x0040` |
| 初始尚无值，网络不可达 | `0x1120` |
| 已有值，网络不可达 | `0x1114` |
| 已有值，超时 | `0x1314` |
| 已有值，明确认证失败 | `0x2014` |
| 已有值，访问拒绝 | `0x2114` |
| 已有值，本轮缺失／读失败／不可编码／过期 | `0x3014`／`0x3114`／`0x3214`／`0x3314` |

## 故障证据、优先级与恢复

- 明确登录错误 `0x8007052E`（或 Win32 1326）、`0x8009030C` 映射 AUTHENTICATION_FAILED；普通拒绝访问只映射 ACCESS_DENIED，禁止按异常文字包含“密码”来推断。
- 优先使用异常链中明确的 HRESULT、底层网络异常；外层通用包装不得掩盖底层 ConnectException 超时。JDK ConnectException 的明确 timeout/timed out 文本可归 TIMEOUT，未知文本仅归 CONNECT_FAILED。
- 连接级故障原因作用于全部点，并优先于本轮缺失和过期推断；等待重试不改成笼统“重连中”。下一次实际失败更新原因，后来的看门狗过期推断不覆盖已有连接故障；成功重绑本身不清除旧故障，新读取轮次提交后按该轮各点样本/失败更新。
- 单点失败只影响该点，正常点继续保留标准品质。样本转换失败须传递具体点原因，不能全都退化为“缺失”。
- 公共 RPC 读取失败在本轮结束后发布连接故障；不得由 finally 的轮次完成把故障重置。旧连接迟到的值、点错误、连接错误和周期边界全部丢弃。
- 没有明确错误但读挂起，`age >= 3P` 以单调时钟判为 READ_STALE，包括首次读取尚未返回；已知连接故障不被该推断覆盖。发送和心跳不刷新读年龄。
- 源 Bad 数值仍是本次样本，不自动改为缓存 Good 值；应用保持时以当前保持原因合成 Bad，不在一个高字节中伪装同时表达全部历史错误。
- UDP 不可达/进程停止无法靠未送达的数据包告知接收端；仍由发送日志、心跳与接收端收包超时判断。配置无法加载时也不能向未知点表发送品质。

## 开发门禁补充

本约定是用户确认的品质语义变更；沿用 [保持值规格](dev-spec-held-values.md) 的输入范围、P/3P 时序、独立线程、缓存和关闭约束。新增状态为每点当前轮次原因、最近已提交原因和连接原因，内存 O(N)，无新队列/线程或配置字段。

本机测试必须覆盖：所有已实现映射、模糊拒绝不冒充认证失败、异常包装/因果环、低字节逐位保留与厂商高字节清除、无值/有值故障组合、单点隔离、重试原因更新及成功重绑不假恢复、旧回调隔离、真实 UDP 字节序与独立周期发送。网络受沙箱限制时在允许本机 socket 的环境重跑。错误路径测试不能替代真实 DCOM/ARM/接收端集成验收。

现场由用户在接收端同时验证低字节兼容及高字节原因；没有现场通过和用户审核不合入 main，不自动操作 ECU。未执行的错误场景不能宣称已在设备验证。

## 依据

- [OPC Foundation：DA 品质低字节 QQSSSSLL，高字节厂商扩展](https://reference.opcfoundation.org/specs/OPC-10000-8/full)
- [Microsoft：ERROR_LOGON_FAILURE 0x8007052E](https://techcommunity.microsoft.com/blog/iis-support-blog/troubleshooting-http-500-19-errors-in-iis-7/347247)
- [Microsoft：SEC_E_LOGON_DENIED](https://github.com/MicrosoftDocs/win32/blob/docs/desktop-src/SecAuthN/initializesecuritycontext--general.md)
- [Microsoft：RPC Win32 错误码](https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--1700-3999-)

本轮纠正历史映射：`0x800706BB` 才是服务繁忙，`0x800706D9` 是端点未注册；`0x800706BF` 是调用失败且未执行，`0x800706D3` 是未知认证服务（不等同于密码错误）。

## 验收记录

- [x] 用户确认兼容结构；未采用完整替换草案。
- [x] 本机完整构建 208 tests，0 failures/errors/skips；协议固定向量通过。
- [x] 错误映射、逐点/全局传播、重试恢复、旧回调和真实 UDP 品质字段回归。
- [ ] 用户现场验证接收端低字节兼容、高字节原因及 ECU/DCOM 实际故障恢复。
- [ ] Hermes 与用户代码审核；代码提交推送不代表审核通过，尚未部署本轮新功能或合入 main。
