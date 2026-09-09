# Harness Agent

Harness Agent 运行在目标电脑上，通过主动 WSS 连接接受 Harness Server 指令，并在受控工作区内驱动本机 `codex app-server`。

用户与机器授权模块要求升级到本版 Agent：上报 `WINDOWS_PROJECT_PROFILE`，创建/恢复线程时启用并核验每项目独立权限配置；旧写入隔离模式不能执行多用户项目。详见 [隔离边界、升级与验收](../../docs/user-device-rbac.md)。

已实现的 V1 功能：

- 一次性注册码注册设备，或直接使用已签发的设备编码和设备令牌。
- 将设备身份原子保存到 `${harness.agent.data-dir}/device-identity.json`。
- WSS 设备鉴权、注册事件、心跳、1/2/5/10/30 秒退避重连。
- 固定协议版本、消息类型白名单、有界命令去重和重复结果重放。
- `START_THREAD`、`START_TURN`、`INTERRUPT_TURN`、`INSTALL_SKILL`、`REMOVE_SKILL`、`CREATE_WORKSPACE`、`REFRESH_WORKSPACES`、`PING` 命令。
- 单 Codex App Server 进程、JSON-RPC 初始化、项目/Thread/Turn 映射和流式事件。
- Agent 重启后按 Server 保存的 Codex Thread ID 恢复已有 Conversation，核验工作区后续聊原历史。
- 严格项目模式下强制 Codex 原生 Windows `elevated` 沙箱，不允许降级到 `unelevated`，也不允许通过审批扩大项目权限。
- 每个 Turn 使用唯一项目目录作为 `writableRoots`，关闭网络访问，并拒绝不同项目复用或重叠执行目录。
- 全局 Turn 并发限制与同一 Conversation 单活动 Turn 限制。
- 工作区名称解析，以及绝对路径、父目录、符号链接、工作区嵌套和 Agent 数据目录逃逸防护。
- 在预授权父目录内原子创建动态工作区，并持久化到 `${harness.agent.data-dir}/workspaces.json` 以支持重启恢复和请求幂等。
- Skill 同源鉴权下载、SHA-256 校验、Zip Slip/链接/特殊文件拦截、展开限制；全局 Skill 原子安装到 `${harness.agent.skill-install-dir}`（默认 `${user.home}/.agents/skills`），项目级 Skill 安装到工作区 `.agents/skills`。

中台对接协议见 [Harness Agent API 文档](../../docs/harness-agent-api.md)，HTTP 接口定义见 [OpenAPI](../../docs/harness-agent-openapi.yaml)。

## 前置条件

- Java 21，构建使用 Maven 3.6.3+。Spring Boot 3.5.16；主代码与测试的编译目标均为 21，不再支持 Java 8 运行。
- Windows 11（推荐），或 Windows 10 1809+（Codex 官方标注为 best-effort）。无需 Docker Desktop 或 WSL2。
- 目标用户已安装 `codex`，且 `codex app-server --stdio` 可运行。
- 目标用户已在本机完成 Codex 登录；Harness Server 不接触 Codex 凭证。
- 静态工作区和允许创建工作区的父目录都必须预先存在，并使用绝对路径配置。

## 身份配置

已有设备身份时：

```powershell
$env:HARNESS_DEVICE_CODE = 'device-001'
$env:HARNESS_DEVICE_TOKEN = '首次注册取得的设备令牌'
```

首次注册时只配置一次性注册码，成功后 Agent 会把设备编码和令牌保存到数据目录：

```powershell
$env:HARNESS_ENROLLMENT_CODE = '一次性注册码'
$env:HARNESS_ENROLLMENT_URL = 'http://localhost:9010/api/v1/agent/enroll'
```

配置值优先于已保存的身份。日志和 WebSocket 消息正文都不会输出设备令牌。

## 常用配置

