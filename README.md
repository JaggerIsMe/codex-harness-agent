# Harness Agent

Harness Agent 通过主动 WSS 连接接受 Harness Server 指令，并在项目专用执行环境中驱动 `codex app-server`。

支持现有 Windows 上直接运行 Agent，通过 LPAC 隔离模型代码，不需要虚拟机。运行时配置和兼容性见 [Windows 原生隔离](../../docs/windows-agent-isolation.md)。[Linux 容器/虚拟机](../../docs/linux-agent-isolation.md) 是另一个部署选项，当前开发环境尚未完成 Linux 实机验收。

图片查看、本机 Codex 生图、补丁编辑和结构化命令通过受控工具恢复，原生工具开关仍关闭。新增工具需要升级 Agent 后新建会话；第三方生图、Maven 和多 Agent 的当前限制见 [受控工具恢复与验证](../../docs/controlled-windows-tools.md)。

## 权限与执行

- Java 21、Maven 3.6.3+；容器镜像固定 Codex 0.153.0。
- Linux 每个 Thread 使用独立 named permission profile；Windows 关闭原生本地执行工具，通过独立 Python 运行时和 LPAC 执行。只读运行时与当前 Workspace 为授权范围，命令禁网，不继承 Agent 凭证环境变量；元数据目录移除继承写授权并设置只读权限。
- Agent 在注册前进行真实访问自检，Windows 通过后上报 `WINDOWS_LPAC_V1`，Linux 通过后上报 `LINUX_PROJECT_PROFILE_V1`；失败停止启动。
- Server 同时支持上述 Windows 和 Linux 能力，不再将旧 `WINDOWS_PROJECT_PROFILE` 视为读取隔离。Windows 当前固定验证 Codex 0.153.0，需要配置 `windows-python` 专用运行时。
- 容器配置的 `max-workspaces=1` 保证独占分配，重启和相同请求重试不会创建第二个项目。裸 Linux 虚拟机部署也应为每个项目使用独立 Agent、工作目录和操作系统边界。
- 每个会话有独立 App Server 进程，恢复时校验持久化 Thread 的 Workspace 归属；支持流式事件、中断和幂等重试。

容器入口使用独立的 `deploy/application-container.yml`，不会加载源码中的旧 Windows 开发配置。`deploy/compose.yml` 不挂载宿主机私人目录或 Docker socket，不使用 privileged；如果内核或运行时阻止 sandbox，应配置受支持环境，不得绕过自检。

## 注册与状态

按部署文档设置 `HARNESS_SERVER_URL`、`HARNESS_ENROLLMENT_URL`、`HARNESS_ENROLLMENT_CODE` 与可选的 `HARNESS_DEVICE_NAME`。远程地址必须使用 WSS/HTTPS。首次注册取得的 Device 身份保存在容器专用 Agent 状态卷；每个容器使用不同注册码和 Compose project name。

平台为 Device 分配运行模型及对应用户后即可创建 Project。升级 Windows LPAC 后，原 Project 和文件保留，旧 Conversation 只保留历史，需要在原项目中新建会话使用新工具；新模式创建的会话可正常恢复。详见 [ADR 0018](../../docs/adr/0018-linux-project-read-isolation.md)。

## 执行确认

Turn 使用 `approvalPolicy=on-request`。Agent 拒绝原生命令/文件扩权审批，并通过 `approvalBlocked` 消息展示平台拦截原因。用户要求执行前确认时，运行指令引导模型通过 `request_user_input` 发起 `harness-confirm-action`，前端提供批准本次、拒绝操作、拒绝并中断。批准只回复工具输入，不改变权限；不支持本会话批准。

`features.default_mode_request_user_input` 在 Codex 中仍属于开发中功能。执行确认由模型选择工具，不是每条命令的强制拦截。确认协议和严格读取隔离应分别验证，见 [审批验收](../../docs/testing/approval-flow.md) 与 [Linux 隔离验收](../../docs/linux-agent-isolation.md)。

受管 Expert 禁用本机 Apps/Plugins 继承，只显式加载授权 Skill 和 MCP，并检查 MCP 白名单；外部 MCP 的权限仍由对应服务控制。

