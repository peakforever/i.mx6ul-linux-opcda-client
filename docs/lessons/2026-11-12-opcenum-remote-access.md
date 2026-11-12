# Windows 10 OPCEnum 远程扫描拒绝访问排障记录（2026-11-11～12）

本文记录用户现场执行的验证、无效尝试和最终恢复过程。Windows、ECU 操作均由用户执行；本次排障未修改 Java 或网页代码，未卸载 Kepware，未重新注册 OPC 公共组件。密码不记录在本文中。

## 结果与范围

最终 `ecu-web-config` 的 OPC DA 扫描成功，网页显示 KEPServerEX 6.4、Matrikon Modbus、Matrikon Simulation 等服务器。两项 `<unavailable>` 条目仅取得 CLSID，详情未取得，不能视为整个扫描失败，也未继续确定这些条目的原因。

本次存在两个分别验证的问题：

1. **Matrikon 直连的后续 TCP 连接超时**：ECU 到 Windows RPC 动态端口 `64714` 处于 `SYN_SENT`；为该端口放行 ECU 来源后，用户报告直连成功。
2. **OPCEnum 激活拒绝访问**：ECU、Windows 原生远程 COM 激活均返回 `0x80070005`。补齐权限后，发现普通账号无法读取 OPCEnum AppID 注册项；增加配置读取权限恢复了该账号的本机枚举。随后重启 OPCEnum，Windows 原生远程激活成功，最终网页扫描成功。

这是组合配置修复后的现场结果，不是每个配置项的独立消融实验。不能断言只有一个权限项导致了全部问题；“运行进程仍使用修改前的安全配置”是重启有效的合理解释，未通过进程内部证据独立证明。

## 环境与标识

| 项目 | 现场值 |
|---|---|
| Windows OPC 主机 | Windows 10，`192.168.3.230` |
| ECU 实际源地址 | `192.168.3.248`；本次以连接记录为准，不沿用旧部署文档地址 |
| ECU 程序目录 | `/home/ecu/opc2ecu` |
| ECU Java | Zulu ARM32 JRE 8.0.402，Linux 5.4.3 |
| 原测试账号 | 本地账号 `opcuser`，SID 末尾 `1020` |
| 新对照账号 | 本地账号 `opcprobe`，SID 末尾 `1021` |
| OPCEnum CLSID | `{13486D51-4821-11D2-A494-3CB306C10000}` |
| OPCEnum AppID | `{13486D44-4821-11D2-A494-3CB306C10000}` |
| OPCEnum 程序与服务 | `C:\Windows\SysWOW64\OpcEnum.exe`，服务 `OpcEnum`，最终以 `LocalSystem` 运行 |
| Matrikon Simulation | `Matrikon.OPC.Simulation.1`，CLSID `{F8582CF2-88FB-11D0-B850-00C0F0104305}` |
| Kepware | 最终扫描显示 KEPServerEX 6.4，ProgID `Kepware.KEPServerEX.V6` |

主机名以目标 Windows 的 `hostname` 输出为准。网页截图与命令文本中的字母 O／数字 0 容易混淆，不手工猜测。另一台 Windows 仅作为原生客户端对照，未记录其具体 IP。

## 验证与尝试过程

