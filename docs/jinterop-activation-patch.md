# J-Interop 2.1.8 激活诊断补丁

来源：https://repo.maven.apache.org/maven2/org/openscada/jinterop/org.openscada.jinterop.core/2.1.8/org.openscada.jinterop.core-2.1.8-sources.jar

原始 JIRemActivation.java SHA-256（含 CRLF）：`6974be063bc5ebd3efc3d144767099218bfb06b0c179f226f7e255cc5c18fa25`。
原源码 Copyright (C) 2006 Vikram Roopchand，LGPL-3.0-or-later；文件许可头保留。
修改日期：2026-09-17；在 write/read 加入诊断；默认保留原编码、解码、异常行为。另提供显式实验开关，仅省略请求末尾10字节。
完整修改源码位于 src/main/java/org/jinterop/dcom/core/JIRemActivation.java。
shade 显式排除依赖内同名类；其他 J-Interop 类保持原版。升级依赖时必须重做源码比对与协议测试。

诊断是库对 NDR stub 的观察，不是独立抓包；bufferReportedLength 不保证为网络有效载荷长度。
日志不能证明认证成功，也不能证明响应解析位置必然正确。
只记录数值字段/固定白名单，不输出 NTLM 安全缓冲区或整包。

## 构建与本机检查

`mvn clean package`。测试与原始依赖隔离加载对照；最终 JAR 打包冒烟：

```sh
java -cp target/opcda-probe.jar:target/test-classes org.jinterop.dcom.core.ActivationPackagedSmoke
```

预期有 `[ACTIVATION] revision=2`、`hresultOffset=44 hresultBytes=11010180` 和
`[SMOKE] ...opcda-probe.jar result=PASS`。此构造响应不代表现场连接已修复。
修改源码及 LGPL/GPL 许可副本随 JAR 打包于 `META-INF/opc2ecu-patches/`。

## ECU 复现（由用户执行）

将本次构建的 target/opcda-probe.jar 传至 ECU 的 `/tmp/opcda-probe.jar` 后，
先验证该文件的 SHA-256 与开发机 `sha256sum target/opcda-probe.jar` 一致。
先使用临时 JAR 预检，无须替换服务的 JAR。以下使用既有安装路径：

```sh
sudo systemctl stop opc2ecu
umask 077
/home/ecu/opc2ecu/runtime/bin/java -Xms16m -Xmx48m -XX:+UseSerialGC \
  -Dopc2ecu.activationTrace=true -jar /tmp/opcda-probe.jar \
  --precheck-points /home/ecu/opc2ecu/config/points.json \
  > /tmp/opc2ecu-activation.log 2>&1
# 紧接上条命令执行，保留实际退出码：
echo "precheck exit=$?"
sudo systemctl start opc2ecu
```

预检不发送 UDP；可读且类型受支持的点全部通过时退出 0，连接失败退出 3，点位预检失败退出 4。
原服务重新启动后是否恢复采集，仍取决于原环境问题是否解决。
读取 `/tmp/opc2ecu-activation.log` 中 `[ACTIVATION]`、`[DIAG]`、`[ERROR]` 行即可。
诊断字段说明：

- request：实际编码的 CLSID、ORPC 前 12 字节（版本/flags/reserved）、extensions 指针、接口和 protseq。
- response-start：ORPCTHAT 原始数值头；此处异常表明未读到 HRESULT。
- response-fields：bindings 字段起止、条数、服务端 COM 版本、authHint、HRESULT 偏移及 4 字节。
  `11010180` 按小端为 `0x80010111`；authHint 不是 NTLM 认证成功证据。
- failure：最后执行的解析阶段和异常类型。response-hresult 表示库已读出错误码；
  response-bindings 等表示更早的解析失败。
- response-complete：库完整解析激活响应。后续点位是否成功仍看预检报告。
- bufferReportedLength/capacity 是库缓冲区值，不是抓包长度；hex 只是相应固定字段。

成功环境与失败环境使用同一 JAR 和开关分别收集，以 traceId 配对请求/响应。
不比较随机 CID、对象 IPID 等动态值。当前无法在本机验证真实 Windows/ECU，现场对照仍阻塞合入。


## 对照版开关（revision=2）

`-Dopc2ecu.activationOmitLegacyTail=false`（默认）为 A 组：现有无对象名请求128字节。
`-Dopc2ecu.activationOmitLegacyTail=true` 为 B 组：同请求118字节。
这是 RPC 封装前 stub 长度，不是 TCP 包大小。对照时两组都打开 activationTrace。
成功/失败均需收集 request 的 revision、omitLegacyTail、stubBytes，以及响应/结果行。
本开关是未验证的诊断实验，不修改配置文件或服务默认行为，不代表已确定根因。
