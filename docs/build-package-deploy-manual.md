# OPC DA 驱动(opc2ecu)构建·打包·部署操作手册

- 版本:v1(2026-08-25)
- 适用分支:feat/precheck-points(123 测试全绿)
- 目标设备:ECU 网关(i.MX6UL/ULL,192.168.1.234,/home/ecu/opc2ecu)
- 关联文档:
  - 部署原理/运行需求:docs/opc2ecu-deployment.md
  - 配置契约 v2.1:docs/points-config-format.md
  - Windows OPC Server 侧配置:docs/opc-server-windows-deploy.md
  - 数据面协议:docs/OPC-DA驱动自定义UDP通讯协议v1.2.docx

---

## 〇、三侧环境与角色

| 侧 | 位置 | 网段 | 干什么 |
|---|---|---|---|
| 开发机 | 本机(~/i.mx6ul-linux-opcda-client) | 192.168.189.x | 构建、打包 |
| 构建机 | 现场可达的中间机器 | 与 ECU 同网段或可达 | 转包(开发机到不了现场) |
| ECU | 192.168.1.234 | 192.168.1.x | 部署、运行 |

> 开发机(189.x)与现场(192.168.1.x)不同网段,部署包必须经构建机转包。

---

## 一、构建(开发机)

### 1.1 前置条件

- JDK 8+(本机 JDK17 在 ~/.local/bin)、Maven 3.6+
- 首次构建需访问 Maven Central;直连失败时:
  export HTTPS_PROXY=http://127.0.0.1:7890

### 1.2 构建命令

cd ~/i.mx6ul-linux-opcda-client
mvn clean package

### 1.3 预期与判读

- 输出末尾:BUILD SUCCESS
- 测试汇总:Tests run: 123, Failures: 0, Errors: 0, Skipped: 0
- 产物:target/opcda-probe.jar(fat jar,唯一程序产物)
- 判读:FAILURES > 0 或 BUILD FAILURE → 修复后重跑,不要继续打包

---

## 二、打包(开发机)

### 2.1 准备 JRE 压缩包(只需一次,后续复用)

下载 Zulu Embedded 8 compact1 ARM32(14.2MB):

curl -fSL -x http://127.0.0.1:7890 -o /tmp/zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz "https://cdn.azul.com/zulu-embedded/bin/zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz"

校验 SHA-256(必须等于下面这个值,不一致则丢弃重下):

sha256sum /tmp/zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz

预期:48896cc85888bf009072efde43701db9e8ce5b7cb355aeeaed5cf8331b9c762f

### 2.2 打包命令

cd ~/i.mx6ul-linux-opcda-client
sh scripts/build-imx6ul-bundle.sh /tmp/zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz

### 2.3 预期与判读

- 输出:[BUNDLE] /home/peakforever/i.mx6ul-linux-opcda-client/target/opc2ecu-imx6ul-armhf.tar.gz
- 大小约 19MB,并打印该包的 SHA-256
- 判读:脚本要求 target/opcda-probe.jar 已存在(先完成第一节);JRE 校验失败会报 Unexpected Runtime SHA-256

### 2.4 产物自检(可选)

tar tzf target/opc2ecu-imx6ul-armhf.tar.gz | grep -E "runtime/bin/java|lib/opcda-probe.jar|bin/opc2ecu"

预期三行都在:JRE、jar、启动脚本。

---

## 三、转包到现场

开发机到不了 192.168.1.x,按你的实际条件二选一:

- U 盘/文件共享:把 target/opc2ecu-imx6ul-armhf.tar.gz 拷到构建机
- scp(构建机可达开发机时):scp ~/i.mx6ul-linux-opcda-client/target/opc2ecu-imx6ul-armhf.tar.gz <构建机用户>@<构建机IP>:/tmp/

然后在构建机上传到 ECU(注意 SSH 兼容参数,见 3.1):

scp -O -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa opc2ecu-imx6ul-armhf.tar.gz ecu@192.168.1.234:/tmp/

### 3.1 SSH 兼容说明(ECU 只支持 ssh-rsa,且无 SFTP)

ECU 的 dropbear(2019.78)主机密钥与公钥认证只支持 rsa/dss/ecdsa;而 OpenSSH 8.8+
默认禁用了 ssh-rsa,直接 scp/ssh 会报 "no matching host key type" 或 "no matching key
exchange method"。所有连 ECU 的命令都要带两个参数:

  -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa

另一个坑:OpenSSH 9.0+ 的 scp 默认走 SFTP 子系统,而 dropbear 没有 sftp-server,
会报 "/usr/libexec/sftp-server: No such file or directory" 后连接关闭。必须加 -O
强制传统 SCP 协议:

  scp -O -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa <文件> ecu@192.168.1.234:<路径>

(连接时的 "post-quantum key exchange" 警告是无害提示,可忽略。)

嫌每次带参数麻烦,可在构建机 ~/.ssh/config 固化密钥参数(注意 -O 是命令行开关,
config 里没有对应项,scp 仍需带 -O):

