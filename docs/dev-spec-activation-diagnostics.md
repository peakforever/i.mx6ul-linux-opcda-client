# 远程激活诊断规格（2026-09-17）

## 功能概述

针对现场 0x80010111，观察 J-Interop 2.1.8 的实际 RemoteActivation 编解码，
默认模式不改激活字段、安全等级、重试或点表契约；显式实验模式仅省略请求末尾10字节。关联 docs/lessons/2026-09-17-activation.md。
用户已授权开发诊断版；ECU 操作由用户执行，不能自动部署或合入 main。

## 接口与参数

Java 系统属性 `opc2ecu.activationTrace` 默认 false，只接受 true/false（大小写不敏感）；
非法值在建立连接前报配置错误。开启后输出 `[ACTIVATION]` 单行结构化记录。
每个激活对象一个进程内递增 traceId；每次 write/read 记录阶段和字节偏移。
仅输出允许字段：CLSID、请求接口、COM 版本、ORPC flags/reserved/extensions 指针是否为空、
请求 impersonation/mode、protocol sequence、响应 authenticationHint、HRESULT、
字符串绑定数量、解析位置；固定 ORPC 头数字字段和 HRESULT 字节可输出。
不输出任意 buffer、密码、NTLM token、对象名、绑定安全主体或异常自由文本。
长度标为 bufferReportedLength/capacity，不冒充网络收到的完整报文长度。

## 参数约束与横切属性

无重试/线程/网络调用增加；诊断随原激活同步执行。每次激活日志条数 <= 8，
每条 <= 2048 字符（截断），额外内存 O(1)，不复制整包、不保留历史。
原 socketTimeout 和重连策略不变；日志 IO 时间不等于协议超时预算，现场需验证开销。
关闭诊断时不构造字段日志；诊断失败不改变原编解码异常或返回结果。

## 依赖与默认值

沿用 Maven Central 的 org.openscada.jinterop.core:2.1.8 源码中单个 JIRemActivation 类，
保留许可与来源，加入观测点和默认关闭的尾部实验开关；其余依赖不变。shade 明确排除依赖中的同名 class，
确保普通 -jar 启动加载诊断版，避免依赖合并顺序决定结果。
J-Interop 默认请求两个接口 IUnknown/IDispatch、TCP protseq 7、mode 0、impersonation 3；
只观测，不据此推断 NTLM 认证成功。认证 hint 不是实际协商安全等级。

## 验收及环境

| 项目 | 环境/方法 | 阻塞合入 |
|---|---|---|
| 实际 write 编码头/CLSID/接口/尾部；开关不改变输出或索引 | 开发机，真实编码方法，归一化随机 CID/指针 | 是，测试必须通过 |
| 0x80010111 响应原码原样抛出且记录 HRESULT 偏移/字节 | 开发机，协议构造响应走真实 read | 是 |
| 截断/ORPC 头异常记录准确最后阶段并保留原异常 | 开发机，真实 read | 是 |
| 成功响应通过后续接口指针解析 | 开发机，对照原库并构造成功响应 | 是 |
| 默认关闭/非法配置/日志不包含敏感文本 | 开发机测试 | 是 |
| fat JAR 仅一个 patched class，Java 8，打包后真实编码冒烟 | 开发机 | 是 |
| ECU 连接失败/成功两个 Windows，比较同阶段字段及性能 | 用户实机，无开发机替代 | **是，现场证据未齐前阻塞合入** |

## 独立评审与验收记录

按 dev-gate-checklist 进行独立规格/实现评审；开发机编解码验证与现场连接成功分别记录。
诊断仅提供成因证据，不宣称错误映射即修复 0x80010111。

## 开发机验收记录（2026-09-17）

- 独立规格及实现评审：通过；补齐字段构造异常隔离、敏感响应尾部与输出异常测试。
- `mvn clean package`：147 tests，0 failures/errors/skips。沙箱内库本地 resolver socket
  受阻，随后在允许本地 socket 的环境重跑通过；不把受阻路径当作验证成功。
- 原库对照：请求归一化随机 CID/唯一指针后字节一致；成功响应实际解出非空 IUnknown；
  0x80010111、ORPC 非法头、48 字节错误响应每个截断位置异常类型/消费位置一致。
- 最终 JAR：patched class 仅一份，class major=52，补丁源码和许可已内嵌。
- 打包冒烟：从 opcda-probe.jar 加载实际类，构造响应输出 hresultOffset=44、
  hresultBytes=11010180、原始异常 0x80010111，SMOKE PASS。
- 真实 ECU/Windows 激活成功与失败对照：**未验证，阻塞合入**。
  无自动部署、无 main 合入；操作清单见 jinterop-activation-patch.md。

## 单变量对照实验：激活请求尾部（2026-09-17）

- 输入：`opc2ecu.activationOmitLegacyTail`，默认 false，仅 true/false（大小写不敏感），
  非法值连接前退出配置错误。每个激活对象构造时固定，运行期间不重载。
- false 保留原编码；true 仅省略 protseq7 后的 IPv4 四个 ushort 和一个零 ushort。
  无对象名、stubStart=0 的现有请求：L(true)=L(false)-10=118 字节（RPC 封装前）。
  不修改 COM5.2、CLSID、接口、身份、安全等级、超时、重连或响应解码。
- trace 日志升为 revision=2，request 必须包含 omitLegacyTail=true/false 以排除旧 JAR/开关错误。
- 无新依赖、无新增线程/网络操作，原日志预算不变。
- 开发机门禁：缺省及显式false对照原库；true与false前118字节归一化CID/指针后一致；
  实际长度差10；开关非法值退出2；true下成功/错误响应解码不变；最终JAR双模式冒烟。
- 现场门禁：同一JAR、同配置、同服务器A(false)/B(true)/必要时再A，用户手动执行。
  若B成功只能初步支持该假设；成功环境回归、采集数据及安全兼容性未验证前阻塞合入。
- 关联案例：docs/lessons/2026-09-17-activation.md 的16:52现场证据。

### 对照版开发机验收

独立规格/实现评审通过；mvn clean package：151 tests，0 failures/errors/skips。
最终JAR A/B冒烟通过：revision=2，A omitLegacyTail=false/stubBytes=128，
B omitLegacyTail=true/stubBytes=118；构造错误响应均保留0x80010111及偏移44。
同字节副本 target/opcda-probe-ab.jar 供现场临时对照，不自动替换生产JAR。
现场A/B结果尚未取得，根因仍未证实，阻塞合入。