| 阶段 | 操作与观察 | 当时能得出的结论 |
|---|---|---|
| 网页初始失败 | `OPC_E_DCOM_ACCESS_DENIED`，`stage=opcenum-list`；原始码出现过 `0x00000005` 和 `0x80070005` | 失败涉及 OPCEnum 激活，不能单凭错误码区分密码、权限或其他安全问题 |
| 密码排查 | 用户忘记 `opcuser` 密码，在计算机管理重设；同步网页密码，尝试 WORKGROUP／机器名，错误未消失 | 改密码、改 domain 不是充分修复；本地账号应使用实际机器名，不能无限反复试域名 |
| 服务启动故障 | 手动停止 OPCEnum 后无法启动；发现服务“登录”使用 `opcuser`，改为本地系统账户后启动成功 | 服务登录身份是独立问题；旧密码可能有关，但未取得具体启动错误码，不能确认唯一原因 |
| 基础安全检查 | 本地账号安全模型已为“经典”；组件认证级别设为 Connect；检查组件和机器级远程权限 | 这些检查没有单独解决扫描失败；没有降低到 None 或关闭 DCOM 强化 |
| 事件日志 | 未找到对应 10036；有其他账号的 4625；一个 10010 的 GUID 查得为 LiveCapture COM Server | 无对应事件不能排除安全问题；无关组件超时不能归到 OPCEnum |
| 登录成功证据 | 4624：类型 3、来源 ECU IP、账号 `opcuser`、账户域为目标计算机名 | 有一次相应网络登录成功；不能据此证明后续所有 DCOM 授权通过，仍需匹配具体请求时间 |
| ECU 命令行枚举 | 独立 properties、交互输入密码，`--list-server` 同样在 `JIRemActivation.readOriginal` 返回拒绝 | 问题不是网页独有；客户端打印启用初始 NTLM 完整性／保密，不等于独立验证全部协商细节 |
| 注册核对 | OPCEnum CLSID 只在 32 位视图存在；AppID 两个视图返回一致；服务路径与 LocalSystem 已确认 | 没有 64 位 CLSID 记录本身不证明损坏；不要仅凭组件显示名称判断实际激活目标 |
| 机器级限制 | 最初 Ole 下两个限制值和策略覆盖项均未查到；后来从默认 `dcomcnfg` 编辑限制后，64 位查询看到值 | 值不存在不能解释为“全部允许”；本次看到修改后值落盘，不能泛化为所有 32 位控制台修改都无效 |
| 旧账号权限解码 | `opcuser` 的组件 Launch=`0x1F`，Access=`0x07`，无拒绝 ACE | 旧账号组件调用权限已存在；不能代替核对新账号 |
| 新建普通账号 | 新建 `opcprobe`，授予机器级及组件调用权限，扫描仍拒绝 | 新建账号本身不是修复；没有给新账号管理员权限 |
| CategoryManager 线索 | 发现 `OPC Enum x64 CategoryManager` | 它与本次激活的 OpcEnum 不是同一个组件；未取得它导致故障的证据，未将其作为修复目标 |
| 绕过枚举直连 Matrikon | `--list-items` 使用已知 CLSID；取得 COM 引用，随后 `addRef` 卡住并最终超时 | 已越过 Matrikon 初始激活，后续网络问题与 OPCEnum 激活拒绝应分开 |
| 动态端口证据 | ECU `192.168.3.248:51016 → 192.168.3.230:64714 SYN_SENT`；异常为 `ConnectException: Connection timed out` | 后续 TCP 握手未完成，不能归为密码错误；外层 `0x8001FFFF` 不能掩盖底层超时 |
| 单端口放行 | 使用 CMD `netsh` 放行 TCP 64714，仅允许 ECU IP，随后用户报告 Matrikon 直连成功 | 放行规则对这次连接有效；不代表动态端口长期固定，也不代表 OPCEnum 同时修复 |
| 直连路径激活 OPCEnum | 将测试 CLSID 换成 OPCEnum，仍用 `--list-items`，在激活返回相同 `0x80070005` | 扫描和直连代码路径的安全初始化差异，不足以解释故障；该测试只看激活，不期待 OPCEnum 提供 DA 点位接口 |
| Windows 原生客户端 | 另一台 Windows 安装 Matrikon OPC Explorer；网络邻居为空，手动指定 IP 后列表为空并提示没有安装 Server | 网络邻居不是 OPCEnum 证据；Explorer 通用提示不证明目标未安装 OPC Server；手工填 ProgID 也不能保证绕过枚举 |
| 本机账号对照 | 当前登录账号 Localhost 能列出；`opcprobe` 刷新后无法列出；确认它已获本地调用权限 | 账号差异值得调查，但仍需区分激活失败和枚举失败 |
| 原生本地激活 | 服务器以 `opcprobe` 运行 32 位 PowerShell，`Activator.CreateInstance` 返回 `0x80070005` | Windows 本机即可复现，不能再只归因于 ECU、防火墙或 Java |
| 注册项读取拒绝 | 同一账号查询 OPCEnum AppID 的 LaunchPermission、AccessPermission 都“拒绝访问”；机器级值可读 | 发现独立的注册表读取权限异常，调用权限与配置读取权限不是同一回事 |
| 补配置读取权限 | OpcEnum → 安全 → 配置权限，给 `opcprobe` 读取权限；随后该账号 Explorer Localhost 能列出 Server | 本机枚举恢复，远程当时仍拒绝，不能提前宣布远程修复 |
| 新账号权限核实 | `1021` 在机器级和组件级均有 Launch=`0x1F`、Access=`0x07`，所提供 ACL 无拒绝项 | 已用原始数据确认，不再重复扩大账号授权 |
| 原生远程激活 | 另一台 Windows 经 `runas /netonly` 使用目标机器 `opcprobe`，远程 CreateInstance 仍返回 `0x80070005` | 原生客户端也复现远程激活拒绝，问题非 J-Interop 独有；Explorer 空列表终于有明确对照错误 |
| 最后重启与恢复 | 在最终权限修改后重启 OPCEnum；原生远程激活成功，随后用户提供网页扫描成功截图 | 这组配置与重启后的现场链路已恢复；不能据此推断历史 Kepware 问题全部解决 |

