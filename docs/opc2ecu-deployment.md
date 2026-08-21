# OPC DA 客户端(opc2ecu)部署文档

- 版本:v1(2026-08-21)
- 适用程序:opcda-probe.jar(迭代 1+2+预检+配置 v2.1,111 测试)
- 目标设备:ECU 系列物联网关(i.MX6UL/ULL)
- 关联文档:
  - 配置契约:docs/points-config-format.md(v2.1)
  - 移植记录:docs/imx6ul-porting.md(ABI 实测、Runtime 方案)
  - 数据面协议:docs/OPC-DA驱动自定义UDP通讯协议v1.2.docx

---

## 一、概述

opc2ecu 是运行在 ECU 网关上的 Java 8 OPC DA 客户端:通过 DCOM 从 Windows
OPC DA Server 采集测点,按 OPC2ECU UDP 协议 v1.2(30 字节记录 + 4 字节心跳)
发送给本机 C++ 采集应用。C++ 侧零改动——UDP 发送就是集成机制。

部署形态:单一自包含目录 `/home/ecu/opc2ecu/`(Fat JAR + ARM32 JRE + 配置 +
启动脚本),由 systemd 托管,`Restart=always` 自愈。

交付路线两条(详见第四节):

| 路线 | 场景 | JRE 来源 |
|---|---|---|
| A:Yocto 镜像集成 | 量产首选 | meta-java `openjre-8` 配方,随镜像构建 |
| B:随应用携带 JRE | PoC / 现网验证中 | Zulu Embedded 8 compact1 armhf,随包部署 |

两条路线使用同一个 `opcda-probe.jar`,程序侧无差异。

---

## 二、程序运行需求

### 2.1 目标设备(已实测确认)

| 项 | 实测值 |
|---|---|
| 主机名 | ecu200 |
| SoC | Freescale i.MX6 UltraLite/ULL |
| CPU | ARMv7 Cortex-A7(armv7l,VFP/NEON) |
| 内核 | Linux 5.4.3 |
| 发行版 | Industrial IoT Gateway OS 1.0.0(Yocto Zeus) |
| 内存 | 235 MiB,无 Swap(实测 available 162 MiB) |
| 根分区 | 149.9 MiB,剩余 76.2 MiB |
| /data | 50.4 MiB,剩余 47.8 MiB |
| 设备地址 | 192.168.1.234/24 |
| ABI | ARM32 hard-float + glibc 2.30 |

> 部署前必须确认 ABI,不能只看 CPU 支持 VFP。判定方法:检查动态加载器
> 名称 `/lib/ld-linux-armhf.so.3`(=glibc hard-float)或运行仓库内
> `scripts/inspect-imx6ul.sh`。

### 2.2 系统软件依赖(JRE 的 ELF 依赖)

已用 `readelf` 审计,compact1 JRE 的 `bin/java` 及全部原生 `.so` 只依赖
rootfs 提供以下基础库:

```text
/lib/ld-linux-armhf.so.3
libc.so.6
libpthread.so.0
libdl.so.2
libm.so.6
```

- JRE 最高只引用 `GLIBC_2.4` 符号;目标机 glibc 2.30,版本满足。
- **不需要** X11、ALSA、字体、libstdc++、Java 原生桥(Wine/JACOB 等)。
- 最终验收标准:目标机执行 `runtime/bin/java -version` 动态加载成功。

### 2.3 Java 运行时

程序是 Java 8 字节码,`jar` 平台无关;但 JVM 是原生程序,**必须与设备
ABI 完全匹配**:ARMv7 32 位 + hard-float + glibc。

| 项 | PoC 采用(Zulu compact1) | 量产路线(meta-java) |
|---|---|---|
| 包 | zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz | openjre-8(OpenJDK 8) |
| 压缩/解压 | 14.2 MB / 约 21 MiB | 需裁剪(见 4.2.5) |
| SHA-256 | 48896cc85888bf009072efde43701db9e8ce5b7cb355aeeaed5cf8331b9c762f | - |

> `scripts/build-imx6ul-bundle.sh` 硬编码了上述 SHA-256 校验;更换 JRE 版本
> 时必须同步更新脚本内的 `EXPECTED_SHA256`。

### 2.4 磁盘占用

```text
部署目录合计约 25 MiB:
  runtime/ (JRE)    约 21 MiB
  lib/opcda-probe.jar 约 3.8 MiB
  config/ bin/        其余
```