中台协议见 [Harness Agent API](../../docs/harness-agent-api.md) 和 [OpenAPI](../../docs/harness-agent-openapi.yaml)。

## Workspace 文件与消息附件

新文件通过项目工作区目录树上传和下载，对话框附件默认上传到工作区根目录。Agent 不再注入输出清单约定，也不再扫描 manifest 或收集 Turn 产物快照。接口、限制和升级步骤见 [Workspace 文件方案](../../docs/workspace-files.md)。

旧 Artifact 补传队列和配置已移除；消息附件只校验并读取 Workspace 文件，不再从平台下载到隐藏附件目录。升级后的旧队列清理步骤见 [Workspace 文件方案](../../docs/workspace-files.md)。

工作区文件操作 V1 增加重命名、单文件移动、检查后删除和多选 ZIP。Agent 的 Windows 实现使用 JNA 的句柄操作：禁止覆盖目标、固定父目录与源对象，并用卷身份和完整 128 位文件 ID 生成 `entryRevision`。没有安全句柄支持的平台不声明 `WORKSPACE_FILE_MUTATIONS_V1`；文件系统不能提供可靠身份时明确拒绝。ZIP 使用 `WORKSPACE_ARCHIVE_DOWNLOAD_V1`，Windows 以固定路径句柄读取，其他平台要求 `SecureDirectoryStream`。

所有层级的 `.codex`、`.git`、`.harness`、`.agent`、`.agents`、`.harness-workspace.json` 和 `.harness-upload-` 临时前缀统一隐藏并保护。目录重命名完整复核子树，目录删除按确认清单逐项执行；平台管理的 Turn 与变更互斥，收到中断确认后仍等实际执行结束再释放。操作无法确认或进程不能确认停止时保留占用，禁止自动重试变更。此协调不锁住外部程序新建文件，目录重命名不承诺外部写入的原子子树快照。

本机 ZIP 默认最多 100 个普通文件、源合计 100 MiB、输出 110 MiB，单个源沿用 `max-attachment-bytes`（20 MiB）。对应配置为 `workspace-archive-max-files`、`workspace-archive-max-total-bytes`、`workspace-archive-max-output-bytes`；Server 取两端较小限额。ZIP 保留 UTF-8 工作区相对路径，条目 comment 保存源 SHA-256，整个 ZIP 通过 SHA-256 传输校验。

删除计划放在 Agent 数据目录 `workspace-delete-plans`，有效 120 秒且不跨重启；临时计划和过期传输字节每分钟进行有界清理，传输残留保留最多约 24 小时。`workspace-action-journal` 保存请求摘要和结果，不可通过删除该目录来解除 UNKNOWN；核实命令只重放已持久化结果，绝不再次执行文件修改。详细协议见 [文件操作扩展方案](../../docs/workspace-file-actions.md)。

## 验证与启动

```powershell
java -version
mvn -version
mvn clean verify
java -jar target/harness-agent-1.0.0-SNAPSHOT.jar
```

测试覆盖协议校验、命令去重、会话并发、工作区逃逸和 Skill 安装安全校验。

Agent 不访问数据库，因此不引入 MyBatis、MySQL 或 Redis 客户端。WebSocket 连接使用 Spring 6 的 `execute` / `CompletableFuture` API；继续使用非 Web 进程保活。Windows 隔离命令在受限数量的虚拟线程中等待，进程树由 Windows Job 管理。

测试 JVM 的 Mockito agent 和模块内临时目录由 POM 配置，不必额外传 `argLine`。IDE、Maven Runner 和运行 Agent 的服务/终端须统一使用 JDK 21；旧的全局 `jdk-1.8` Maven profile 需在本机设置中调整。

2026-09-04 升级验证：`mvn clean verify '-Dcodex.smoke=true'` 通过，40 项测试成功、1 项符号链接能力测试按环境条件跳过。包含真实 Codex 初始化/建会话（不发送模型请求）、Jakarta 校验、异步重连、关闭后迟到握手和进程树清理测试。升级验证发现并修复了 Windows `.cmd` 包装进程退出后子进程遗留的问题：先关闭 stdin，再等待并清理该 Agent 自己启动的进程树。可执行 JAR 已生成，入口字节码 major version 为 65（Java 21）。

