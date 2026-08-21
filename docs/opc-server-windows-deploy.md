# Windows OPC DA Server 部署与远程访问配置指南(测试用)

- 版本:v1(2026-08-21)
- 用途:在 Windows 测试机上部署 OPC DA Server,并配置 DCOM 远程访问,
  使 ECU 网关(192.168.1.234)上的 opc2ecu 客户端可以远程连接测试
- 目标环境:Windows(192.168.1.2,主机名 DESKTOP-EB400FS)+ Matrikon OPC Simulation
- 关联文档:
  - 客户端配置契约:docs/points-config-format.md(v2.1)
  - 客户端部署:docs/opc2ecu-deployment.md
  - 移植/验收记录:docs/imx6ul-porting.md

---

## 一、安装 OPC DA Server(Matrikon OPC Simulation)

1. 从 Matrikon 官网下载 **MatrikonOPC Simulation**(免费版,自带模拟数据源,
   约 103 个点位,含 Saw-toothed Waves.Real8 等标准点位)。
2. 默认安装(32 位组件会一并安装;OPC DA 2.0 是 COM/DCOM 架构,
   32/64 位均可运行,但配置工具必须用 32 位视图,见第三节)。
3. 安装完成后本机验证:
   - 打开 Matrikon OPC Explorer
   - 连接 `Matrikon.OPC.Simulation.1`,浏览到点位并读取值
   - 本机读不到 → 先解决安装/运行问题,再进行远程配置

> 使用其他 OPC DA Server 也可以,只要:
> - 支持 OPC DA 2.0(客户端基于 OPC DA 2.0 + OPCEnum)
> - 提供可读的数值型点位(30 字节 UDP 记录只能承载数值标量)

---

## 二、Windows 防火墙配置(远程访问第一关)

OPC DA 远程访问需要两类入站流量:
- TCP 135(DCOM 激活/OPCEnum)
- RPC 动态端口(DCOM 业务通道,Windows 默认 49152-65535)

### 方式 A(测试推荐):放行动态端口段

以管理员 PowerShell 执行:

```powershell
# 放行 TCP 135(DCOM)
New-NetFirewallRule -DisplayName "OPC DCOM 135" -Direction Inbound `
  -Protocol TCP -LocalPort 135 -RemoteAddress 192.168.1.234 -Action Allow

# 放行 RPC 动态端口段(49152-65535)
New-NetFirewallRule -DisplayName "OPC RPC dynamic" -Direction Inbound `
  -Protocol TCP -LocalPort 49152-65535 -RemoteAddress 192.168.1.234 -Action Allow
```

> `-RemoteAddress` 限定只放行 ECU 的 IP,缩小暴露面;
> 如果 ECU IP 会变,可以去掉该参数或按子网 `192.168.1.0/24` 放行。

### 方式 B(端口固定,更可控):固定 RPC 端口

1. 注册表固定 RPC 端口(例如 40001-40010):

```powershell
reg add "HKLM\SOFTWARE\Microsoft\Rpc\Internet" /v Ports /t REG_MULTI_SZ `
  /d "40001-40010" /f
reg add "HKLM\SOFTWARE\Microsoft\Rpc\Internet" /v PortsInternetAvailable `
  /t REG_SZ /d "Y" /f
```

2. 重启 Windows(或重启 RPC 相关服务)。
3. 防火墙只放行 135 + 40001-40010。
4. 注意:固定端口后,客户端 `socketTimeoutMillis` 不变,无其他影响。

---

## 三、DCOM 配置(远程访问核心)

> 关键:Matrikon/OPCEnum 是 **32 位组件**,必须用 32 位控制台打开组件服务:

```text
Win+R → mmc comexp.msc /32   ← 注意 /32 参数,64 位控制台看不到 32 位组件
```

依次配置两个组件:
1. `OPCEnum`(OPC 服务器枚举器,CLSID 固定,ProgID 为 OPCEnum)
2. `Matrikon.OPC.Simulation.1`(CLSID f8582cf2-88fb-11d0-b850-00c0f0104305)

每个组件右键 → 属性,做三处修改:

### 3.1 常规 → 身份(Identity)

- 选 **"指定用户"(This user)**,填 Windows 登录账号和密码
  (该账号将作为 DCOM 启动身份;测试环境建议用有密码的本地管理员)
- 不要用"交互式用户":远程激活时若无用户登录桌面会失败

### 3.2 安全 → 启动和激活权限(Launch and Activation)

- 选 **自定义**,编辑,添加访问账号(见下方账号清单),勾选:
  - 本地启动 / 远程启动
  - 本地激活 / 远程激活

### 3.3 安全 → 访问权限(Access)

- 选 **自定义**,编辑,添加同样账号,勾选:
  - 本地访问 / 远程访问