当前 rootfs 剩余 76.2 MiB 可以容纳,但需为系统升级、日志、临时文件预留
空间。量产建议重新规划分区(见 4.2.5)。

### 2.5 网络需求

| 方向 | 协议/端口 | 用途 | 放行要求 |
|---|---|---|---|
| ECU → Windows OPC Server(192.168.1.2) | TCP 135 | DCOM 激活/OPCEnum | Windows 防火墙放行 ECU IP |
| ECU → Windows OPC Server | TCP RPC 动态端口 | DCOM 业务通道 | 同上(可固定 RPC 端口后只放开该段) |
| ECU 回环 | UDP 127.0.0.1:5353 | 30B 数据记录 + 4B 心跳 → C++ 接收端 | 回环,无需外部放行 |

- Windows 侧 DCOM 配置(账号/权限/32 位 comexp.msc)不在本程序部署范围,
  见 `docs/imx6ul-porting.md` 与验收记录;联调前置条件见第五节。
- UDP 目标是 `points.json` 的 `udp.host/port` 决定,与 C++ 接收端监听一致。

### 2.6 配置需求

- 生产采集与点位预检:`/home/ecu/opc2ecu/config/points.json`
  (契约 v2.1:server 连接段 + periodMillis + udp + reconnect + items;
  强制 UTF-8;含明文密码,**权限 600、属主 ecu、禁止入版本库/日志**)。
- 探测模式(枚举/导出/读取):`config/opc.properties`,密码走环境变量
  `OPC_PASSWORD`,不落盘。
- 配置变更生效方式:重启进程(`systemctl restart opc2ecu.service`);
  热重载(watcher)规划中,未实现。

### 2.7 JVM 运行参数(256MB 内存设备)

```bash
-Xms16m -Xmx48m -XX:+UseSerialGC -Djava.awt.headless=true
```

- 48 MiB 最大堆为第一轮验证值;多 Item 采集实机 RSS 如证明需要再提至 64 MiB。
- SerialGC 单线程回收,适合小堆嵌入式场景,减少停顿与线程开销。

---

## 三、部署基本原理

### 3.1 为什么是"Fat JAR + 随应用 JRE"

1. **字节码平台无关,构建与运行分离**。开发机 `mvn clean package` 产出
   `target/opcda-probe.jar`,Java 8 字节码在 x86_64 与 ARM 上完全相同,
   开发机无需交叉编译;只有 JVM 是原生程序,必须按目标 ABI 提供。
2. **Fat JAR 单文件**。maven-shade-plugin 把依赖(Utgard/JInterop、
   Jackson、slf4j、bcprov)合并进一个 jar,`java -jar` 直接运行,
   设备上零 classpath 管理,升级只换一个文件。
3. **JRE 随应用携带**(路线 B)使部署目录自包含,不依赖设备预装 Java,
   版本可控、可整体回滚;代价是 21 MiB 磁盘与安全补丁需自行跟进。

### 3.2 为什么是独立进程 + UDP 协议边界

- 既有 C++ 采集应用通过 30 字节小端 UDP 记录(16B MD5 + 8B double +
  4B 秒 + 2B quality,每包 ≤48 条)消费数据——**UDP 发送是集成机制**。
  程序作为独立进程跑在网关上,向 `127.0.0.1:5353` 发送,与 Windows 时代
  (Windows 主机发往网关)只换了"谁在发",C++ 应用零改动。
- 独立进程带来:语言栈隔离(Java 异常/GC 不影响 C++ 采集)、崩溃隔离、
  systemd 自愈、按需启停与升级互不影响。
- 心跳自治:4 字节心跳独立状态机(1000ms 周期/500ms 超时/连续 3 次判
  OFFLINE),与业务发送解耦;业务断网期间照常发送。

### 3.3 目录布局

```text
/home/ecu/opc2ecu/
├── bin/
│   └── opc2ecu            # 启动脚本(见 3.4)
├── lib/
│   └── opcda-probe.jar    # Fat JAR(唯一程序产物)
├── config/
│   ├── points.json        # 生产采集配置(v2.1,600 权限)
│   └── opc.properties     # 探测模式配置(密码走环境变量)
└── runtime/               # JRE(路线 B:Zulu compact1;路线 A:镜像内置)
    └── bin/java
```

### 3.4 启动器(`bin/opc2ecu`)

