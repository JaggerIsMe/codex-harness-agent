package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.workspace.WorkspaceFileService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured program invocation in the same LPAC; executable homes are administrator-controlled.
 */
final class WindowsCommandTool {
    static final String NAME = "harness_run_command";

    private WindowsCommandTool() {
    }

    static WindowsIsolatedCommand.Result execute(Path workspace, AgentProperties properties, JsonNode arguments, ObjectMapper json) throws IOException {
        return execute(workspace,properties,arguments,json,null);
    }
    static WindowsIsolatedCommand.Result execute(Path workspace, AgentProperties properties, JsonNode arguments, ObjectMapper json, SkillExecutionScope scope) throws IOException {
        if (!arguments.isObject()) throw new CodexException("命令参数必须为对象");
        for (var fields = arguments.fieldNames(); fields.hasNext(); )
            if (!Set.of("program", "args", "timeout_seconds").contains(fields.next()))
                throw new CodexException("未知命令参数");
        if (!arguments.path("program").isTextual() || !arguments.path("args").isArray() || arguments.path("args").size() > 128)
            throw new CodexException("命令需要 program 和 args 数组");
        if (arguments.has("timeout_seconds") && (!arguments.path("timeout_seconds").isIntegralNumber() || !arguments.path("timeout_seconds").canConvertToInt()))
            throw new CodexException("超时时间必须为整数");
        int timeout = arguments.path("timeout_seconds").asInt(120);
        if (timeout < 1 || timeout > 1800) throw new CodexException("命令超时范围为 1–1800 秒");
        var executables = new LinkedHashMap<String, String>();
        var homes = new ArrayList<Path>();
        var environment = new LinkedHashMap<String, String>();
        Path python = properties.getWindowsPython().toRealPath();
        executables.put("python", python.toString());
        executables.put("cmd", Path.of(System.getenv("SystemRoot"), "System32/cmd.exe").toString());
        var searchPaths = new ArrayList<String>();
        searchPaths.add(python.getParent().toString());
        searchPaths.add(Path.of(System.getenv("SystemRoot"), "System32").toString());
        Path data = properties.getDataDir().toRealPath(), project = workspace.toRealPath();
        for (var entry : properties.getWindowsTools().entrySet()) {
            String name = entry.getKey();
            var tool = entry.getValue();
            if (!name.matches("[a-z][a-z0-9_-]{0,31}") || executables.containsKey(name) || tool == null || tool.getHome() == null)
                throw new CodexException("工具链别名或目录无效");
            WorkspaceFileService.validateRelative(tool.getExecutable());
            if (tool.getExecutable().isBlank()) throw new CodexException("工具链可执行文件不能为空");
            Path home = tool.getHome().toRealPath();
            Path executable = home.resolve(tool.getExecutable()).toRealPath();
            if (home.getParent() == null || data.startsWith(home) || project.startsWith(home) || home.startsWith(project)
                    || Path.of(System.getProperty("user.home")).toRealPath().startsWith(home)
                    || !executable.startsWith(home) || !Files.isRegularFile(executable))
                throw new CodexException("工具链必须位于不含项目、Agent 状态或用户目录的专用目录");
            if (!executable.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches(".+\\.(exe|cmd|bat)"))
                throw new CodexException("工具链入口必须为 exe、cmd 或 bat");
            homes.add(home);
            executables.put(name, executable.toString());
            searchPaths.add(executable.getParent().toString());
            if (name.equals("java")) environment.put("JAVA_HOME", home.toString());
        }
        String program = arguments.path("program").asText();
        String executable = executables.get(program);
        if (executable == null) throw new CodexException("未配置工具：" + program + "；可用工具：" + executables.keySet());
        var argv = new ArrayList<String>();
        argv.add(executable);
        if (program.equals("python")) {
            argv.add("-I");
            argv.add("-S");
            argv.add("-X");
            argv.add("utf8");
        }
        int length = 0;
        for (var arg : arguments.path("args")) {
            if (!arg.isTextual() || arg.asText().indexOf('\0') >= 0)
                throw new CodexException("命令参数必须为不含 NUL 的字符串");
            length += arg.asText().length();
            if (length > 8000) throw new CodexException("命令参数总长超过 8000 字符");
            argv.add(arg.asText());
        }
        environment.put("PATH", String.join(";", searchPaths));
        environment.put("NODE_OPTIONS", "--preserve-symlinks --preserve-symlinks-main");
        boolean batch = executable.toLowerCase(java.util.Locale.ROOT).endsWith(".cmd") || executable.toLowerCase(java.util.Locale.ROOT).endsWith(".bat");
        if (batch && argv.stream().anyMatch(value -> value.chars().anyMatch(c -> "%!^&|<>\"\r\n".indexOf(c) >= 0)))
            throw new CodexException("批处理启动器的参数不支持 Shell 控制字符；请使用直接的 exe 入口或隔离脚本");
        List<String> command=program.equals("python") ? PythonCommand.withManagedImports(argv,json) : argv;
        String encoded = java.util.Base64.getEncoder().encodeToString(json.writeValueAsBytes(command));
        String script = "import base64,json,subprocess,sys,os\nargv=json.loads(base64.b64decode('" + encoded + "'))\n"
                + (batch ? "q=chr(34)\nargv=q+os.environ['ComSpec']+q+' /d /s /c '+q+' '.join(q+arg+q for arg in argv)+q\n" : "")
                + "result=subprocess.run(argv,shell=False)\nsys.exit(result.returncode)";
        if(scope!=null)return WindowsIsolatedCommand.executeScoped(project,script,timeout,python,scope,homes,environment,1800);
        return WindowsIsolatedCommand.executeTool(project, script, timeout, python, com.myharness.agent.workspace.AgentStorage.executionDirectory(properties.getDataDir(), project), homes, environment);
    }
}
