# ECU 上机验证清单（2026-11-11）

> 用户后续确认 ECU 尚无运行环境，请先使用 [首次安装清单](ecu-first-install-2026-11-11.md)。本文第 1～3 节仅适用于已有安装；首次安装后可使用第 4 节验收。

## 授权与产物

用户明确决定本轮跳过 Hermes 和用户代码审核，直接进入 ECU 验证；未执行审核不代表审核通过。设备命令全部由用户执行，未授权自动部署或合入 main。

- 目标：ECU `192.168.1.234`，已有安装 `/home/ecu/opc2ecu`，服务 `opc2ecu.service`。
- 本机：`target/opcda-probe.jar`，175 个测试全部通过，完整构建成功，主程序源文件没有晚于 JAR 的修改。
- 校验文件：`target/opcda-probe.jar.sha256`。
- 本次 JAR SHA-256：`4df5d83c67279e8cec8f66d189f21dd3a4c568555ab86703cbb97416bbc159cc`。
- JAR 来自当前未提交工作区（基线 `f68c5f4`）；用以上摘要标识本次二进制，不能仅凭基线提交号判断是否为新版。

本次只更新 JAR，保留设备上的 `config/points.json`、JRE、启动脚本及服务配置。若尚未安装或实际路径不同，先按实际情况调整，首次安装参考 build-package-deploy-manual.md，不能直接覆盖现场目录。

## 1. 从能访问 ECU 的电脑上传（用户执行）

在项目根目录执行；若开发机无法访问 ECU，先把两个文件转到可访问 ECU 的中间电脑，再调整本地路径执行。

```bash
scp -O -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa \
  target/opcda-probe.jar target/opcda-probe.jar.sha256 ecu@192.168.1.234:/tmp/
```

预期：两个文件传输成功。此步不停止当前服务、不覆盖安装目录。

## 2. ECU 离线检查（用户执行）

登录 ECU 后执行：

```sh
cd /tmp
sha256sum -c opcda-probe.jar.sha256
test -x /home/ecu/opc2ecu/runtime/bin/java
/home/ecu/opc2ecu/runtime/bin/java -Xms16m -Xmx48m -XX:+UseSerialGC \
  -Djava.awt.headless=true -jar /tmp/opcda-probe.jar --self-test-protocol
df -h /home/ecu /tmp
ls -l /home/ecu/opc2ecu/config/points.json
```

判读：

- 校验必须显示 `opcda-probe.jar: OK`，并与本文的摘要一致。
- 离线自检必须包含 `[RESULT] OPC2ECU protocol codec self-test succeeded.`；这一步不连接 Windows、不创建业务 UDP 采集实例，可在旧服务仍运行时执行。
- 自检显示的 US-ASCII 是固定测试向量的字符集，不代表生产配置默认值；生产默认仍是 UTF-8。
- `/home/ecu` 需留出旧 JAR 备份和新 JAR 暂存空间；本次 JAR 约 5.74 MiB，建议可用空间至少 20 MiB。
- 配置权限应为 `-rw-------`，属主允许服务用户 `ecu` 读取。不要打印或回传配置内容。

任一检查失败先停止，不替换正在运行的版本。

## 3. 停服务、备份与替换（ECU root 执行）

以下涉及 systemd 和文件属主，需要 root；使用你现场已有的提权方式。

先停止服务并检查是否还有手动启动的旧采集进程：

```sh
systemctl stop opc2ecu.service
systemctl is-active opc2ecu.service
ps | grep '[o]pcda-probe.jar'
```

预期：服务为 `inactive`（`is-active` 此时返回非零属正常），没有另一份 Java 采集进程。若仍有手动启动的实例，先人工正常停止，再继续。保留 ECU 的 UDP 接收端运行。

然后在同一个 root 会话中逐步执行，任一步失败先停止：

```sh
sh -eu <<'SH'
OPC_BACKUP_DIR=/home/ecu/opc2ecu/backups/$(date +%Y%m%d-%H%M%S)
mkdir -p "$OPC_BACKUP_DIR"
cp -p /home/ecu/opc2ecu/lib/opcda-probe.jar "$OPC_BACKUP_DIR/opcda-probe.jar"
printf 'Backup: %s\n' "$OPC_BACKUP_DIR/opcda-probe.jar"
cp /tmp/opcda-probe.jar /home/ecu/opc2ecu/lib/.opcda-probe.jar.next
chmod 0644 /home/ecu/opc2ecu/lib/.opcda-probe.jar.next
chown ecu:ecu /home/ecu/opc2ecu/lib/.opcda-probe.jar.next
mv /home/ecu/opc2ecu/lib/.opcda-probe.jar.next /home/ecu/opc2ecu/lib/opcda-probe.jar
cd /home/ecu/opc2ecu/lib
sha256sum -c /tmp/opcda-probe.jar.sha256
systemctl start opc2ecu.service
systemctl status opc2ecu.service --no-pager -l
journalctl -u opc2ecu.service -n 80 --no-pager
SH
```

记下输出的备份路径。脚本任一步失败会停止；若服务启动失败，另行执行 `journalctl -u opc2ecu.service -n 80 --no-pager` 收集日志。安装后的摘要必须与本文记录的版本一致。

启动判读：服务 `active (running)`，出现 `[START] Collecting ...`，点位数、周期和 UDP 地址符合现场配置。接收端确认正常值持续更新。仅有 `[START]` 或 UDP send 成功不能证明接收端收到数据。

