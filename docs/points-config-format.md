# OPC DA 驱动采集点配置文件格式(对接模板)

- 版本:v2.1(2026-08-20,去除 group 歧义,统一 UTF-8)
- 用途:ECU 网关 OPC DA 驱动(Java,opcda-client)的完整运行配置;本文件为
  C++ 应用侧(配置文件生成方)与 Java 驱动侧(消费方)的对接契约
- 关联:docs/OPC-DA驱动自定义UDP通讯协议v1.2.docx(数据面协议)

## 一、文件与位置

| 项 | 值 |
|---|---|
| 路径 | /home/ecu/opc2ecu/config/points.json |
| 生成方 | C++ 应用侧(与接收端测点表同一来源维护) |
| 文件编码 | **一律 UTF-8**(含中文点位时也必须 UTF-8 存储) |
| 读取时机 | 驱动启动时读取;运行中检测到文件变更后重新加载 |
| 更新方式 | 原子写:先写 points.json.tmp,再 rename 为 points.json(禁止直接覆盖,防止驱动读到半截 JSON) |
| 文件权限 | 含明文密码,建议 600、属主 ecu;禁止提交到任何版本库/日志 |

本文件是驱动的**完整配置**(连接 + 采集 + 发送),驱动不再需要单独的
opc.properties。

## 二、JSON 格式(模板)

```json
{
  "server": {
    "host": "192.168.1.2",
    "domain": "DESKTOP-EB400FS",
    "user": "opcuser",
    "password": "***",
    "progId": "Matrikon.OPC.Simulation.1",
    "clsid": "f8582cf2-88fb-11d0-b850-00c0f0104305",
    "socketTimeoutMillis": 30000,
    "useNtlmV2": true
  },
  "periodMillis": 1000,
  "udp": {
    "host": "127.0.0.1",
    "port": 5353,
    "md5Charset": "UTF-8"
  },
  "reconnect": {
    "enabled": true,
    "initialDelayMillis": 1000,
    "maxDelayMillis": 30000,
    "maxAttempts": 0
  },
  "items": [
    "Saw-toothed Waves.Real8",
    "Saw-toothed Waves.Real4",
    "Sine Waves.Real8"
  ]
}
```

## 三、字段说明

### server(OPC 服务器连接,DCOM)

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| host | string | 是 | - | OPC 服务器 IP 或主机名 |
| domain | string | 是 | - | Windows 域;工作组环境填机器名 |
| user | string | 是 | - | DCOM 访问账号 |
| password | string | 是 | - | DCOM 密码(明文存储,见安全注意) |
| progId | string | 否* | - | 服务器 ProgID,如 Matrikon.OPC.Simulation.1 |
| clsid | string | 否* | - | 服务器 CLSID,如 f8582cf2-88fb-11d0-b850-00c0f0104305 |
| socketTimeoutMillis | int | 否 | 30000 | DCOM/OPCEnum 套接字超时,毫秒 |
| useNtlmV2 | bool | 否 | true | Windows NTLMv2 认证 |

*progId 与 clsid 至少填一个;推荐都填——clsid 直连避免依赖远程 OPCEnum 查表。

### 采集与发送

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| periodMillis | int | 是 | - | 采集周期,毫秒,>0,建议 ≥500 |
| udp.host | string | 是 | - | UDP 目标地址;同机 127.0.0.1 |
| udp.port | int | 是 | 5353 | 目标端口,1-65535 |
| udp.md5Charset | string | 否 | UTF-8 | MD5 编码字符集:UTF-8 / US-ASCII / GBK |
| items | string[] | 是 | - | 测点 Item ID 数组,非空,不重复 |

### reconnect(断线重连,可整段省略)

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| enabled | bool | 否 | true | 断线后自动重连 |
| initialDelayMillis | int | 否 | 1000 | 首次重连延迟 |
| maxDelayMillis | int | 否 | 30000 | 重连延迟封顶(指数退避) |
| maxAttempts | int | 否 | 0 | 最大重试次数,0=无限 |

## 四、items 与测点标识(核心约定)

### 4.1 items 每项 = OPC Server 实际 Item ID 全字符串

```
items 数组中的每个字符串 = OPC Server 命名空间中的完整 Item ID
```

- 必须是 OPC Server 实际暴露的 Item ID(可用 --list-items 浏览确认),
  逐字符一致,大小写敏感
- **该字符串本身就是 MD5 输入的原文**:client 原样使用,不做任何拼接、
  重组、加前缀(如服务器名)或插入分隔符
- 示例(Matrikon 模拟器):"Saw-toothed Waves.Real8"

### 4.2 "组"的概念澄清(避免歧义)

- 测点 Item ID 中如含层级(如 "Saw-toothed Waves.Real8" 的
  "Saw-toothed Waves"),那是 **server 命名空间分支**,由 OPC Server 定义,
  是 Item ID 的一部分
- **不存在也不配置** "client 采集组名":client 的 OPC Group 是运行时
  自动创建的会话标识(每次启动自动生成),不参与 Item ID,不参与 MD5,
  接收端测点表也不包含它
- 因此:点表里**不出现 group 字段**,也不要按 "服务器名.组名.点名" 的
  三段式拼接——MD5 输入就是 Item ID 原文

### 4.3 md5Charset 与文件编码

- 文件编码:UTF-8(强制)
- md5Charset 默认 UTF-8:MD5 输入 = Item ID 字符串的 UTF-8 字节序列,
  与文件编码一致,两端零转换
- GBK 仅兼容选项:仅当 C++ 内部测点表强依赖 GBK 字符串时启用;启用时
  两端必须使用完全相同的 GBK 编码规则,且该编码只发生在 MD5 计算步骤
  (文件仍为 UTF-8)
- 一致性铁律:两端对**同一个字符串、同一种字符集**计算 MD5,否则标识
  不匹配,数据块被接收端丢弃

## 五、驱动侧强制校验规则

| 规则 | 违反时行为 |
|---|---|
| server.host / user / password 非空 | 拒绝加载,配置错误退出码 2 |
| progId 与 clsid 至少一个非空 | 同上 |
| periodMillis > 0 | 同上 |
| udp.port ∈ [1, 65535] | 同上 |
| md5Charset ∈ {UTF-8, US-ASCII, GBK} | 同上 |
| items 非空数组,每项非空字符串,无重复 | 同上 |

校验失败时驱动保留旧配置继续运行(热重载场景)或报错退出(启动场景),并输出
明确日志。

## 六、安全注意(方案 B:密码入配置文件)

- password 明文存储:文件权限 600、属主 ecu;禁止入版本库、禁止写入日志
- 驱动自身保证:stdout 输出、导出 JSON、日志均不打印密码(现有代码约束)
- 现场设备丢失/被拆机时密码暴露风险由公司安全策略评估

## 七、变更生效说明(对接 C++ 侧)

- 当前版本:驱动启动时读取一次;points.json 更新后需重启驱动进程
  (systemctl restart opc2ecu.service)
- 规划中:驱动增加文件 watcher,检测到变更后自动热重载(校验失败保留旧配置),
  届时 C++ 侧只需原子写文件,无需重启驱动

## 八、示例(最小可用)

```json
{
  "server": {
    "host": "192.168.1.2",
    "domain": "DESKTOP-EB400FS",
    "user": "opcuser",
    "password": "***",
    "clsid": "f8582cf2-88fb-11d0-b850-00c0f0104305"
  },
  "periodMillis": 1000,
  "udp": { "host": "127.0.0.1", "port": 5353, "md5Charset": "UTF-8" },
  "items": [ "Saw-toothed Waves.Real8" ]
}
```
