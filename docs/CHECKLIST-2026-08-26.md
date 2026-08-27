# 操作清单(2026-08-26)

接续:docs/STATUS-2026-08-25.md 待办 1-3。本文件给 ECU 侧操作命令
(由用户执行),全部单行可直接拷贝粘贴。

## A. 换新 jar + verify 复跑(待办 1+2)

本机 jar 已确认含 ReconnectManager 修复(4db541a,17:09 提交 / jar 17:10 构建),
无需重建。verify 脚本取仓库版(61c1916)。

A1. 传新 jar 和仓库版 verify 脚本到 ECU(本机执行):

scp -O -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa target/opcda-probe.jar ecu@192.168.1.234:/tmp/

scp -O -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa scripts/verify-opcda.sh ecu@192.168.1.234:/home/ecu/opc2ecu/

A2. 停服务 → 替换 jar → 启动(ECU 上执行):

systemctl stop opc2ecu.service && install -m 0644 /tmp/opcda-probe.jar /home/ecu/opc2ecu/lib/ && chown ecu:ecu /home/ecu/opc2ecu/lib/opcda-probe.jar && systemctl start opc2ecu.service

A3. 确认服务起来、采集恢复(ECU 上执行,预期 12 item 在采):

systemctl status opc2ecu.service --no-pager | head -n 5

A4. 第一次 verify(ECU 上执行;OPC_PASSWORD 填 opcuser 的密码):

export OPC_PASSWORD='<opcuser密码>' && sh /home/ecu/opc2ecu/verify-opcda.sh

   预期:RESULT: PASS=8 FAIL=0
   (8 项 = JRE executable / jar present / self-test-protocol /
   check-config / list-server / list-items / read 10 samples /
   wrong-password exit 3;--reconnect 与 --collect 未带,显示 SKIP 属正常)

A5. 间隔 1-2 分钟再跑第二次,确认稳定 PASS=8 FAIL=0。

判读标准:
- 两次均 PASS=8 FAIL=0 → 修复版确认,待办 1+2 闭环
- 有 FAIL → 把 FAIL 行 + 对应 /tmp/opcda-p*.log 尾部 5 行贴回来
- 服务起不来 → 贴 systemctl status 输出

## B. 5353 数据面联调(待办 3,等契约确认)

前置确认项(需 C++ 侧拍板,不在本机):
1. 数据面协议 v1.2 docx 的 record 布局与驱动实现是否一致:
   30B/条 = MD5 16B + double 8B(小端)+ uint32 时间戳 4B(小端)+ uint16 quality 2B(小端),
   每数据报最多 48 条(1440B)
2. MD5 输入 = Item ID 原文(无拼接),charset 默认 UTF-8(v2.1 契约,
   驱动侧 UdpRecordSender/EcuRecordCodec 已按此实现,本机已核对一致)
3. 接收端(5353)是否已部署、按契约接收

确认后联调命令(ECU 上执行):

export OPC_PASSWORD='<opcuser密码>' && sh /home/ecu/opc2ecu/verify-opcda.sh --collect

   预期:[START] 出现 + "UDP capture non-empty: N bytes"
   (nc 捕获 5353 收包,od 打印前 64 字节供人工比对)

## C. 待用户拍板的小项(本机)

1. --self-test-protocol 输出文本 "MD5 input=Server.Group.Item charset=US-ASCII"
   是旧协议时代描述,与 v2.1 契约语义相悖(应为 Item ID 原文 + md5Charset)。
   自检向量本身是固定字节回归,不受影响。建议:改描述文本,向量保留。
   (改动前先确认)
2. .vscode/ 未跟踪,建议 .gitignore 加一行 .vscode/