期间尝试设置 `ActivationFailureLoggingLevel=1`，用户未观察到相关系统事件；它未提供有效定位证据。曾建议 `kill -3` 取线程栈，但现场实际回传的是连接最终超时异常，未取得独立线程栈结果。

## 最终有效设置与验证顺序

本节是本次现场恢复配置，不代表无需核实即可套用到任意主机。

1. OPCEnum 服务以 `LocalSystem` 运行；不将远程访问账号误当成必须的服务运行身份。服务“登录”、DCOM“标识”、组件“安全”是三个不同设置位置。
2. 目标账号直接加入机器级 COM 安全两处“编辑限制”，保留原有主体：启动／激活允许本地和远程启动、激活；访问允许本地和远程访问。
3. 对实际 AppID 对应的 **OpcEnum** 设置自定义启动／激活和访问权限，并确认 SID 是当前测试账号，而不是只检查同名旧账号或管理员组。
4. OpcEnum 的“配置权限”允许该账号**读取**；本次不需要完全控制，不需要管理员组，也未启用匿名访问。
5. 修改完成后安排重启 **OpcEnum 服务**，使运行进程重新初始化。重启会短暂影响枚举，须由现场人员安排；本次没有要求重启整台 Windows。
6. 按“普通账号本地 → 原生远程激活 → ECU 命令行枚举 → 网页扫描”验证，不把本地成功等同于远程成功。
7. 具体 OPC Server 的点位浏览、读取及 UDP 采集转发另外验收，不能以枚举成功替代。

### 原生激活对照命令

服务器本机 CMD，以目标普通账号启动 32 位 PowerShell；替换机器名：

```bat
runas /user:服务器计算机名\opcprobe "C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe -NoProfile"
```

新窗口执行下列整行，避免多行未粘贴完整出现 `>>`；`>>` 是续行提示，不是脚本策略拒绝：

```powershell
try { $t = [Type]::GetTypeFromCLSID([Guid]'{13486D51-4821-11D2-A494-3CB306C10000}'); $o = [Activator]::CreateInstance($t); 'OPCEnum activation OK' } catch { $_.Exception.ToString() }
```

另一台 Windows CMD 启动网络身份对照窗口：

```bat
runas /netonly /user:服务器计算机名\opcprobe "powershell.exe -NoProfile"
```

新窗口执行：

```powershell
try { $t = [Type]::GetTypeFromCLSID([Guid]'{13486D51-4821-11D2-A494-3CB306C10000}', '192.168.3.230'); $o = [Activator]::CreateInstance($t); 'Remote OPCEnum activation OK' } catch { $_.Exception.ToString() }
```

`runas /netonly` 启动成功不证明密码正确；窗口的本地 `whoami` 也不代表远程凭据。以目标服务器匹配时间、来源 IP 的登录事件及实际 COM 调用结果为证据。本次修改的是测试账号权限，不记录密码。

### ECU 扫描与临时文件

本次使用 `/tmp/opc-scan.properties`、`/tmp/opc-direct.properties`、`/tmp/opcenum-activation.properties` 分离测试，不改生产 `points.json`。扫描 properties 的关键字段如下：

```properties
host=192.168.3.230
domain=目标Windows实际计算机名
user=opcprobe
useNtlmV2=true
socketTimeoutMillis=30000
```

域字段替换为实际 ASCII 计算机名。密码通过交互输入并在子 shell 内导出，既不写配置，也不直接写到命令历史：

```sh
(
  printf '请输入测试账号密码：'
  stty -echo
  trap 'stty echo' EXIT
  IFS= read -r OPC_PASSWORD
  stty echo
  printf '\n'
  export OPC_PASSWORD
  /home/ecu/opc2ecu/bin/opc2ecu --list-server /tmp/opc-scan.properties
)
```

Matrikon 直连对照使用其实际 ProgID、CLSID 和 `--list-items`。OPCEnum CLSID 的 `--list-items` 实验仅用于比较激活路径；它不是可用的点位浏览配置，不能带入生产。

### 动态端口临时规则

本次在服务器管理员 CMD 中执行：

