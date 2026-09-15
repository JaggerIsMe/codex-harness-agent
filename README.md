# Harness Agent

[项目文档总目录](../../docs/README.md) · [设备与执行环境](../../docs/modules/devices-agent.md) · [当前架构](../../docs/architecture/overview.md)

[交付架构与设计](../../docs/agent-distribution-v1.md) · [双平台开发手册](../../docs/guides/agent-development.md)

Harness Agent 通过主动 WSS 连接接受 Harness Server 指令，并在项目专用执行环境中驱动 `codex app-server`。

首版正式交付范围已确定为 **Windows 桌面安装包 + Linux 独立服务器安装包**，共用 Java Agent 核心；Linux 一台独立服务器运行一个 Agent、注册一个 Device，由 systemd 管理，允许在已授权父目录下创建多个 Project，每个项目独占 Workspace，与 Windows 项目组织一致。两类安装包尚未实现，Linux 多项目实机隔离验收尚未完成；范围与实施要求见 [首版交付方案](../../docs/agent-distribution-v1.md)。

现有 Windows Java 进程通过 LPAC 隔离模型代码，运行时配置和兼容性见 [Windows 原生隔离](../../docs/windows-agent-isolation.md)，Linux 策略与待验收项见 [Linux 隔离说明](../../docs/linux-agent-isolation.md)。旧 Docker 部署文件已移除；下文 Jar 命令是开发入口，不代表正式安装包已经交付。

图片查看、本机 Codex 生图、补丁编辑和结构化命令通过受控工具恢复，原生工具开关仍关闭。新增工具需要升级 Agent 后新建会话；第三方生图、Maven 和多 Agent 的当前限制见 [受控工具恢复与验证](../../docs/controlled-windows-tools.md)。

## 工程组织与开发状态

一个仓库共用 Java Agent 核心，现有 `pom.xml`、`src/` 保持原路径。建议在本模块内新增 `desktop/`（Vue/Tauri 桌面工程）和 `packaging/common`、`packaging/windows`、`packaging/linux`（发布组装与安装支持）；这些目录目前尚未创建。Linux 原生打包不依赖桌面构建，Windows 包组合核心、桌面与平台运行时；不复制第二份 Java Agent。

已有注册、连接、多项目登记、执行协调和部分清理实现；完整本地管理协议、Agent 级单实例锁、统一停止协调、外部发布配置、安装器与 systemd 集成仍待开发。具体接入点、现有命令及计划脚本分列在开发手册中，不能把方案中的路径或脚本当作现有命令。

## 权限与执行

- Java 21、Maven 3.6.3+；Codex 版本与平台验证范围见下方隔离约束。
- Linux 每个 Thread 使用独立 named permission profile；Windows 关闭原生本地执行工具，通过独立 Python 运行时和 LPAC 执行。当前 Workspace、按运行隔离的临时区、配置运行时及授权 Skill 目录构成精确权限范围；不继承 Agent 凭证。Windows 可配置 PUBLIC 公网出站访问或 DISABLED 禁网，Linux 当前仅支持 DISABLED。私有数据位于 Workspace 外。用户项目元数据目录可由隔离命令修改，Agent 不再创建占位目录。
- Agent 在注册前进行真实访问自检，Windows 通过后上报 `WINDOWS_LPAC_V1`，Linux 通过后上报 `LINUX_PROJECT_PROFILE_V1`；失败停止启动。
- Server 同时支持上述 Windows 和 Linux 能力，不再将旧 `WINDOWS_PROJECT_PROFILE` 视为读取隔离。Windows 当前固定验证 Codex 0.153.0，需要配置 `windows-python` 专用运行时。
- Linux 首版允许一个 Agent 下的多个项目工作区，原生安装包采用核心现有默认 `max-workspaces=0`（不设数量上限），可配置大于 1 的容量上限；该配置约束项目工作区总数，不是父目录数量或 Turn 并发数。每个项目独占 Workspace，重启和重试须保留原归属；安装包配置与多项目隔离验收仍待完成。
- 每个会话有独立 App Server 进程，恢复时校验持久化 Thread 的 Workspace 归属；支持流式事件、中断和幂等重试。

`deploy/` 仅保留 Windows 运行时与工具链准备脚本。原生安装包需提供独立于源码 Windows 开发配置的部署配置；如果目标系统阻止 sandbox，应配置受支持环境，不得绕过自检。

工作区外部私有存储、Skill 文本加载及其限制见 [Agent 私有存储架构](../../docs/architecture/agent-private-storage.md)。不提供旧运行数据迁移，不识别或搬移用户工作区内的 `.harness`、`.harness-workspace.json`。旧会话不迁移恢复，升级后在原项目下新建会话。

## 注册与状态

部署需配置中台连接地址、Enrollment 地址、一次性注册码和可选设备名称，远程地址必须使用 WSS/HTTPS。首次注册取得的 Device 身份保存在该实例独占的 Agent 数据目录，重启和升级继续使用原身份；正式安装包的配置入口按首版交付方案实施。

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

文件接口（不代表隔离命令 ACL）中，所有层级的 `.codex`、`.git`、`.harness`、`.agent`、`.agents`、`.harness-workspace.json` 和 `.harness-upload-` 临时前缀统一隐藏并保护。目录重命名完整复核子树，目录删除按确认清单逐项执行；平台管理的 Turn 与变更互斥，收到中断确认后仍等实际执行结束再释放。操作无法确认或进程不能确认停止时保留占用，禁止自动重试变更。此协调不锁住外部程序新建文件，目录重命名不承诺外部写入的原子子树快照。

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

恢复使用官方 [Codex App Server 的 thread/read 与 thread/resume 协议](https://learn.chatgpt.com/docs/app-server)。恢复前后都会校验 Thread ID 和目录；执行 Turn 时继续使用项目权限 profile、Agent 配置的网络模式与 `approvalPolicy=on-request`，并重新配置执行前确认能力。

## Skill 公网 API

Windows 配置 `harness.agent.command-network-mode: PUBLIC`（示例配置默认值）后，脚本可调用公网 HTTP/HTTPS API；环境变量 `HARNESS_COMMAND_NETWORK_MODE=DISABLED` 可恢复禁网。需同步升级 Server 和前端以识别 `WINDOWS_LPAC_API_V3`，重启 Agent 后新建 Conversation。同模式会话支持恢复。Linux PUBLIC 目前明确拒绝注册，需设置 DISABLED。依赖安装不包含在此功能内。详见 [验证与部署说明](../../docs/testing/public-api-skill.md)。
