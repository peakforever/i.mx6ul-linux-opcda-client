# 迭代 1 验收记录

日期：2026-08-20

## 已完成

- 增加 `OpcDaClient` 抽象及 `UtgardOpcDaClient` 实现，CLI 不再直接持有
  Utgard 的 Server、Group 或 SyncAccess。
- 增加 STOPPED / CONNECTING / CONNECTED / RECONNECTING 状态机。
- 断线后销毁旧客户端并通过工厂创建全新 JISession、Server、Group 和 Item。
- 增加 1 秒起步、2 倍增长、30 秒封顶、±20% 抖动的重连策略。
- 增加回调看门狗、采样缺口统计、异常分类和退出码。
- 诊断信息统一使用 slf4j；stdout 保留机器可读关键行并对账号信息脱敏。
- 增加 JUnit 4.13.2 与 Maven Surefire 2.22.2 测试基建。

## 自动化验证

开发机环境：Temurin JDK 8u502、Apache Maven 3.9.11、Linux amd64。

```text
mvn clean test
Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试覆盖协议固定向量与 uint 边界、配置解析、JSON 转义、退出码、指数退避与
抖动，以及 fake OPC 驱动下的初次连接失败、Item 绑定失败、断开后恢复、持续
失败封顶、看门狗、同步读失败后重连、Item 重建和缺口计数。

兼容冒烟项目：

- `--self-test-protocol`
- `--check-config`
- Java 8 字节码检查
- Fat JAR 打包
- 源码及日志凭据扫描

## 待现场验收

- Windows + Matrikon 环境中读取时断网 30 秒再恢复，确认自动重连和 `[GAP]`。
- 错误凭据确认退出码 3，且进程不挂死。
- 连续 10 次重连失败，确认实际等待符合退避范围。
- 重复 connect/disconnect 50 次，记录 i.MX6UL RSS 是否稳定。
- 在目标 ARM JRE 上再次运行 `--self-test-protocol`。
