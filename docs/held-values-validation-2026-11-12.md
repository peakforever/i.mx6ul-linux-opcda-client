# 保持最后值与品质发送验收清单（2026-11-12）

规格：[保持值](dev-spec-held-values.md)、[兼容品质约定](application-quality-format.md)。设备操作均由用户执行；未自动上传、部署或改配置。文档描述预期，不代表现场已完成新功能验收。

## 本机产物与验证

- `mvn -o clean package`：208 tests，0 failures/errors/skips，BUILD SUCCESS（允许本机 socket 的环境）。
- `java -jar target/opcda-probe.jar --self-test-protocol`：通过。
- 新 JAR：`target/opcda-probe.jar`。
- SHA-256：`86f12a595c6c25b3443494a22b3859f961e563ff70d6ce5c0cb0190ddd0d4279`；后续重新构建须重新校验，不把该摘要当永久版本号。
- 本轮没有重打含凭据的 ARM 完整包；旧包里的 JAR 不能验证本功能。

关键回归：HeldPointValuesTest、PeriodicCollectionPublisherTest、HeldValuesUdpTest、QualityReasonTest、QualityPropagationTest；另保留连接停止、旧回调失效、批量错误隔离、心跳来源和协议回归。真实 UDP 的值/品质/时间已解码验证；真实 Windows DCOM 断线和 ECU 资源尚未验证。

## 部署前

只替换 JAR，不改 JSON 格式或 systemd 参数。沿用 [已有升级清单](ecu-validation-2026-11-11.md) 的备份、停止、原子替换和回滚步骤，但使用本清单的新 JAR 和摘要，不能复用旧摘要。按现场渠道上传到暂存目录；所有设备操作由用户决定并执行。

确认 service 使用 `/home/ecu/opc2ecu/lib/opcda-probe.jar`；核对 points.json 的目标、点位、周期、UDP 目标及权限 600，不打印或回传密码。避免同时运行手动采集实例。若服务未创建，使用 [首次安装清单第 5 节](ecu-first-install-2026-11-11.md#5-创建服务并启动ecu-root-执行) 的 unit，先不启用开机自启。

读取记录布局：每条 30 字节，小端，偏移 0～15 为 ItemID MD5，16～23 为 double，24～27 为 uint32 采样秒，28～29 为 uint16 品质。业务数据包 ≤1440 字节；4 字节包是心跳，不计入点位记录。不要启动第二个 UDP listener 抢占现有接收端。

接收端必须明确品质高字节已由应用接管；低字节标准语义兼容，原厂商高字节不再透传。用品质掩码检查 OPC 类别，并可显示高字节原因；不能只拿整个 uint16 与旧常量相等来判断 Bad。确认接收端按新约定处理后再现场升级；本轮未修改 Windows/MFC 工程。

## 用户现场验收

选测试 Server 和已绑定的测试点 A/B/C，实际周期记为 P。按每项观察收到的载荷，不能只看进程活着、页面值不变或发送计数增长。

| 场景 | 操作 | 预期（未现场执行） |
|---|---|---|
| 初始化 | 启动服务，接收端预先开始记录；或在测试 Server 不可达时启动 | 首包所有点值 0、品质 `0x0120`、时间 0；连接未建立时继续发送，错误返回后高字节更新具体原因 |
| 正常读 | Server 可达、所有点有可编码值 | 全点表按配置顺序；值和时间随样本更新，品质低字节原样保留，高字节 0 |
| 单点失败 | 只令已绑定 C 读取失败或返回不可编码值 | A/B 正常更新；C 保持最后可编码值和原时间，读失败 `0x3114`、不可编码 `0x3214`；无初值则低字节为 `0x20` |
| Server 坏品质 | C 返回可编码数值及 Bad/Uncertain | 使用本次值和服务器低字节，高字节 0，不能无条件替换成保持 Bad |
| 连接具体错误 | 在测试环境取得明确网络不可达/超时/认证/授权错误 | 已有值时分别如 `0x1114/0x1314/0x2014/0x2114`；无值时低字节 `0x20`，拒绝访问不得直接判密码错 |
| 整连接故障 | 暂停测试 Server 或受控阻断 OPC 网络，保持 ECU UDP 接收端工作 | 显式检测到故障后全点保持 Bad；挂起未报错时读取年龄 ≥3P 后、下一发送轮降品质；连接/退避期间业务包仍到达 |
| 恢复 | 恢复 Server/网络 | 新样本重新更新值、时间和服务器低字节，高字节清零；仅重绑或保持值发包不能解除故障 |
| 重启 | 停止再启动应用 | 不带上次进程缓存，先初始 0/Bad；成功样本随后替代初始状态 |
| 分包 | 配置超过 48 个已知点 | 每个周期输出全配置点，单包最多 48 条，故障点仍占记录位置 |
| 停止 | `systemctl stop opc2ecu.service` | 不再业务发包；接收端依据收包超时识别应用停止；记录停止耗时和是否强制杀死 |
| 资源 | 1/10/30 分钟，随后 24 小时 | 记录点数、P、RSS、线程数及重连次数，无持续无界增长 |

调度是“上一轮发送结束后再等待 P”，不是硬实时；实际间隔包含编码、UDP send 和调度耗时。故障检测/降品质时间需结合此间隔，不用固定毫秒上限替代现场测量。

查看服务：

```sh
systemctl status opc2ecu.service --no-pager -l
journalctl -u opc2ecu.service -n 80 --no-pager
```

关注 `lastReadAt`、`lastReadItems`、`heldBadItems`、`invalidSamples`、`recordsSentDelta`、`sendFailures`。`heldBadItems` 是应用保持/初始化的点数，不是所有服务器返回 Bad 的点数；`recordsSentDelta` 是本机发送计数，不保证收到。同步重连期间 60 秒主循环摘要可能暂停，独立发送仍应由接收端验证。

断网恢复期间仍可能等待不响应中断的第三方 RPC；停止时限必须现场计量。未知 ItemID 初始绑定失败不是已绑定点的读取故障，当前没有单点自动注册恢复，不把二者混作一个验收。

## 结果回传

逐项记录是否执行、配置点数与周期、故障/恢复时间、收到的值/品质/采样时间、停止耗时和资源。不要回传 points.json 或密码。本机构建通过不等于现场通过，未完成现场与审核不合入 main。