## 新建会话失败排查

创建项目只操作工作区目录；首次新建会话才启动 Codex App Server。项目创建成功不能证明 Codex 能正常启动。

如果日志出现 `Codex App Server exited with code 1`，查看同条日志中的 `stderr` 摘要，以及前面的 `Starting Codex App Server with command`，确认实际调用的程序路径和工作目录。摘要保留最近的诊断，并对常见密钥、令牌和密码字段脱敏。请求失败信息也会包含底层错误。IDE 和终端的 PATH 可能不同，必要时将 `harness.agent.codex-command` 配置为已验证的 Codex 可执行文件或 `.cmd` 包装器的绝对路径。

可在 Agent 模块目录中显式运行本机 Codex 验证（需要已安装并配置 Codex，默认测试不会执行）：

```powershell
mvn '-Dcodex.smoke=true' '-Dtest=CodexAppServerSmokeTest' test
```

该验证通过实际启动命令完成初始化，并通过 Agent 适配器创建一个测试会话，不发送模型请求。测试会话会由本机 Codex 保存。

## Agent 重启后已有会话无法续聊

2026-09-05：若首条消息从未成功执行，重启后报 `thread not loaded`，需同步更新 Server 与 Agent，支持后端授权的空线程重新初始化。原会话可重发消息，不用删历史或修改数据库；已有模型历史不会自动重建。详见 [空线程恢复说明](../../docs/agent-empty-thread-recovery.md)。

若发送消息立即失败并出现 `default_permissions requires a [permissions] table`，更新到当前 Agent 后重启即可。已修复轮次级权限名称覆盖导致 Codex 丢失线程内联权限表的问题；每轮继承启动/恢复线程时已验证的权限，继续维持项目隔离。无需改数据库或手工修复 Redis，失败消息可在原会话重发。完整复现与验证见 [验收记录](../../docs/user-device-rbac-verification.md)。

旧实现仅在内存中保存 Conversation 与 Codex Thread 的映射，重启后已有会话会报 `CONVERSATION_NOT_STARTED`，即使 Server 返回的 Conversation 仍为 `ACTIVE`。当前实现要求 Server 的 `START_TURN` 同时携带数据库中的项目 ID、工作区名称和 Codex Thread ID；Agent 会在缺失映射时核验原 Thread 的真实目录并恢复它，再发送新 Turn。

更新并重启 Server 与 Agent 后，在原 Conversation 重试即可，不需要新建会话或修改数据库。之前失败的 Turn 保留为失败，不会自动重发。原 Thread 文件丢失、工作区变化或已有活动 Turn 时恢复会明确失败，不会静默创建新 Thread。

如果恢复返回 `already has an active writer`，说明同一 Codex Thread 已被另一个 App Server（例如使用相同 Codex 用户目录的桌面应用）占用。先在占用方释放该 Thread，再重试；不要删除写锁文件或并发写入同一 Thread。真实恢复验证须使用实际 Agent 的 Windows 用户及 Codex 用户目录，隔离测试账户的空历史不能用于验证其他用户的会话。

可显式选择一个已有且空闲的本机 Thread，验证真实 App Server 的读取与恢复协议（不发送模型请求）：

```powershell
mvn '-Dcodex.smoke=true' '-Dtest=CodexAppServerSmokeTest#resumesExistingStoredThreadWithoutSendingModelRequest' '-Dcodex.resume.thread=<已有Thread ID>' '-Dcodex.resume.workspace=<原工作区绝对路径>' test
```

恢复使用官方 [Codex App Server 的 thread/read 与 thread/resume 协议](https://learn.chatgpt.com/docs/app-server)。恢复前后都会校验 Thread ID 和目录；执行 Turn 时继续使用项目权限 profile、命令禁网与 `approvalPolicy=on-request`，并重新配置执行前确认能力。