```bat
netsh advfirewall firewall add rule name="OPC-Test-64714-ECU" dir=in action=allow protocol=TCP localport=64714 remoteip=192.168.3.248 profile=any
```

`New-NetFirewallRule` 是 PowerShell 命令，不能直接在普通 CMD 中使用。临时规则仅覆盖本次动态端口和 ECU 来源，不覆盖另一台 Windows，也不保证服务重启后的端口。正式规则应依据实际服务程序、来源地址和现场策略配置；本次未验证正式规则。

正式规则验证成功后，删除临时规则：

```bat
netsh advfirewall firewall delete rule name="OPC-Test-64714-ECU"
```

## 需要纠正和保留的判断边界

- `0x80070005` 是定位入口，不是“密码错误”的充分证据。BIND_ACK、AUTH3、RESPONSE 也不能替代完整授权验证。
- 防火墙不是所有阶段的统一解释：本次确有后续动态端口阻塞，也确有 OPCEnum 原生激活拒绝；两者分别取证、分别复测。
- 注册表配置读取权限缺失是实测异常，但其对远程激活的全部内部作用路径未独立证明；不要把本地恢复直接当成远程恢复。
- 最后一次服务重启后成功，是已验证事实；未做回退实验，不将重启有效解释成所有机器级 ACL 修改均必须重启系统。
- 本机管理员能枚举不代表普通账号能枚举；普通账号本机能枚举不代表远程能枚举。
- `MachineLaunchRestriction`／`MachineAccessRestriction` 不存在时不能推断无限制；还应检查默认行为、注册表视图及策略覆盖。
- 安装 Kepware 与故障在时间上有关联的可能性仍在，但没有安装前后文件、ACL 对照或回退实验，**没有证据确认 Kepware 损坏公共组件**。最终保留 Kepware 并扫描到了它。
- [2026-09-17 历史激活问题](2026-09-17-activation.md) 的 `0x80010111` 与本次 `0x80070005` 不同，不因本次扫描恢复而宣布历史问题结案。

## 后续收尾

- 网页已验证枚举成功；用户随后报告 Matrikon 点位浏览再次超时，ECU 连接处于 SYN_SENT。Windows 查得监听 PID 为 20052，任务管理器确认程序为 OPCSim.exe。
- 用户按图形界面创建 OPCSim.exe 的 TCP 入站程序规则，远程地址限定 ECU `192.168.3.248`，随后报告点位浏览恢复。程序完整路径未回传，不在本文猜写。此为本次相对于临时单端口放行的正式修正；临时规则是否已删除尚未确认。
- 另一台 Windows 的 Explorer 远程列表在后续沟通中仍未确认恢复；只允许 ECU 的防火墙规则不覆盖该电脑。本机普通账号枚举、原生远程激活和网页枚举成功不能替代该 Explorer 的独立验收。
- Kepware 的点位浏览、读取及 Matrikon／Kepware 持续采集转发仍需分别记录；服务重启后端口变化的程序规则覆盖仍需长期观察。
- 诊断日志开关需恢复原值；若 `ActivationFailureLoggingLevel` 原来不存在，可在管理员 CMD 删除本次新增值：`reg delete "HKLM\SOFTWARE\Microsoft\Ole" /v ActivationFailureLoggingLevel /f /reg:64`。尚未确认用户已清理。
- 测试账号、测试文件和各处权限是否保留，由现场依据后续实际使用决定；本次未自动删除或变更生产账号。
- 本次没有改程序、配置契约、合入 main 或自动操作设备。

## 参考

- [Windows OPC Server 部署手册](../opc-server-windows-deploy.md)
- [Microsoft：COM 权限位定义](https://learn.microsoft.com/en-us/windows/win32/com/access-control-lists-for-com)
- [Microsoft：DCOM 机器级限制及策略覆盖](https://learn.microsoft.com/en-us/windows/win32/com/dcom-security-enhancements-in-windows-xp-service-pack-2-and-windows-server-2003-service-pack-1)
- [Microsoft：DCOMCNFG 与注册表安全设置](https://learn.microsoft.com/en-us/windows/win32/com/enabling-com-security-using-dcomcnfg)
- [Microsoft：RPC 故障排查](https://learn.microsoft.com/en-us/troubleshoot/windows-client/networking/rpc-errors-troubleshooting)
- [Matrikon OPC Explorer 官方下载](https://www.matrikonopc.com/downloads/176/software/index.aspx)

外部资料用于解释机制；本次是否成功以用户回传的日志和网页截图为准。
