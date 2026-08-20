# i.MX6UL/ULL 移植记录

## 已确认的设备环境

```text
主机名:       ecu200
SoC/平台:     Freescale i.MX6 UltraLite/ULL
CPU:          ARMv7 Cortex-A7（armv7l，VFP/NEON）
内核:         Linux 5.4.3
发行版:       Industrial IoT Gateway OS 1.0.0
Yocto 系列:   Zeus
内存:         235 MiB，无 Swap，实测 available 162 MiB
根分区:       149.9 MiB，剩余 76.2 MiB
/data:        50.4 MiB，剩余 47.8 MiB
设备地址:     192.168.1.234/24
Java:         未安装
```

CPU 支持 VFP 只能说明硬件能力，不能单独证明 Linux 用户态采用 hard-float ABI。
选择 Runtime 前还需要通过动态加载器名称确认：

- `/lib/ld-linux-armhf.so.3`：通常为 glibc ARM hard-float。
- `/lib/ld-linux.so.3`：通常为 glibc ARM soft/softfp。
- `/lib/ld-musl-arm*.so.1`：musl。
- `/lib/ld-uClibc*.so*`：uClibc。

仓库中的 `scripts/inspect-imx6ul.sh` 已包含无 `file`、`readelf`、`ldd` 环境下的
回退检测。

实机已进一步确认：

```text
/lib/ld-linux-armhf.so.3 -> ld-2.30.so
/lib/libc.so.6 -> libc-2.30.so
```

因此目标 ABI 为 ARM32 hard-float + glibc 2.30。

### Runtime ELF 依赖审计

已使用 `readelf` 检查 compact1 JRE 的 `bin/java` 及全部原生 `.so`。rootfs 需要
提供的外部库只有：

```text
/lib/ld-linux-armhf.so.3
libc.so.6
libpthread.so.0
libdl.so.2
libm.so.6
```

JRE 最高只引用 `GLIBC_2.4` 符号；目标机是 glibc 2.30，版本要求满足。
`libjli.so`、`libjvm.so`、`libjava.so`、`libnet.so`、`libnio.so`、`libverify.so`
等均包含在 Runtime 中。compact1 包不要求 rootfs 安装 libstdc++、X11、ALSA 或字体
库。最终仍以目标机执行 `runtime/bin/java -version` 为动态加载验收标准。

## Runtime 方案

### 方案 A：集成到 Yocto 镜像（量产首选）

设备镜像基于 Yocto Zeus。对应的 OpenEmbedded `meta-java` 分支提供
`openjre-8` 配方，可以让 Java Runtime 使用与系统一致的交叉工具链、libc 和 ABI。

建议由 BSP/固件维护方：

1. 在 Zeus 构建环境加入匹配分支的 `meta-openembedded/meta-oe` 和 `meta-java`。
2. 将 `openjre-8` 加入镜像或生成独立包。
3. 禁用本项目不需要的图形、音频、打印等 PACKAGECONFIG，控制镜像大小。
4. 对 Zeus 配方中的旧 OpenJDK 8 更新版本和安全补丁，不直接将旧版 Runtime
   用于长期生产。
5. 将应用、配置和清单统一安装到 `/home/ecu/opc2ecu`；`/data` 保留给设备基础信息。

### 方案 B：随应用携带 ARM32 JRE（PoC）

只有在动态加载器、libc、hard/soft-float ABI 均匹配后才能使用预编译 JRE。部署前
应先在开发机解压并测量实际占用。应用固定部署到根文件系统中的
`/home/ecu/opc2ecu`，不使用 `/data`。当前根分区剩余 76.2 MiB，约 25 MiB 的 PoC
部署目录可以容纳，但必须为系统升级、日志和临时文件预留空间。量产分区布局可以在
重新制作镜像时缩小 `/data`、扩大 rootfs；调整 UBI 分区前应先备份设备基础信息，
并按照该设备 BSP 的烧写/升级流程操作。

PoC 已选择 Azul Zulu Embedded Java 8 compact1 ARM hard-float/glibc 包：