```sh
#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_HOME=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
if [ "$#" -eq 0 ]; then
    set -- --collect "$APP_HOME/config/points.json" "$APP_HOME/config/opc.properties"
fi
exec "$APP_HOME/runtime/bin/java" \
    -Xms16m -Xmx48m -XX:+UseSerialGC -Djava.awt.headless=true \
    -jar "$APP_HOME/lib/opcda-probe.jar" "$@"
```

- 无参数时默认进入生产采集模式(`--collect`);`exec` 保证 PID 归属 Java,
  systemd 能正确管理。
- 第二个参数 `opc.properties` 是向后兼容的旧配置回退(仅当 points.json
  缺 server 段时使用并打弃用警告),生产配置以 points.json 为准。

### 3.5 配置生效机制

- 驱动启动时读取一次 points.json;变更后需重启进程。
- C++ 侧更新文件务必**原子写**:先写 `points.json.tmp`,再 `rename` 为
  `points.json`,防止驱动读到半截 JSON(热重载落地后此约定同样生效)。
- 校验失败:启动场景报错退出(退出码 2);热重载场景保留旧配置继续运行。

### 3.6 进程管理(systemd)

```ini
# /etc/systemd/system/opc2ecu.service
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
```

- `Restart=always` 提供自愈:进程崩溃/被 OOM 杀掉后 5 秒自动拉起。
- 数据断链由程序内部重连管理(指数退避 1s→30s + 抖动),与 systemd
  重启无关,二者不冲突。

### 3.7 日志与落盘(flash 磨损)

- stdout 输出结构化行:`[CONFIG]` `[CONNECT]` `[READ]` `[GAP]` `[OFFLINE]`
  `[RECOVERED]` `[RESULT]`,供脚本解析;诊断信息由 slf4j 走 stderr。
- 密码永不写入日志(程序内已约束);用户名/域在 `[CONFIG]` 行脱敏为
  `<redacted>`。
- NAND 有写入寿命限制:建议 journald 使用 volatile 存储或配置 logrotate
  上限;如需持久日志,挂 tmpfs 或转储到远程,避免高频采集日志直接写 flash。

### 3.8 升级与回滚

```bash
# 1. 停服务
systemctl stop opc2ecu.service
# 2. 备份旧 jar(回滚点)
cp /home/ecu/opc2ecu/lib/opcda-probe.jar /tmp/opcda-probe.jar.bak
# 3. 替换新 jar(不覆盖 config/)
scp opcda-probe.jar ecu@192.168.1.234:/tmp/
ssh ecu@192.168.1.234 \
  'install -m 0644 /tmp/opcda-probe.jar /home/ecu/opc2ecu/lib/ && chown ecu:ecu /home/ecu/opc2ecu/lib/opcda-probe.jar'
# 4. 离线冒烟
ssh ecu@192.168.1.234 '/home/ecu/opc2ecu/bin/opc2ecu --self-test-protocol'
# 5. 启动并确认
systemctl start opc2ecu.service
systemctl status opc2ecu.service
journalctl -u opc2ecu.service -n 50 --no-pager
```

- 回滚:停服务 → 用备份 jar 覆盖 → 启动。
- 配置升级只动 `config/`,程序升级只动 `lib/`,互不覆盖。

---

## 四、使用 Yocto 实施

### 4.1 两条路线对比

| 维度 | A:镜像集成(meta-java) | B:携带 JRE(PoC) |
|---|---|---|
| JRE 一致性 | 与系统同工具链/libc/ABI 构建 | 预编译包,需手工核 ABI |
| 部署操作 | 烧写镜像即含,零现场步骤 | 解包 + chown + 装 service |
| 升级 | 重建镜像/包 | 只换 jar,现场可做 |
| 磁盘 | 标准 JRE 需裁剪或扩分区 | 21 MiB 固定 |
| 安全维护 | OpenJDK 8 补丁随 BSP 跟进 | 需自行跟进 Zulu 更新 |
| 适用阶段 | 量产 | 现网 PoC/联调 |

**建议:两条线并行**——联调阶段用 B 继续验证协议与性能;量产前按 A
落地,程序产物是同一个 jar,投入不浪费。

### 4.2 路线 A:meta-java 集成 openjre-8(量产首选)

目标发行版为 Yocto **Zeus**(3.0)。以下步骤由 BSP/固件维护方在构建
主机上执行。

#### 4.2.1 添加 layer

```bash
# 选择与 Zeus 匹配的分支(以仓库实际分支名为准)
git clone -b zeus https://git.openembedded.org/meta-openembedded
git clone -b zeus https://github.com/openjdk/meta-java
```

在 `conf/bblayers.conf` 中加入:

