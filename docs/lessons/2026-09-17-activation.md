# 激活失败不能仅靠 HRESULT 文本归因

现场：地址纠正后，JIComServer.init -> JIRemActivation.read 返回 0x80010111。
旧日志丢失原码；新增映射后仍缺少实际请求和响应解析位置，无法排除编码/解析兼容性问题。
库消息缺失不等于库没收到错误码；解析器读出的 HRESULT 也不能单独证明服务器发包非法。

措施：在固定版本实际编码/解析路径加入可关闭、字段白名单、限长诊断；
用真实编解码测试和原库对照验证不改业务行为；现场成功/失败环境对比为合入门禁。
参考开发门禁缺陷案例：~/iot-gateway-planning/docs/lessons/2026-08-21-ping-count-timeout.md。

## 16:52 ECU 诊断证据

请求：COM 5.2，flags/reserved/extensions=0，mode=0，impersonation=3，
IUnknown+IDispatch，protseq=7，stubBytes=128。响应：ORPCTHAT 前8字节为0，
bindings pointer 空（offset16..20），authHint=0，COM5.2，offset44字节11010180。
该布局与无绑定的 RemoteActivation 响应字段位置一致；仍非独立抓包验证。

源码进一步发现：声明单个 protseq7 后，原库额外写入本机 IPv4 四个 ushort 和终止 ushort
（10字节，最终128字节）；官方 RemoteActivation IDL 输入在 aRequestedProtseqs 数组结束。
这是值得隔离验证的旧库编码疑点，不是已证实根因；不据此直接删除或改变默认请求。
官方规范：https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-dcom/cf56f36e-a87e-49b4-9729-ebb8a07054fb
COM5.2是规范支持的版本，不能仅凭版本旧判为错误。AUTH3/ALTER_CTX_RESP不证明所有权限检查通过。

## 17:08/17:09 ECU A/B 结果

同一opcda-probe-ab.jar，CLSID7bc0cc8e-482c-47ca-abdc-0fe7f9c6e729：
A(false) stubBytes128；B(true) stubBytes118，实验开关确实生效。
两次可见响应字段一致：capacity76、ORPCTHAT零头、bindings空、authHint0、COM5.2、
HRESULT偏移44/11010180，均在response-hresult失败。
结论：省略尾部10字节不足以解决该现场错误，不支持其为唯一触发原因；默认编码继续保留。
不能据字段相同宣称完整网络响应逐字节相同；尚无RPC实际安全封装/协商证据。
