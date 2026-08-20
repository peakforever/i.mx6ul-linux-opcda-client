# i.MX6UL Linux OPC DA Client

本目录用于验证以下最短链路：

```text
Linux + Utgard/J-Interop → Windows OPC DA Server → 多 Item 采集 → OPC2ECU UDP
```

该程序不依赖 Wine，也不修改任何 Windows/MFC 工程。

## 构建机要求

- JDK 8 或更高版本（代码以 Java 8 字节码为目标）
- Maven 3.6 或更高版本
- 首次构建需要访问 Maven Central

构建操作只在开发机上执行，i.MX6UL 设备不需要安装 Maven 或 JDK。

```bash
cd imx6ul-linux-opcda-client
mvn clean package
```

成功后生成：

```text
target/opcda-probe.jar
```

## 配置

复制示例配置：

```bash
cp config/opc.properties.example config/opc.properties
cp config/points.json.example config/points.json
```

填写 OPC Server 地址、Windows 账号、ProgID、CLSID 和一个确定可读的 Item ID。密码不要写入配置文件，通过环境变量提供：

```bash
export OPC_PASSWORD='实际密码'
```

生产采集的点位、周期、UDP 目标和 MD5 字符集位于 points.json；OPC 连接和
重连参数仍独立保存在 opc.properties。md5Charset 可省略，默认 US-ASCII，
现场包含中文路径时可明确配置为 GBK。

## 运行

```bash
java -Djava.awt.headless=true -jar target/opcda-probe.jar config/opc.properties
```

程序默认每秒同步读取一次，成功取得 10 个样本后退出。输出包含 Value、Quality 和 Timestamp。

生产模式持续运行到 SIGTERM/SIGINT：

~~~bash
java -Xms16m -Xmx48m -XX:+UseSerialGC -Djava.awt.headless=true \
  -jar target/opcda-probe.jar \
  --collect config/points.json config/opc.properties
~~~

每个采集周期在收到全部配置点位后生成一个快照，每个 UDP 包最多 48 条记录；
超过 48 条自动拆包，不跨周期混包。业务包发送失败不重传，心跳判定对端离线时
业务仍照常发送。心跳固定每 1000 ms 发送一次，500 ms 未应答计一次失败，连续
3 次失败输出 [OFFLINE]，收到匹配会话 ID 的应答后输出 [RECOVERED]。

正式启动采集前可预检点位表：

```bash
timeout 120s java -jar target/opcda-probe.jar \
  --precheck-points config/points.json config/opc.properties
```

预检连接 OPC Server，并以每批 50 点调用 OPC `validateItems`。只有可读且规范
VARTYPE 为数值标量（I1/I2/I4/I8、UI1/UI2/UI4/UI8、R4/R8、CY）的点位通过；
BSTR、BOOL、DATE、数组及其他类型报告 `non-numeric`。输出适合脚本解析：

```text
[PRECHECK 1/2] item=Group.Numeric result=PASS reason=ok
[PRECHECK 2/2] item=Group.Text result=FAIL reason=non-numeric
[PRECHECK] summary passed=1 failed=1
```

全部通过时退出码为 `0`，存在不可读或非数值点时为 `4`。该模式只使用
points.json 中的点位列表，不创建 UDP socket、不发送业务包或心跳；不过仍复用
完整 PointsConfig 校验，因此 points.json 的周期和 UDP 字段也必须合法。

只检查配置、不连接 OPC Server：

```bash
OPC_PASSWORD='占位值' java -jar target/opcda-probe.jar \
  --check-config config/opc.properties
```

`readTimeoutSeconds` 是等待指定样本数量的总超时下限，不是底层 DCOM
握手超时。首次实机验证时建议同时在外层使用 Linux `timeout` 命令，避免网络或
RPC 配置错误导致连接长期等待：

```bash
timeout 60s java -jar target/opcda-probe.jar config/opc.properties
```

## 断线重连与退出码

默认读取模式建立连接后，会通过回调看门狗检测失联。超过 3 个采样周期未收到
回调时，客户端释放旧的 Utgard/J-Interop 对象，创建全新的 JISession、Server、
Group 和 Item，然后按指数退避策略恢复读取。旧会话不会被复用。