```text
BBLAYERS += " \
  ${TOPDIR}/../meta-openembedded/meta-oe \
  ${TOPDIR}/../meta-java \
"
```

#### 4.2.2 启用 openjre-8 并裁剪

`conf/local.conf`:

```text
IMAGE_INSTALL:append = " openjre-8"
```

`meta-java` 的 openjre-8 配方有 PACKAGECONFIG 选项,关闭本项目不需要的
图形/音频/打印/浏览器组件,控制镜像体积(具体选项以配方为准,示例):

```text
PACKAGECONFIG:remove:pn-openjre-8 = "x11 cups alsa"
```

> 本项目为 headless 纯网络程序,不依赖 AWT/图形;裁剪项可进一步核验
> 配方支持的开关后收紧。

#### 4.2.3 应用 recipe(opc2ecu.bb 骨架)

把 `opcda-probe.jar`、`points.json`(模板)、`opc2ecu-launcher.sh`、
`opc2ecu.service` 放入 recipe 的 `files/` 目录:

```bitbake
SUMMARY = "OPC DA client for ECU gateway"
LICENSE = "CLOSED"
inherit systemd

SRC_URI = " \
    file://opcda-probe.jar \
    file://points.json \
    file://opc2ecu-launcher.sh \
    file://opc2ecu.service \
"

do_install() {
    install -d ${D}/home/ecu/opc2ecu/bin
    install -d ${D}/home/ecu/opc2ecu/lib
    install -d ${D}/home/ecu/opc2ecu/config
    install -m 0755 ${WORKDIR}/opc2ecu-launcher.sh \
        ${D}/home/ecu/opc2ecu/bin/opc2ecu
    install -m 0644 ${WORKDIR}/opcda-probe.jar \
        ${D}/home/ecu/opc2ecu/lib/opcda-probe.jar
    install -m 0600 ${WORKDIR}/points.json \
        ${D}/home/ecu/opc2ecu/config/points.json
    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/opc2ecu.service \
        ${D}${systemd_system_unitdir}/opc2ecu.service
}

FILES:${PN} = "/home/ecu/opc2ecu ${systemd_system_unitdir}/opc2ecu.service"
SYSTEMD_SERVICE:${PN} = "opc2ecu.service"
```

> 注意:
> - `points.json` 含明文密码,**真实密码不应打进镜像**。量产应放模板
>   (密码占位符),由现场首次上电初始化或远程下发真实配置。
> - `/home/ecu` 目录需在镜像中预创建并设属主(`User=ecu` 运行时要求)。
> - launcher 脚本内 `runtime/bin/java` 路径在路线 A 下不存在(JDK 装在
>   系统路径);量产版 launcher 应改用 `exec java ...`(PATH 解析)或
>   指向 `/usr/bin/java`。

#### 4.2.4 镜像空间与分区

- 标准 OpenJDK 8 JRE 体积大于 PoC 的 compact1(21 MiB),裁剪后仍可能
  使 rootfs 紧张;当前 rootfs 仅剩 76 MiB。
- 量产建议重新规划 UBI 分区(缩小 /data、扩大 rootfs),操作前先备份
  设备基础信息,并遵循该设备 BSP 的烧写/升级流程。
- 备选:若空间仍不足,可评估裁剪 JRE(compact1 风格)替代完整 JRE,
  或 jlink 式模块裁剪(需确认 OpenJDK 8 支持度)。

#### 4.2.5 安全维护

- Zeus 配方的 OpenJDK 8 较旧,量产前应升配方版本/应用安全补丁,
  不直接以旧版 Runtime 长期生产。
- 后续可评估升级发行版(Yocto 新版 + 新版 JDK)一并进行。

### 4.3 路线 B:PoC 手动部署(随应用携带 JRE)

#### 4.3.1 构建部署包(开发机)

```bash
cd ~/i.mx6ul-linux-opcda-client
mvn clean package                      # 产出 target/opcda-probe.jar(111 测试)
cp config/points.json.example config/points.json   # 填真实环境
cp config/opc.properties.example config/opc.properties
scripts/build-imx6ul-bundle.sh /path/to/zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz
# 产出 target/opc2ecu-imx6ul-armhf.tar.gz,脚本内置 JRE SHA-256 校验
```

#### 4.3.2 部署到设备

