# AGENTS.md — i.MX6UL Linux OPC DA Client

Linux + Utgard/J-Interop → Windows OPC DA Server → OPC2ECU UDP 最短链路验证项目。不依赖 Wine,不修改 Windows/MFC 工程。

## 构建与测试

```bash
mvn clean package        # 构建(JDK 8+, Maven 3.6+;产物 target/opcda-probe.jar 等)
mvn test                 # 测试套件(当前 111 个,全绿)
scripts/build-imx6ul-bundle.sh   # ARM 部署包打包(target/opc2ecu-imx6ul-armhf.tar.gz)
```

- 目标字节码:Java 8
- 工具链:JDK17/Maven3.9 在 ~/.local/bin
- 首次构建需访问 Maven Central(代理兜底:export HTTPS_PROXY=http://127.0.0.1:7890)

## 配置与契约

- `config/points.json` — 生产采集与点位预检配置。**含明文密码,权限 600,已 gitignore,不得提交、不得写入日志**
- `config/opc.properties` — 探测模式(默认读取/枚举/清单导出),密码不落盘,通过 `export OPC_PASSWORD='...'` 提供
- 契约文档:`docs/points-config-format.md`(v2.1:文件强制 UTF-8、md5Charset 默认 UTF-8、items=ItemID 原文无 group 字段、密码明文 600)。**修改契约需先经用户确认**
- 热重载未定案,当前配置修改重启生效

## 关键文档

- `docs/opc2ecu-deployment.md` — opc2ecu 部署(Yocto 集成、systemd、运行需求)
- `docs/opc-server-windows-deploy.md` — Windows OPC DA Server 远程 DCOM 部署(测试用)
- `docs/STATUS-2026-08-20.md` — 状态与待办(契约确认、5353 接收端部署)

## 工作约定

- 分支 `feat/precheck-points` 为当前工作基线;里程碑合入 ≠ 整体完成,分支保留
- 迭代流程:Codex/本机开发 → Hermes 审核 → **用户亲自审代码后拍板合入**,不得自行合入 main
- **设备(ECU 192.168.1.234)操作由用户自己执行**:只给命令清单 + 预期输出 + 判读标准,不自动 scp/部署
- 开发门禁:新功能/规格变更走 dev-gate-checklist 流程(规格模板 + 缺陷案例库)
- 提交规范:`type: 摘要`(fix/feat/refactor/docs/chore)