```properties
reconnect.enabled=true
reconnect.initialDelayMillis=1000
reconnect.maxDelayMillis=30000
reconnect.maxAttempts=0
```

退避从 1 秒开始，每次乘 2，最终延迟不超过 30 秒，并带 ±20% 抖动。
`reconnect.maxAttempts=0` 表示连接建立后的断线恢复可无限重试。关闭进程时会停止
重连并释放当前客户端。恢复后输出 `[GAP]` 摘要，包含缺口起止时间及估算漏采数。

采集模式另有周期级看门狗：即使仍有部分 Item 回调，超过 3 个采样周期没有发出
完整快照也会输出 WARN，包含上次完整快照时间和当前已收点位数，并累计
`snapshotStalls`。该告警不会触发重连；通常应运行点位预检并人工修正坏点或类型。

进程退出码：

- `0`：成功。
- `1`：未分类内部错误。
- `2`：配置错误。
- `3`：连接或 DCOM 激活失败。
- `4`：连接建立后的读取或 Item 绑定失败，或点位预检存在失败项。
- `5`：等待采样或重连等待超时。

`[CONFIG]`、`[CONNECT]`、`[READ]`、`[GAP]` 和 `[RESULT]` 行保留在 stdout，
便于脚本解析；诊断信息由 slf4j 输出到 stderr。密码不会写入日志，用户名和域在
`[CONFIG]` 行中显示为 `<redacted>`。可使用 JVM 参数调整诊断级别：

```bash
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
  -jar target/opcda-probe.jar config/opc.properties
```

## 通过远程 OPCEnum 列举 Server

如果不知道 OPC Server 的 CLSID，但 Windows 主机已经运行 OPCEnum，而且账号具有
远程 OPCEnum/DCOM 权限，可以执行：

```bash
export OPC_PASSWORD='实际密码'
timeout 60s java -jar target/opcda-probe.jar \
  --list-server config/opc.properties
```

`--list-servers` 也可作为兼容别名使用。该模式只要求配置中的 `host`、`domain` 和
`user`，不要求提前知道 `progId`、`clsid` 或 `itemId`。程序会分别枚举 OPC DA
1.0、2.0、3.0 类别，合并去重后输出：

```text
[SERVER 1/1] name=... progId=... clsid=...
```

相关配置：

```properties
socketTimeoutMillis=30000
useNtlmV2=true
```

枚举连接会启用 J-Interop NTLM Session Security，对 RPC 报文进行签名和密封，
以满足新版 Windows DCOM 激活至少使用 Packet Integrity 的要求。
由于 J-Interop 2.1.8 默认在远程激活成功后才应用该设置，程序还会在创建首个
`JIComServer` 前修正其安全默认值，确保首次 `IRemoteActivation` 调用也受保护。

此操作仍需要工业控制系统管理员授权，并依赖远端 TCP 135、RPC 动态端口、OPCEnum
服务和 DCOM 权限。它不是绕过 Windows 安全配置的扫描工具。

取得 ProgID 和 CLSID 后，可以直接浏览该 Server 暴露的 Item ID：

```bash
timeout 60s java -jar target/opcda-probe.jar \
  --list-items config/opc.properties
```

## 导出 Server 与 Item 清单

以下命令将当前配置的 OPC Server 信息及完整 Item 清单导出为 UTF-8 JSON：

```bash
timeout 120s java -jar target/opcda-probe.jar \
  --export-catalog output/opc-catalog.json config/opc.properties
```

清单包含：

- 目标主机、域/机器名、ProgID、CLSID 和生成时间。
- Server 运行状态、厂商信息、版本、Group 数量、带宽及 Server 时间。
- 所有 Item ID，以及逐项校验得到的规范 VARTYPE、读写权限和 HRESULT。

导出时只在内存中创建临时 OPC Group，并按批次校验 Item；结束时自动删除临时
Group。JSON 不包含 Windows 密码。

## i.MX6UL 运行参数

设备端只需复制 Fat JAR、配置文件和与设备 ABI 匹配的 ARM32 Headless JRE。建议初始参数：

```bash
./runtime/bin/java \
  -Xms16m \
  -Xmx48m \
  -XX:+UseSerialGC \
  -Djava.awt.headless=true \
  -jar opcda-probe.jar --collect points.json opc.properties
```