```bash
# 压缩包暂存 tmpfs,解压到 /home/ecu(不占 /data)
scp target/opc2ecu-imx6ul-armhf.tar.gz root@192.168.1.234:/tmp/
ssh root@192.168.1.234 \
  'mkdir -p /home/ecu && tar -xzf /tmp/opc2ecu-imx6ul-armhf.tar.gz -C /home/ecu && rm /tmp/opc2ecu-imx6ul-armhf.tar.gz'
ssh root@192.168.1.234 'chown -R ecu:ecu /home/ecu/opc2ecu'
# 安装 systemd 单元(见 3.6)
scp opc2ecu.service root@192.168.1.234:/etc/systemd/system/
ssh root@192.168.1.234 'systemctl daemon-reload && systemctl enable --now opc2ecu.service'
```

#### 4.3.3 首次冒烟

```bash
ssh ecu@192.168.1.234 '/home/ecu/opc2ecu/bin/opc2ecu --self-test-protocol'   # 协议自测
ssh ecu@192.168.1.234 '/home/ecu/opc2ecu/bin/opc2ecu --check-config /home/ecu/opc2ecu/config/points.json'
```

---

## 五、部署后验收清单

### 5.1 离线项(不依赖 Windows)

1. `runtime/bin/java -version` 输出 ARM32(aarch32hf / Client VM)。
2. `bin/opc2ecu --self-test-protocol` 通过(固定字节向量)。
3. `bin/opc2ecu --check-config config/points.json` 通过。
4. `systemctl status opc2ecu.service` active(running)。

### 5.2 在线项(依赖 Windows+Matrikon,192.168.1.2)

5. `--list-server` / `--list-items`:OPCEnum、103 个 Item 浏览成功。
6. `--precheck-points config/points.json`:点位全部 PASS(非数值点 FAIL,
   退出码 4 属预期,需修正点表)。
7. `--collect`:日志出现 `[START]`,`journalctl` 无异常;UDP 5353 抓包
   有 30B 倍数数据(用 `verify-opcda.sh --collect` 自动化)。

### 5.3 联调前置 4 项(与 C++ 同事确认)

1. C++ 接收端(5353 监听 + 测点表 + 心跳应答)已部署 ECU。
2. 契约 v2.1 三要点:items = MD5 输入原文 / 文件 UTF-8 / 字符集一致。
3. 环境:Matrikon 在线、Windows 防火墙放行 ECU(192.168.1.234)。
4. points.json 密码填真实值(联调包内为 CHANGE_ME)。

### 5.4 资源项

```bash
free -m                                    # 无 Swap 下内存余量
ps -o pid,rss,vsz,pcpu,comm,args -C java   # RSS 基线(建议 <64 MiB)
df -h                                      # rootfs 余量
```

---

## 六、退出码与故障排查

| 退出码 | 含义 | 排查方向 |
|---|---|---|
| 0 | 成功 | - |
| 1 | 未分类内部错误 | 查看 stderr/slf4j 日志 |
| 2 | 配置错误 | `--check-config` 定位;校验规则见契约文档第五节 |
| 3 | 连接/DCOM 激活失败 | Windows 防火墙 135+动态端口、DCOM 权限、账号密码、NTLMv2 |
| 4 | 读取/Item 绑定失败,或预检有失败项 | 点表 Item ID 是否与 server 一致;`--list-items` 核对 |
| 5 | 等待采样/重连超时 | 网络抖动、`socketTimeoutMillis` 是否过小 |

常见问题:

- **连接挂起不报错**:NTLM 认证失败会表现为超时而非快速失败;外层加
  `timeout 90s` 运行,先怀疑密码/域错误。
- **能 ping 通但连不上**:~90% 是 Windows 防火墙 RPC 动态端口未放行;
  32 位 Server(Matrikon/OPCEnum)需用 `mmc.exe comexp.msc /32` 配置。
- **预检 FAIL non-numeric**:该点位 VARTYPE 非数值标量(BSTR/BOOL/DATE/
  数组),接收端 30B 记录只能承载数值,需从点表剔除或改类型。
- **[OFFLINE] 心跳离线**:C++ 接收端未监听/未应答;业务包仍发送,检查
  接收端日志与监听端口。

---

## 七、相关文档

- 配置契约 v2.1:`docs/points-config-format.md`(C++ 侧对接模板)
- 移植记录与 ABI 实测:`docs/imx6ul-porting.md`
- 数据面协议:`docs/OPC-DA驱动自定义UDP通讯协议v1.2.docx`
- 程序用法/构建:`README.md`
- 状态与联调前置:`docs/STATUS-2026-08-20.md`
