# ECU 首次安装与验证（2026-11-11）

用户确认 ECU 尚无 Java 客户端运行环境，但 UDP 接收程序已经部署。本次改走完整部署包，不使用仅替换 JAR 的升级步骤；安装和验证时保留接收程序运行。全部设备命令由用户执行；本机仅准备产物，没有上传或操作 ECU。

## 产物

- 完整包：`target/opc2ecu-imx6ul-armhf.tar.gz`，19,537,241 字节，约 18.6 MiB。
- 校验文件：`target/opc2ecu-imx6ul-armhf.tar.gz.sha256`。
- 本次完整包 SHA-256：`5e0b2703c4ae8204506d7ef7bfff16d7665b6380cc7cd77b91223758dba19674`。
- 内含固定 ARM32 hard-float/glibc JRE 8、175 个测试通过的程序 JAR、启动脚本、现有点位配置及探测配置；无需在 ECU 安装 Maven、JDK 或全局 Java。
- 包内普通文件合计 27,544,374 字节，约 26.3 MiB；磁盘实际占用略大。建议 `/home/ecu` 所在分区至少留 40 MiB 可用空间，`/tmp` 至少留 25 MiB 用于暂存压缩包。
- 包含现有明文密码配置，归档及密码文件权限均为 600；只在现场设备使用，不上传版本库。首次联网前由用户在设备上核对配置，不打印或回传密码。

## 1. 先确认 ECU 环境（ECU 执行）

```sh
uname -m
ls -l /lib/ld-linux-armhf.so.3 /lib/libc.so.6
df -h / /tmp /home
id
id ecu
command -v systemctl
```

预期：

- `uname -m` 为 `armv7l`；存在 ARM hard-float 加载器和 glibc。仓库历史记录为 glibc 2.30，最终以本次设备实际输出及 Java 自检为准。
- 有足够空间；运行用户 `ecu` 存在；系统提供 systemd。
- 安装和创建服务需 root，平时服务以 `ecu` 用户运行。

若缺加载器、用户或 systemd，先回传检查输出，按设备实际环境适配，不混用不同系统的安装命令。若旧安装目录或服务文件已经存在，先确认其用途，不直接覆盖。

## 2. 上传完整包（能访问 ECU 的电脑执行）

项目根目录执行，以下以设备可登录的 `ecu` 用户传包；若现场使用 root 登录，可将目标用户名改为 root。开发机不能访问 ECU 时先转包到可访问的中间电脑。

```bash
scp -O -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa \
  target/opc2ecu-imx6ul-armhf.tar.gz target/opc2ecu-imx6ul-armhf.tar.gz.sha256 \
  ecu@192.168.1.234:/tmp/
```

ECU 执行：

```sh
cd /tmp
sha256sum -c opc2ecu-imx6ul-armhf.tar.gz.sha256
```

必须显示 `opc2ecu-imx6ul-armhf.tar.gz: OK`。与本文的摘要核对一致后再解压。

## 3. 安装目录与离线自检（ECU root 执行）

脚本先校验包、确认没有既有安装，再执行解压；任一步失败会停止。未通过自检不会启动采集。

```sh
sh -eu <<'SH'
id ecu
test ! -e /home/ecu/opc2ecu
cd /tmp
sha256sum -c opc2ecu-imx6ul-armhf.tar.gz.sha256
mkdir -p /home/ecu
tar -xzf opc2ecu-imx6ul-armhf.tar.gz -C /home/ecu
OPC_GROUP=$(id -gn ecu)
chown -R "ecu:$OPC_GROUP" /home/ecu/opc2ecu
chmod 600 /home/ecu/opc2ecu/config/points.json
/home/ecu/opc2ecu/runtime/bin/java -version
/home/ecu/opc2ecu/bin/opc2ecu --self-test-protocol
SH
```

预期：

- 目录有 `bin/`、`config/`、`lib/`、`runtime/`。
- `java -version` 正常输出 Java 8 版本，不出现缺动态加载器、共享库、非法指令或执行权限错误。
- 自检包含 `[RESULT] OPC2ECU protocol codec self-test succeeded.`。
- 自检中的 US-ASCII 是固定回归向量，不代表生产配置默认值；生产默认仍为 UTF-8。

若同名目录已存在，脚本会在解压前停止。若解压后 Java 失败，会留下安装目录供定位，不反复覆盖；回传报错即可。

## 4. 核对现场配置

在设备上使用编辑器核对 `/home/ecu/opc2ecu/config/points.json`：Windows 地址、账号、密码、ProgID/CLSID、点位、采集周期、UDP 接收目标以及字符集。保持 UTF-8 和权限 600；修改配置后需重启采集。

不要将实际配置内容或密码粘贴回聊天。采集和点位预检从该文件取密码，无需额外 `export OPC_PASSWORD`。

Windows 测试 OPC Server 可访问后，以 `ecu` 用户执行预检（如果当前为 root，请通过现场可用方式切换到 ecu 的会话）：

```sh
/home/ecu/opc2ecu/bin/opc2ecu --precheck-points /home/ecu/opc2ecu/config/points.json
```

预期：连接成功、已知正常数值/布尔点预检通过。故意配置的坏点可以失败，但不应把连接失败误判为单点隔离成功。`0x80010111` 激活失败仍按既有排障记录处理，不代表此次采集优化有误。

## 5. 创建服务并启动（ECU root 执行）

确认无既有手动采集实例，UDP 接收程序保持运行。服务单元若已存在，先停止并确认用途；下方脚本拒绝覆盖已有文件。

```sh
sh -eu <<'SH'
test -x /home/ecu/opc2ecu/bin/opc2ecu
test ! -e /etc/systemd/system/opc2ecu.service
cat > /etc/systemd/system/opc2ecu.service <<'UNIT'
[Unit]
Description=OPC DA client (opc2ecu)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ecu
WorkingDirectory=/home/ecu/opc2ecu
ExecStart=/home/ecu/opc2ecu/bin/opc2ecu
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl start opc2ecu.service
SH
systemctl status opc2ecu.service --no-pager -l
journalctl -u opc2ecu.service -n 80 --no-pager
```

预期：服务持续为 `active (running)`，出现 `[START] Collecting ...`；点位数、周期、UDP 目标符合现场配置。接收程序存在时，应进一步确认实际收到数据、心跳在线；启动成功本身不能证明完整链路成功。

用户已确认 UDP 接收程序部署完成，本次无需安装或替换接收程序；仍需在现场确认它处于运行状态、监听地址/端口与 points.json 一致，并观察实际收包和心跳。安装包只包含 Java OPC DA 客户端。

约 60 秒后检查 `Collection status`，再按 [上机验证清单第 4 节](ecu-validation-2026-11-11.md#4-分项验证用户操作测试点位服务器ecu-只观测) 验证单点隔离、恢复、停止和资源占用。该清单前面的“只换 JAR”步骤不适用于本次首次安装。

验证稳定后，若需要开机自启，再由用户执行：

```sh
systemctl enable opc2ecu.service
```

停止测试用 `systemctl stop opc2ecu.service`，不需要卸载系统 Java 或重启固件。

## 当前回传内容

先回传第 1 节环境检查输出；条件满足后再按后续章节操作，并回传校验、自检、服务状态和最近日志。设备安装、在线采集、接收端及资源验收均尚未执行，不能仅凭本机打包成功标记上机通过。
