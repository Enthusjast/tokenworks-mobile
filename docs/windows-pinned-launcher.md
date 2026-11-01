# Windows DPAPI 摘要固定启动器

`tools/native/windows/dpapi-launcher.c` 在启动目标 helper 前核对可信配置中的 SHA-256。
这是原生启动边界，尚未接入 Node provider、签名安装或 Windows 日志文件发布流程。
`loadProtectedKey` 的 Win32 拒绝保留；本批不能作为 Windows 日志上线依据。

## 调用与信任来源

```text
dpapi-launcher.exe <绝对 helper 路径> <64 位小写 SHA-256> <操作>
```

操作仅接受 `journal-protect-v1`／`journal-unprotect-v1`，数据仍沿用
[DPAPI 二进制管道协议](windows-dpapi-primitive.md)。参数不包含密钥、context 或密文。
路径和摘要必须由可信安装配置提供，不能从远端命令或 helper 自报信息取得。

启动器自身必须由可信启动端／安装来源保障；它的自检不能认证自身，也不能认证进入入口前
加载的 DLL。本实现没有消除信任引导问题，没有把测试构建摘要当成签名发行清单。
Node 接入、辅助程序及系统依赖来源、升级与回滚均须独立完成。

## 启动边界

- 启动器和 helper 都检查当前用户及安装文件 owner／DACL；共享
  [安装文件门禁](windows-helper-file-guard.md)。链路改为从盘根逐层打开、检查并保留句柄，
  再打开映像；当前只接受固定磁盘的规范 drive-letter 路径。
- 路径拒绝 UNC／扩展前缀、相对段、空组成部分、尾随点／空格、控制字符、额外冒号及命令
  引号等特殊字符；不把未知输入先规范化为另一条路径。支持组成部分中的普通空格与 Unicode。
- 从最后一个检查句柄读取最多 16 MiB 的非空映像，以 BCrypt SHA-256 核对摘要。检查句柄
  只共享读取，在子进程结束前保留；写入／删除打开与额外硬链接仍受门禁约束。
- 摘要和路径通过后才复制 stdin／stdout／stderr 管道。`PROC_THREAD_ATTRIBUTE_HANDLE_LIST`
  明确限定继承句柄；其它父进程句柄不传给 helper。不使用 shell 或 PATH 搜索可执行程序。
- 子进程工作目录来自 GetSystemDirectoryW。环境仅包含系统 PATH、SystemRoot、WINDIR，
  不继承调用者的加载器、代理、运行时注入或其它环境变量。
- CreateProcessW 以挂起状态创建子进程；先加入具有 KILL_ON_JOB_CLOSE 和单进程限额的
  私有 Job Object，再恢复主线程。设置／分配失败时终止挂起子进程，不继续读取请求。
- 每次调用最多等待 10 秒，超时终止进程及 Job；启动器死亡关闭 Job 时也要求回收子进程。
  该限额不是 DSH 整体请求超时。句柄、进程、Job 和属性列表均在退出路径清理。

这些门禁保守拒绝链路上任何不可信主体的写权限，以及未列入白名单的 owner；常见系统盘
默认 ACL／TrustedInstaller owner 是否适配仍须目标安装验收。不能从 Wine 私有目录可运行
推导任意 Windows 安装路径可运行。Job Object 限额也不构成任意不可信程序的完整沙箱。

## 本地证据

`tools/integration/run_windows_dpapi.py` 使用原有隔离 Wine 前缀，并在私有 C: 目录安装
helper／launcher。MinGW 严格构建覆盖共享头和五个程序，summary 记录源码与二进制摘要。
所有原 DPAPI 往返／负面协议检查改经固定摘要启动器执行。

`.local/windows-pinned-launch-inheritance.log` 记录 35 个检查组／场景通过，包括错误／不同
映像摘要、非法路径／操作在未发送 stdin 时拒绝，中文及空格路径往返，最小环境和系统工作
目录，标准管道，故意传入启动器的额外可继承 event 不能进入 helper，以及真实超时退出。
父进程终止检查先等待子程序就绪，再只杀启动器；驱动保持 stdin 写端打开并观察子程序
stdout EOF，避免用关闭 stdin 误证明 Job 回收。

单列三项 Wine 未验证能力：

1. 文件对象单独 WRITE_DAC ACE 被模拟层丢弃；合成描述符拒绝测试仍通过。
2. QueryInformationJobObject 的限额回读为零。
3. ActiveProcessLimit 没有阻止实际后代创建。夹具只以 CREATE_SUSPENDED 创建探针，并立即
   终止，不执行后代；生产启动器保留限额设置，不能把 Wine 行为视为 Windows 已通过。

`.local/windows-pinned-launch-host-verify-ready.log` 记录 Host 495 项全部通过，包括实际
npm dsh 0.2.1-rc.2。首次验证工作目录缺少 node_modules 链接导致七项失败，修复测试目录
后重跑通过；未修改产品测试来规避这些失败。真实 Windows acceptance 始终为 false。