```powershell
$env:HARNESS_SERVER_URL = 'ws://localhost:9010/ws/agent'
$env:HARNESS_DEVICE_NAME = 'development-pc'
$env:HARNESS_AGENT_DATA_DIR = 'D:/my-harness/agent-data'
$env:HARNESS_WORKSPACE_PATH = 'D:/workspace/harness'
$env:HARNESS_MAX_CONCURRENT_TURNS = '1'
$env:HARNESS_HEARTBEAT_INTERVAL_SECONDS = '15'
$env:HARNESS_CODEX_COMMAND = 'codex'
$env:HARNESS_STRICT_PROJECT_ISOLATION = 'true'
$env:HARNESS_WINDOWS_SANDBOX = 'elevated'
```

生产环境的远程 Server 必须使用 `https`/`wss`。`http`/`ws` 只允许 `localhost`、`127.0.0.1` 或 `::1`。

多个工作区使用本地 YAML 配置：

```yaml
harness:
  agent:
    workspaces:
      - name: harness
        path: D:/workspace/harness
      - name: demo-project
        path: D:/workspace/demo-project
    workspace-roots:
      - name: development-projects
        path: D:/workspace/projects
```

`workspaces` 注册已有项目，`workspace-roots` 只授权中台在其直接子目录下创建新项目。Agent 数据目录不能与工作区或授权父目录重叠；不同工作区也不能互为父子。中台只传逻辑父目录名称和工作区名称，不能传任意绝对工作目录。

`strict-project-isolation` 默认开启。开启后 Agent 只在 Windows 上启动 Codex，并用 `--strict-config -c windows.sandbox="elevated"` 启动 App Server；Turn 固定使用 `approvalPolicy=never`、`workspaceWrite`、禁网和单一项目根目录。启动或执行失败时直接报错，不会回退到弱沙箱。

生产机器还应由管理员把 [config/codex-requirements.toml](config/codex-requirements.toml) 安装到 `%ProgramData%\OpenAI\Codex\requirements.toml`。这是 Codex 官方的系统级强制策略位置，模板只允许 `elevated`，可防止机器上的其他 Codex 配置回退到 `unelevated`。

注意：`windows.allowed_sandbox_implementations` 是 `requirements.toml` 的策略字段，不能复制到 `%USERPROFILE%\.codex\config.toml`。否则 `--strict-config` 会报 `unknown configuration field windows.allowed_sandbox_implementations` 并终止启动。用户配置中的 `[windows]` 使用 `sandbox = "elevated"` 选择沙箱；系统策略使用单独的 `requirements.toml` 限定允许的实现。

## Workspace 文件与消息附件

新文件通过项目工作区目录树上传和下载，对话框附件默认上传到工作区根目录。Agent 不再注入输出清单约定，也不再扫描 manifest 或收集 Turn 产物快照。接口、限制和升级步骤见 [Workspace 文件方案](../../docs/workspace-files.md)。

旧 Artifact 补传队列和配置已移除；消息附件只校验并读取 Workspace 文件，不再从平台下载到隐藏附件目录。升级后的旧队列清理步骤见 [Workspace 文件方案](../../docs/workspace-files.md)。

## 验证与启动

```powershell
java -version
mvn -version
mvn clean verify
java -jar target/harness-agent-1.0.0-SNAPSHOT.jar
```

测试覆盖协议校验、命令去重、会话并发、工作区逃逸和 Skill 安装安全校验。

Agent 不访问数据库，因此不引入 MyBatis、MySQL 或 Redis 客户端。WebSocket 连接使用 Spring 6 的 `execute` / `CompletableFuture` API；Jakarta 校验与关闭清理已适配。继续使用非 Web 进程保活和现有线程模型，未启用虚拟线程。

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

恢复使用官方 [Codex App Server 的 thread/read 与 thread/resume 协议](https://learn.chatgpt.com/docs/app-server)。恢复前后都会校验 Thread ID 和目录；执行 Turn 时继续强制单工作区、禁网与 `approvalPolicy=never`。