在选择 ARM JRE 前，必须先确认设备的架构、C 库和 hard-float ABI：

```bash
uname -a
uname -m
cat /etc/os-release
ldd --version
file /bin/sh
free -m
df -h
```

也可以将 `scripts/inspect-imx6ul.sh` 复制到设备并执行：

```bash
chmod +x inspect-imx6ul.sh
./inspect-imx6ul.sh > imx6ul-environment.txt 2>&1
```

本项目的设备环境结论和两种 Runtime 部署路线记录在
`docs/imx6ul-porting.md`。

根据输出选择 ARM32 Java 8 Runtime。开发机生成的 Fat JAR 是平台无关 Java
字节码，可直接复用；x86_64 JRE 不能复制到 i.MX6UL。若设备为常见的
ARMv7 hard-float + glibc 环境，Runtime 也必须匹配 `armhf`/hard-float ABI；其他
C 库环境需要选择对应构建或重新制作系统镜像。

## OPC2ECU 兼容协议

原 Windows 程序使用固定 30 字节、小端序记录：

```text
offset 0   16 字节  MD5(serverName.groupName.itemId)
offset 16   8 字节  IEEE-754 double
offset 24   4 字节  uint32 Unix 秒
offset 28   2 字节  uint16 OPC Quality
```

每个 UDP 数据报最多包含 48 条记录，即最多 1440 字节。MD5 的输入字符集必须与
原 Windows MultiByte 工程保持一致：纯 ASCII 点名可直接使用 US-ASCII；包含中文
时通常需要按现场 Windows ACP（中文系统常见 GBK）配置并与原程序对照。

可离线执行固定字节向量测试：

```bash
java -jar target/opcda-probe.jar --self-test-protocol
```

## 第一阶段验收

- Linux 能建立到 Windows OPC DA Server 的 DCOM 连接。
- 连续读取一个 Item 至少 10 次。
- Value、Quality、Timestamp 正确。
- 退出时释放连接和工作线程。
- 使用 `free`、`top` 或 `ps` 记录 i.MX6UL 上的实际内存和 CPU 占用。

### x86_64 实机验收结果（2026-08-02）

已从 Ubuntu 客户端 `192.168.1.166` 成功连接 Windows 主机
`DESKTOP-EB400FS`（`192.168.1.2`）上的 32 位 Matrikon OPC Simulation Server：

```text
ProgID: Matrikon.OPC.Simulation.1
CLSID:  f8582cf2-88fb-11d0-b850-00c0f0104305
Item:   Saw-toothed Waves.Real8
```

验证结果：

- 远程 OPCEnum 成功，发现 4 个 OPC DA 类，其中 2 个 Matrikon Server 可正常读取详情。
- Matrikon Simulation Server 连接成功，实测连接耗时 316 ms。
- Item 地址空间浏览成功，共发现 103 个 Item。
- 以 1000 ms 周期连续读取 10 次全部成功。
- 10 个样本的 `quality` 均为 `192`，即 OPC Quality `Good`。
- `Saw-toothed Waves.Real8` 的值从约 `128.8053` 连续变化到 `157.0796`，时间戳随采样更新。
- 最终输出 `[RESULT] OPC DA single-item read verification succeeded.`。

这证明 Linux 到 Windows 的 DCOM 激活、NTLM Packet Integrity、RPC 动态端口、
OPC DA Server 连接、Item 浏览和同步读取链路均已打通。退出时 J-Interop 输出的
`prepareForReleaseRef` WARN 是 COM 引用释放日志，不代表读取失败。

Windows 侧本次验证还依赖以下配置：

- 使用 32 位组件服务配置（例如 `mmc.exe comexp.msc /32`），因为 OPCEnum 和
  Matrikon Simulation Server 均为 32 位程序。
- `opcuser` 具有对应 DCOM 远程访问、远程启动和远程激活权限。
- 防火墙允许客户端访问 TCP 135，并允许 OPCEnum 与 `OPCSim.exe` 使用 RPC 动态端口。
- OPCEnum 服务已启动；Matrikon Simulation Server 的注册路径为
  `D:\Matrikon\OPC\Simulation\OPCSim.exe`。
