Windows 0.3.0.3：修复读取清单时误报同步冲突。手机 APK 无需更新。

官网全量读取（optId=-1）可以返回 differenceData 标记，官网此时不将它作为保存失败处理。连接端之前在读取和读回阶段错误地把它判为冲突，导致测试未提交；本次修正全量读取检查，仍检查响应结构、授权状态和记录字段，提交写入时仍拒绝真正的同步冲突。

32 项服务和协议测试通过，包括全量读取带该标记时能提交并读回、重试不重复写入、实际提交冲突仍拒绝确认成功。Windows CI 另行验证实际 CMD、Edge 启动和服务 HTTP。测试使用模拟响应；真实账号和手机同步仍需本地验收。

更新：停止电脑服务和番茄窗口，备份 data，将 ChatPlanner 内程序文件覆盖到原目录，保留 data 和 .venv。重新启动 windows/start-assistant.cmd，再运行 windows/test-tomato.cmd；登录官网后进入清单页，回终端按回车。

操作说明：https://github.com/linyubo0614/vibecoding_test_01/blob/main/docs/windows.md