Host ecu500
    HostName 192.168.1.234
    User ecu
    HostKeyAlgorithms +ssh-rsa
    PubkeyAcceptedAlgorithms +ssh-rsa

配好后:scp -O opc2ecu-imx6ul-armhf.tar.gz ecu500:/tmp/

---

## 四、部署(ECU 上执行)

### 4.1 首次部署

cd /tmp && tar xzf opc2ecu-imx6ul-armhf.tar.gz -C /home/ecu && chown -R ecu:ecu /home/ecu/opc2ecu

预期:/home/ecu/opc2ecu/ 下出现 bin/ config/ lib/ runtime/ 四个目录。

### 4.2 部署验收脚本(verify-opcda.sh,打包里没有,单独传)

scp -O -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa ~/opcda-deploy/verify-opcda.sh ecu@192.168.1.234:/home/ecu/opc2ecu/

(构建机上没有就先把 ~/opcda-deploy/verify-opcda.sh 一并转过去)

### 4.3 注册 systemd 服务(可选,联调阶段可先手动跑)

在 ECU 上创建 /etc/systemd/system/opc2ecu.service:

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

启用:

systemctl daemon-reload && systemctl enable --now opc2ecu.service

### 4.4 升级(只换 jar,不动配置)

systemctl stop opc2ecu.service
scp -O -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa <新包里的>opcda-probe.jar ecu@192.168.1.234:/tmp/
install -m 0644 /tmp/opcda-probe.jar /home/ecu/opc2ecu/lib/ && chown ecu:ecu /home/ecu/opc2ecu/lib/opcda-probe.jar
systemctl start opc2ecu.service

回滚:用备份 jar 覆盖 lib/ 后重启。

---

## 五、验收

### 5.1 离线自检(不需要 Windows,先确认包没坏)

export OPC_PASSWORD=***
sh /home/ecu/opc2ecu/verify-opcda.sh

看输出前两行 PASS(Phase 1 离线自测)。密码错误也无所谓,Phase 1 不联网。

### 5.2 在线连接验收(需要 Windows + Matrikon 在线,192.168.1.2)

export OPC_PASSWORD=***
sh /home/ecu/opc2ecu/verify-opcda.sh

预期:RESULT: PASS=6 FAIL=0
- Phase 2 用生产配置 points.json 验收(--precheck-points):连接 + 逐点位读取
- 判据是 [CONNECT] Connected(连接成功);个别点位 FAIL 是点表问题(如 Matrikon
  Sine 波形默认关闭),不影响链路结论
- 注意:若需探测模式(--list-server/--list-items,走 OPCEnum),Windows 侧需同时
  给 OPCEnum 组件配好 DCOM 权限(生产采集直连 Matrikon,不依赖 OPCEnum)

### 5.3 数据面(联调阶段,可选,--collect)

sh /home/ecu/opc2ecu/verify-opcda.sh --collect

预期:UDP capture non-empty(5353 抓到 30B 记录流)+ [START] 行。

---

## 六、失败时看什么([DIAG] 归因解读)

连接失败时,程序会先输出一行 [DIAG],直接定位故障层:

[DIAG] code=OPC_E_DCOM_ACCESS_DENIED layer=dcom hresult=0x80070005 detail="..." hint="..."

| code | layer | 含义 | 处理 |
|---|---|---|---|
| OPC_E_DCOM_ACCESS_DENIED | dcom | DCOM 权限或账号密码错 | mmc comexp.msc /32 两个组件加账号;核对 domain/user/password |
| OPC_E_RPC_SERVER_UNAVAILABLE | rpc | RPC 服务不可用 | Matrikon/OPCEnum 服务没起;TCP 135 被拦 |
| OPC_E_RPC_ENDPOINT_NOT_FOUND | rpc | RPC 动态端口被拦 | 放行 49152-65535 或固定 RPC 端口 |
| OPC_E_CLASS_NOT_REGISTERED | registry | ProgID/CLSID 未注册 | 核对配置;确认 32 位组件视图可见 |
| OPC_E_AUTH_OR_NETWORK_TIMEOUT | auth | 挂起超时:鉴权失败或网络黑洞 | 先核对密码/域;再看防火墙是否丢包 |
| OPC_E_UNKNOWN | generic | 无法归因 | 开 debug 日志看完整栈:java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar ... |

退出码速查:0 成功 / 2 配置错误 / 3 连接失败 / 4 读取或预检失败 / 5 采样超时。

---

## 七、操作顺序速记

1. mvn clean package(123 测试绿)
2. build-imx6ul-bundle.sh 打部署包(19MB)
3. 转包:开发机 → 构建机 → ECU /tmp
4. ECU:解包到 /home/ecu + chown + 传 verify 脚本
5. export OPC_PASSWORD && sh verify-opcda.sh,目标 PASS=6 FAIL=0
6. 通过后决定是否装 systemd 服务托管