### 3.4 账号清单建议

| 场景 | 添加的账号 |
|---|---|
| 用具名账号测试(推荐) | 该账号本身(如 `DESKTOP-EB400FS\opctest`) |
| 简化测试(内网测试机) | `Everyone` 或 `Network Service` 对应的"网络"用户 |
| 匿名访问(不推荐) | `ANONYMOUS LOGON`(需同时开启匿名 DCOM 访问,见 3.5) |

> 最小原则:两个组件(OPCEnum + Matrikon)的启动/激活/访问权限
> 都要添加账号,否则会出现"能枚举到 server 但连不上"或"直接拒绝"。

### 3.5 (仅当需要匿名访问时)组件服务 → 我的电脑 → 属性

- COM 安全 → 访问权限/启动和激活权限 → 编辑默认值 → 添加 `ANONYMOUS LOGON`
- 同时本地安全策略开启 `网络访问:允许对 SAM 账户和共享的匿名枚举`
- 生产环境禁止匿名,此节仅测试用

---

## 四、账号与安全策略

1. 创建测试账号(如 `opctest`),**必须设置非空密码**
   (DCOM 远程默认拒绝空密码账号)
2. 本地安全策略(secpol.msc)→ 本地策略 → 安全选项:
   - `网络访问:本地帐户的共享和安全模型` = **经典 - 对本地用户进行身份验证,不改变其本来身份**
     (来宾模式会导致 DCOM 鉴权异常)
   - `网络安全性:LAN 管理器身份验证级别` = **仅发送 NTLMv2 响应/拒绝 LM 和 NTLM**(默认即可)
     客户端 opc.properties 中 `useNtlmV2=true` 与之对应
3. 测试账号加入 `Distributed COM Users` 组(可选,部分配置场景需要):
   ```powershell
   net localgroup "Distributed COM Users" opctest /add
   ```

---

## 五、验证(按顺序,每步通过再进下一步)

### 5.1 Windows 本机验证

- Matrikon OPC Explorer 本地连接 `Matrikon.OPC.Simulation.1`,读到数值
- 若用"指定用户"身份,确认该账号能正常登录 Windows(有密码)

### 5.2 ECU 侧验证(远程)

在 ECU(192.168.1.234)上执行:

```bash
cd /home/ecu/opc2ecu
export OPC_PASSWORD='<opctest 密码>'

# 1. 枚举 OPC Server(验证 OPCEnum + DCOM 激活)
bin/opc2ecu --list-server config/opc.properties
# 预期:[LIST] 出现 Matrikon.OPC.Simulation.1,退出码 0

# 2. 枚举点位(验证 server 连接 + 浏览权限)
bin/opc2ecu --list-items config/opc.properties
# 预期:[ITEMS] Found 103 item(s),退出码 0

# 3. 读单点(验证读取权限)
bin/opc2ecu config/opc.properties
# 预期:读到 Saw-toothed Waves.Real8 数值 + quality
```

对应 opc.properties 关键项:

```properties
host=192.168.1.2
domain=DESKTOP-EB400FS      # 或 WORKGROUP(本地账号时)
user=opctest
progId=Matrikon.OPC.Simulation.1
clsid=f8582cf2-88fb-11d0-b850-00c0f0104305
useNtlmV2=true
```

---

## 六、常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 能 ping 通但 --list-server 超时/失败 | ~90% 是防火墙 RPC 动态端口未放行 | 检查方式 A 的 49152-65535 规则;或改用方式 B 固定端口 |
| 退出码 3 + 0x80070005 | DCOM 启动/激活/访问权限拒绝 | 重查第三节:两个组件的三处权限都要加账号 |
| 退出码 3 + 0x800706BA | RPC 服务器不可用 | Matrikon 服务未运行;防火墙 135;Windows 未重启(方式 B) |
| 连接挂起(90s 超时)而非快速失败 | NTLM 鉴权失败(密码/域/账号) | 先怀疑密码与 domain;确认账号密码非空、经典模型 |
| 本机 OPC Explorer 能连,ECU 不能 | 权限只配了本地、没配远程 | 启动/激活/访问权限中勾选"远程启动/激活/访问" |
| mmc 里找不到组件 | 用了 64 位控制台 | 必须 `mmc comexp.msc /32` |
| 能连 server 但枚举不到点位 | 浏览权限 | Matrikon 组件访问权限加账号,重试 |

> 客户端侧超时提示:`timeout 90s` 包裹命令运行,先定位是"慢"还是"死"。

---

## 七、测试完成后的收尾

- 确认远程访问后,如无必要可移除 `Everyone`/`ANONYMOUS LOGON` 权限,恢复最小账号
- 联调数据面(UDP 5353)不受本文档影响,见 docs/opc2ecu-deployment.md