```text
文件: zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz
压缩大小: 14,196,200 字节
解压占用: 约 21 MiB
SHA-256: 48896cc85888bf009072efde43701db9e8ce5b7cb355aeeaed5cf8331b9c762f
```

`target/opcda-probe.jar` 约 3.8 MiB，部署目录合计约 25 MiB。构建部署包：

```bash
scripts/build-imx6ul-bundle.sh /tmp/zulu8-armhf-cp1.tar.gz
```

部署时将压缩包暂存到设备的 tmpfs，然后解压到 `/home/ecu`，不占用 `/data`：

```bash
scp target/opc2ecu-imx6ul-armhf.tar.gz root@192.168.1.234:/tmp/
ssh root@192.168.1.234 \
  'mkdir -p /home/ecu && tar -xzf /tmp/opc2ecu-imx6ul-armhf.tar.gz -C /home/ecu && rm /tmp/opc2ecu-imx6ul-armhf.tar.gz'
```

如果应用以 `ecu` 用户运行，再设置目录属主：

```bash
chown -R ecu:ecu /home/ecu/opc2ecu
```

首次只执行离线测试：

```bash
/home/ecu/opc2ecu/bin/opc2ecu --self-test-protocol
```

## 初始资源参数

设备无 Swap，第一轮建议：

```text
-Xms16m
-Xmx48m
-XX:+UseSerialGC
-Djava.awt.headless=true
```

先以 48 MiB 最大堆验证，只有在多 Item 数量和实际 RSS 证明需要时才提高到 64 MiB。
验收期间记录：

```bash
free -m
ps -o pid,rss,vsz,pcpu,comm,args
df -h
```

## 分阶段验收

1. `java -version` 能启动，架构为 ARM32。
2. `--self-test-protocol` 通过，证明 Java 与协议编码器可运行。
3. `--check-config` 通过。
4. 设备 `192.168.1.234` 到 Windows `192.168.1.2` 的 TCP 135 和 RPC 动态端口放行。
5. `--list-server`、`--export-catalog` 通过。
6. 单 Item 连续读取 10 次通过。
7. 多 Item 采集和 UDP 报文与 Windows 原程序逐字节对照。

## ARM 实机离线验收结果（2026-08-02）

部署位置：

```text
/home/ecu/opc2ecu
```

实机 Java 启动成功：

```text
openjdk version "1.8.0_402"
Zulu 8.76.0.17-CA-linux_aarch32hf
profile compact1
OpenJDK Client VM
```

协议自测成功，固定向量为：

```text
199065ab24ae156c84bc8b33e6acc683000000000000f83f04030201c000
```

资源实测：

```text
部署目录:       24.4 MiB
rootfs 使用率:  61%，剩余 56.1 MiB
物理内存:       235 MiB
测试后 available: 161 MiB
Swap:           0
```

结论：目标 rootfs 的 ARM hard-float ABI、glibc 动态依赖和 Java compact1 类库均满足
当前应用；协议编码器在 i.MX6UL/ULL 上运行正常。下一验收点为设备地址
`192.168.1.234` 到 Windows OPC DA Server 的 DCOM 连接和 Item 读取。

## ARM 实机 OPC DA 联调结果（2026-08-02）

在 Windows 防火墙仅放行可信客户端 `192.168.1.234` 的 TCP 135 和 RPC 动态端口
后，以下四项在 i.MX6UL/ULL 上全部成功：

1. `--list-server`：远程 OPCEnum 和 Matrikon Server 枚举成功。
2. `--list-items`：连接 `Matrikon.OPC.Simulation.1` 并浏览到 103 个 Item。
3. `--export-catalog`：Server 状态和完整 Item 元数据 JSON 导出成功。
4. 默认读取模式：`Saw-toothed Waves.Real8` 连续读取 10 次成功，Quality 为 Good。

结论：从 i.MX6UL/ULL 到 Windows OPC DA 的 Java、NTLM/DCOM Packet Integrity、
OPCEnum、RPC 动态端口、Server 激活、Item 浏览、元数据导出和同步读取均已通过
实机验收。下一阶段为多 Item 缓存和原 OPC2ECU 30 字节 UDP 协议发送。