约 60 秒后查看状态摘要：

```sh
journalctl -u opc2ecu.service -n 100 --no-pager
```

正常采集时应出现 `Collection status`，`recordsSentDelta` 持续增长；关注 `recordsSkipped`、`sendFailures` 和 `heartbeatOnline`。同步重连过程中此摘要会暂停，需同时看重连日志。

## 4. 分项验证（用户操作测试点位/服务器，ECU 只观测）

> 本节的“缺失点不补旧值”是旧版本行为。2026-11-12 新版本改为初始 0、保持最后值＋Bad，最新验收见 [保持值验证清单](held-values-validation-2026-11-12.md)。旧 JAR 摘要不能用于验证新版本。

优先使用测试服务器或测试点位。先确认基线正常，再逐个制造故障并记录时间；每项完成后恢复。

| 场景 | 操作 | 合格标准 |
|---|---|---|
| 正常采集 | 至少运行 1 分钟 | 接收端持续更新；状态摘要发送增量增长，无持续发送失败。 |
| C 无回调/读取失败/非数值 | 保持 A/B 正常，只让已绑定测试点 C 出现该类故障 | A/B 持续采集转发，不因 C 故障重连整个连接，不补发 C 的旧值。C 恢复正常返回后加入后续轮次。 |
| C 坏品质但仍有数值 | 让 C 返回坏品质数值 | A/B 照常；C 可继续携带原始坏品质转发，不能把“仍有 C 记录”判为隔离失败。 |
| 相同值持续采集 | 让 A/B 的值保持不变 | 仍每轮转发；可结合接收端时间戳/报文数验证，不能只看显示值是否变化。 |
| 整连接故障 | 临时停止测试 OPC Server，再恢复 | 出现重连日志；恢复后正常采集，不混入旧连接的迟到回调。不要为此修改 ECU 网络配置。 |
| 心跳 | 保持已确认的接收端应答 | `heartbeatOnline=true`；只接受目标 IP/端口的匹配会话应答。错误来源已在本机 socket 回归覆盖，不要求现场占用 5353 端口模拟。 |
| 停止 | 按下方命令停止、再启动 | 正常退出；无 systemd 停止超时或强制 SIGKILL。记录耗时，在途 RPC 可能等待网络超时。 |

单点隔离是本轮真实结果转发；初始注册失败的 ItemID 不承诺自动重新注册。若测试通过修改 ItemID 制造无效配置，恢复配置后仍需重启，不能将其与“已绑定点读取故障恢复”混为同一用例。

可选被动抓包（ECU 已有 tcpdump 时，替换实际网络接口/端口）：

```sh
tcpdump -ni lo -tttt -vv 'udp port 5353'
```

业务载荷为 30 字节的整数倍且不超过 1440 字节；心跳载荷 4 字节。恰好仅 A/B 两点可编码时，业务包应为 60 字节；三点可编码为 90 字节。坏品质数值仍可编码。不得用 `nc -l` 抢占现有接收端端口。

停止验证（ECU root）：

```sh
date
systemctl stop opc2ecu.service
date
systemctl show opc2ecu.service -p ActiveState -p SubState -p Result
journalctl -u opc2ecu.service -n 40 --no-pager
systemctl start opc2ecu.service
```

预期：`ActiveState=inactive`、`SubState=dead`、`Result=success`。停止失败时保留日志，不直接以强杀结果当作正常通过。

资源记录（分别在正常运行约 1、10、30 分钟时执行）：

```sh
OPC_PID=$(systemctl show opc2ecu.service -p MainPID --value)
date
awk '/^(Name|VmSize|VmRSS|Threads):/' "/proc/$OPC_PID/status"
free -m
```

记录实际点位数、采集周期与是否重连过；RSS、线程数不应持续无界增长。若本机 systemctl 不支持 `--value`，从 `systemctl show opc2ecu.service -p MainPID` 手动取 PID。

## 5. 回滚（ECU root，用户执行）

若升级后不可用，用第 3 节实际备份路径替换下面的占位路径。先确认备份存在，再执行；任一步失败先停止。

```sh
sh -eu <<'SH'
OPC_BACKUP_JAR=/home/ecu/opc2ecu/backups/实际备份目录/opcda-probe.jar
test -f "$OPC_BACKUP_JAR"
systemctl stop opc2ecu.service
cp "$OPC_BACKUP_JAR" /home/ecu/opc2ecu/lib/.opcda-probe.jar.rollback
chmod 0644 /home/ecu/opc2ecu/lib/.opcda-probe.jar.rollback
chown ecu:ecu /home/ecu/opc2ecu/lib/.opcda-probe.jar.rollback
mv /home/ecu/opc2ecu/lib/.opcda-probe.jar.rollback /home/ecu/opc2ecu/lib/opcda-probe.jar
systemctl start opc2ecu.service
systemctl status opc2ecu.service --no-pager -l
SH
```

配置未被本次升级覆盖。若为测试另行改过现场配置，需使用自己的配置备份恢复。

## 回传结果

先完成第 1～3 节，回传校验、自检、服务状态和最近日志即可；不要回传 `points.json` 或密码。后续再记录单点故障/恢复、停止耗时、实际点位数与周期、资源数据。上机通过不代表其余性能优化或合入已经完成。
