# 代码审查记录:原型基线

- 审查:2026-08-20(Hermes)
- 基线:commit 2885b1a
- 方式:读代码 + mvn clean package + 离线自检(--self-test-protocol、--check-config)

## 结论

原型质量高于 POC 平均水平,链路已验证到实机(见 README 与 docs/imx6ul-porting.md)。
可以此为基础进入迭代开发。发现的问题均为"缺口"而非"缺陷",无阻断性 bug。

## 优点

- 安全意识好:密码仅环境变量、NTLM Packet Integrity 反射修正(注释清楚)、
  导出 JSON 不含凭据、错误路径 try/finally 释放
- EcuRecordCodec 边界校验完整(长度/范围/字符集),固定向量自测可离线回归
- 配置解析防御性好(必填/正整数/模式差异校验)
- 移植文档(ABI 审计、Runtime 方案 A/B、分阶段验收、资源实测)非常扎实

## 缺口(按优先级)

| # | 缺口 | 影响 | 处理 |
|---|------|------|------|
| G1 | 无断线重连 | 生产采集不可用(文档已标"必补") | 迭代 1 T3 |
| G2 | 零单元测试 | 回归无保障,重构风险高 | 迭代 1 T1 |
| G3 | 写操作未实现 | 依赖客户需求,未定 | 待确认 |
| G4 | 多 Item 采集+UDP 发送缺失 | 原 Windows 程序替代品未完成 | 迭代 2 |
| G5 | 异常场景无系统化处理/退出码 | 现场排障靠人肉 | 迭代 1 T4 |
| G6 | println 裸日志 | 生产诊断不足 | 迭代 1 T5 |
| G7 | 配置为单 properties | 点位表格式待同事规范 | 迭代 3 |

## 技术观察

- pom 中 bcprov-jdk15on 1.50 排除/重定向处理正确(Utgard 1.5.0 引用不存在的
  1.50.0 版本)
- shade 过滤 META-INF 签名是 fat jar 的标准做法
- varTypeName/accessText 的 VARTYPE 映射表可扩展(迭代 2 写操作需要)
- OpcDaProbe 713 行单类偏大,迭代 1 抽 OpcDaClient 后自然缓解

## 验证记录

- mvn clean package:通过,opcda-probe.jar 3,952,325 字节
- --self-test-protocol:向量 199065ab...c000 一致,通过
- --check-config(example 配置):通过
