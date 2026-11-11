# 优化验证记录（2026-11-11）

## 本机验证

| 检查 | 结果 |
|---|---|
| Java 回归与打包 | 允许本机监听的环境执行 `mvn -o clean package`：175 tests，0 failures/errors/skips，BUILD SUCCESS；无 shutdown hook 空指针。 |
| 打包脚本语法 | `sh -n scripts/build-imx6ul-bundle.sh` 通过。 |
| 打包权限与失败行为 | `python3 scripts/test-build-imx6ul-bundle.py` 通过。虚构凭据；配置源权限 644 时归档内强制 600，输出归档 600，启动器可执行，默认/显式 JRE 路径均通过，替换旧包，失败不覆盖旧包且清理临时输出。 |
| 协议自检 | `java -jar target/opcda-probe.jar --self-test-protocol` 通过，30 字节、每包最多 48 条、小端固定向量保持一致。 |
| 差异空白检查 | `git diff --check` 通过。 |

新增并发测试使用 latch 确认停止已传入工作线程后才释放模拟 RPC，覆盖忽略中断的在途连接/绑定；验证关闭等待调用返回、候选清理且不发布恢复状态。它不证明第三方真实 RPC 可被中断。

## J-Interop 退出异常定位

沙箱内运行测试时，测试计数通过，但退出出现 `jI_ShutdownHook` 空指针异常。检查本地依赖 `org.openscada.jinterop.core-2.1.8.jar` 字节码：

- `JISession` 静态初始化异步启动 resolver 并注册 shutdown hook。
- resolver 调用 `ServerSocketChannel.open()` 和 `bind()`，捕获 IOException 后退出。
- `stopResolver()` 直接调用 `serverSocket.close()`，只捕获 IOException，未检查 null。
- 在允许本机监听的环境运行完整构建，未复现退出空指针。

这些证据支持沙箱限制触发 resolver 启动失败的解释；未进行生产环境复现，不把依赖修改列为本轮修复。

## 审核与现场门禁

- Hermes 审核：用户明确决定本轮跳过；未执行审核，不代表审核通过。
- 用户代码审核：用户明确决定本轮跳过（未执行，不代表审核通过）；合入决定仍未作出。
- ECU 设备部署及运行验证：用户决定直接进入此阶段，由用户执行，未进行；步骤与产物摘要见 ecu-validation-2026-11-11.md。
- 真实断网恢复、SIGTERM 退出时限、连续运行资源数据：待现场验证。沿用部署手册操作，记录在途网络调用耗时，不能以 fake 测试替代。
- 心跳应答源 IP/端口及单点独立转发语义：用户已确认，相关实现和测试见下；ARM 性能收益仍待实测。

## 后续确认：单点隔离及心跳来源

用户要求某点故障不影响其他点采集转发，并确认心跳来源。最新完整构建：175 tests，0 failures/errors/skips；本机回环测试通过。规格见 dev-spec-point-isolation.md，替代全表收齐才发送的旧规则。

- MultiItemCollectionTest：C 缺失时 A/B 连续转发、C 恢复参与且不重放旧值；新周期丢弃未完成旧周期、同点取本轮最新结果、非数值跳过与部分结果仍按 48 条拆包、旧连接回调丢弃、健康点持续回调不触发整连接重连。ReconnectManagerTest 另覆盖绑定失败后候选的全部迟到回调失效。
- IsolatedSyncAccessTest：失败/空状态和逐点转换异常隔离、相同 ItemState 逐轮交付、批量转换异常退回逐点读取、共同 RPC 故障沿原连接路径传播、坏品质数值原样转发。
- UdpDatagramChannelTest：真实回环 socket 验证正确来源、错误源端口、相同源端口但错误 IP，以及持续错误报文无法延长本次接收预算。
- 正常路径仍一次批量读取；遇到 Utgard 批量结果转换异常才退回逐点读取，该异常路径的 RPC 数量会增加，现场需关注实际点数下的轮次耗时。
- 已绑定点位的读故障恢复可自动加入后续轮次；初始未成功注册的 ItemID 仍遵循 Utgard 的绑定生命周期，需要修正配置/重启或另行设计单点重新注册，不能把该场景宣称为已完成自动恢复。
