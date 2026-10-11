Windows 启动修复包；手机 APK 无需更新。

修复番茄测试/同步启动器的编码和换行兼容性；失败会显示启动阶段、退出码并等待按键关闭。增加 Edge 无法启动、官网访问失败和依赖缺失提示。

该 ZIP 来自提交 fe1b1c0d15d3a800027a80c5ebf40f242bf640ec 的 Windows CI 成功产物，已验证实际 CMD 启动、带中文和空格的路径、Edge 打开、缺少环境提示，以及 29 项服务测试。浏览器检查不访问账号或写入记录；真实账号写入仍需在自己电脑授权验证。

更新：停止电脑服务和番茄窗口，备份 data，将 ChatPlanner 内的程序文件覆盖到原目录，保留 data 和 .venv。重新启动 windows/start-assistant.cmd，再运行 windows/test-tomato.cmd。不要同时打开测试和同步窗口。

操作说明：https://github.com/linyubo0614/vibecoding_test_01/blob/main/docs/windows.md
