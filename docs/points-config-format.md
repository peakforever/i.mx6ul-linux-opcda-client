# OPC DA 驱动采集点配置文件格式(对接模板)

- 版本:v1(2026-08-20)
- 用途:ECU 网关 OPC DA 驱动(Java,opcda-client)读取的采集点配置;本文件为
  C++ 应用侧(配置文件生成方)与 Java 驱动侧(消费方)的对接契约
- 关联:docs/OPC-DA驱动自定义UDP通讯协议v1.2.docx(数据面协议)

## 一、文件与位置

| 项 | 值 |
|---|---|
| 路径 | /home/ecu/opc2ecu/config/points.json |
| 生成方 | C++ 应用侧(与接收端测点表同一来源维护) |
| 读取时机 | 驱动启动时读取;运行中检测到文件变更后重新加载 |
| 更新方式 | 原子写:先写 points.json.tmp,再 rename 为 points.json(禁止直接覆盖,防止驱动读到半截 JSON) |

连接参数(OPC server 地址/账号/CLSID/重连策略)在 opc.properties,由驱动侧维护,
不在此文件。

## 二、JSON 格式(模板)

```json
{
  "periodMillis": 1000,
  "udp": {
    "host": "127.0.0.1",
    "port": 5353,
    "md5Charset": "US-ASCII"
  },
  "items": [
    "OPCServerDemo.Group01.TAG001",
    "OPCServerDemo.Group01.TAG002",
    "OPCServerDemo.Group01.TAG003"
  ]
}
```

## 三、字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| periodMillis | int | 是 | 采集周期,毫秒,必须 >0;建议 ≥500 |
| udp.host | string | 是 | UDP 目标地址;驱动与接收端同机时填 127.0.0.1 |
| udp.port | int | 是 | 目标端口,协议默认 5353,范围 1-65535 |
| udp.md5Charset | string | 是 | 测点路径 MD5 编码字符集:US-ASCII 或 GBK |
| items | string[] | 是 | 测点路径数组,非空,不允许重复 |

### items 每项格式(关键)

```
opc服务器名称.opc组名称.opc测点名称
```

- 与 UDP 协议文档 4.1 节 MD5 输入字符串完全一致
- 必须与 OPC Server 实际暴露的 Item ID 逐字符一致(大小写敏感)
- 示例:Matrikon 模拟器 → "Saw-toothed Waves.Real8"

### md5Charset(数据正确性关键)

- 驱动用该字符集对 items 字符串编码后计算 MD5(16 字节原始输出)
- 必须与 C++ 接收端测点表 MD5 计算所用字符集完全一致,否则测点标识不匹配,
  数据块会被接收端丢弃
- 纯英文点位:US-ASCII;含中文点位:GBK(现场 Windows ACP)
- 如不确定,先按 US-ASCII 联调,抓包对照后再定

## 四、驱动侧强制校验规则

| 规则 | 违反时行为 |
|---|---|
| periodMillis > 0 | 拒绝加载,配置错误退出码 2 |
| udp.port ∈ [1, 65535] | 同上 |
| md5Charset ∈ {US-ASCII, GBK} | 同上 |
| items 非空数组,每项非空字符串 | 同上 |
| items 无重复项 | 同上 |

校验失败时驱动保留旧配置继续运行(热重载场景)或报错退出(启动场景),并输出
明确日志。

## 五、变更生效说明(对接 C++ 侧)

- 当前版本:驱动启动时读取一次;points.json 更新后需重启驱动进程
  (systemctl restart opc2ecu.service)
- 规划中:驱动增加文件 watcher,检测到变更后自动热重载(校验失败保留旧配置),
  届时 C++ 侧只需原子写文件,无需重启驱动
- 无论哪种机制,C++ 侧都按"原子写 + 变更即通知/等待检测"即可

## 六、示例(最小可用)

```json
{
  "periodMillis": 1000,
  "udp": { "host": "127.0.0.1", "port": 5353, "md5Charset": "US-ASCII" },
  "items": [ "OPCServerDemo.Group01.TAG001" ]
}
```
