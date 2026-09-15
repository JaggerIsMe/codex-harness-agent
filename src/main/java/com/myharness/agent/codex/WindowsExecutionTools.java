package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;

/** Controlled replacements; native execution and filesystem tools remain disabled. */
final class WindowsExecutionTools {
    static final String NAME="harness_execute";
    static final List<String> DISABLED=List.of("shell_tool","unified_exec","code_mode","code_mode_host","view_image","image_generation","multi_agent");
    private WindowsExecutionTools() { }
    static void validateRemoteMcp(String url) {
        try {
            String host=java.net.URI.create(url).getHost();
            if(host==null) throw new CodexException("MCP URL 缺少主机名");
            for(var address:java.net.InetAddress.getAllByName(host))
                if(address.isAnyLocalAddress() || address.isLoopbackAddress() || java.net.NetworkInterface.getByInetAddress(address)!=null)
                    throw new CodexException("Windows 读取隔离禁止连接本机 HTTP MCP，请使用独立远程 MCP 服务");
        } catch(CodexException failure) {throw failure;}
        catch(Exception failure) {throw new CodexException("无法确认 MCP 服务位于隔离边界之外",failure);}
    }

    static void configure(ObjectNode params,boolean newThread) {
        configure(params,newThread,true);
    }
    static void configure(ObjectNode params,boolean newThread,boolean imageInput) {
        configure(params,newThread,imageInput,false);
    }
    static void configure(ObjectNode params,boolean newThread,boolean imageInput,boolean imageGeneration) {
        ObjectNode config=params.withObject("config");
        DISABLED.forEach(key->config.withObject("features").put(key,false));
        config.withObject("features").put("apps",false).put("plugins",false);
        // Host-side automatic project-document loading must not follow a Workspace symlink.
        config.put("project_doc_max_bytes",0);
        if(newThread) {
            var tool=params.putArray("dynamicTools").addObject().put("type","function").put("name",NAME)
                    .put("description","Run Python 3 in this project's Windows LPAC boundary. Use pathlib for reading/searching/editing files and subprocess for allowed Windows programs. Read project AGENTS.md through this tool if present. Workspace files are writable; external user files and command networking are inaccessible. The current expert Skill directories and configured runtime files are read-only exceptions. Skill scripts may use preinstalled dependencies; report missing dependencies without installing them. PowerShell is not supported in this boundary. No permission escalation is available. Each call is synchronous and ends all child processes on completion, with a maximum 120 second timeout. Use request_user_input for execution confirmation before sensitive operations.");
            var schema=tool.putObject("inputSchema").put("type","object").put("additionalProperties",false);
            schema.putArray("required").add("script");var fields=schema.putObject("properties");
            fields.putObject("script").put("type","string").put("description","Python source to run in the project directory.").put("minLength",1).put("maxLength",12000);
            fields.putObject("timeout_seconds").put("type","integer").put("minimum",1).put("maximum",120);
            addTextTool(params,WorkspacePatchTool.NAME,"Apply a context patch within this Workspace. Format: *** Begin Patch, *** Add File: path (+lines), *** Update File: path (@@ context hunks), optional *** Move to: path, *** Delete File: path, *** End Patch. Paths must be relative with forward slashes; parent directories must already exist. UTF-8 text only. All contexts are checked before writes. No links, shared hard links, protected metadata, or external paths. Maximum 32 files, 1 MiB per file. Use request_user_input for confirmation when required by the user's instructions.","patch",131072);
            if(imageInput)addTextTool(params,WorkspaceImageTool.NAME,"View a Workspace PNG, JPEG or GIF as image pixels. Use this controlled tool instead of native view_image, including for visual verification. Relative forward-slash paths only. No links, shared hard links, protected metadata or external files. Maximum 10 MiB / 16 megapixels; GIF uses first frame.","path",2048);
            if(imageGeneration) {
                var imageTool=params.withArray("dynamicTools").addObject().put("type","function").put("name",WorkspaceImageGenerationTool.NAME)
                        .put("description","Generate or edit an image using the current Local Codex image service. Use this instead of native imagegen/image_generation. Prompt is required; output_path must be a new Workspace-relative .png path whose parent exists. Optional reference_paths contains up to 5 Workspace-relative PNG/JPEG/GIF images. No URLs, external paths or credentials accepted. Existing files are never overwritten. This is a billable service call; do not retry automatically after timeout or an uncertain result. Maximum service wait 5 minutes.");
                var imageSchema=imageTool.putObject("inputSchema").put("type","object").put("additionalProperties",false);
                imageSchema.putArray("required").add("prompt").add("output_path");var imageFields=imageSchema.putObject("properties");
                imageFields.putObject("prompt").put("type","string").put("minLength",1).put("maxLength",12000);
                imageFields.putObject("output_path").put("type","string").put("minLength",1).put("maxLength",2048);
                imageFields.putObject("reference_paths").put("type","array").put("maxItems",5).putObject("items").put("type","string").put("minLength",1).put("maxLength",2048);
            }
        }
    }
    private static void addTextTool(ObjectNode params,String name,String description,String field,int limit) {
        var tool=params.withArray("dynamicTools").addObject().put("type","function").put("name",name).put("description",description);
        var schema=tool.putObject("inputSchema").put("type","object").put("additionalProperties",false);
        schema.putArray("required").add(field);schema.putObject("properties").putObject(field).put("type","string").put("minLength",1).put("maxLength",limit);
    }
    static void configureCommands(ObjectNode params,com.myharness.agent.config.AgentProperties properties) {
        var tool=params.withArray("dynamicTools").addObject().put("type","function").put("name",WindowsCommandTool.NAME)
                .put("description","Run an administrator-configured program with structured arguments in the project's Windows LPAC. Use for builds and tests. Built-ins: python (isolated Python), cmd (Windows CMD). Additional aliases are listed in program. Processes cannot read external private files or access the network. Tool runtimes are read-only; caches use a separate per-project execution directory. Up to 1800 seconds; all descendants are terminated on completion, cancellation or timeout. Output capped at 128 KiB. No interactive terminal, permission escalation or background servers. Request execution confirmation when required by user instructions.");
        var schema=tool.putObject("inputSchema").put("type","object").put("additionalProperties",false);schema.putArray("required").add("program").add("args");
        var fields=schema.putObject("properties");var programs=fields.putObject("program").put("type","string").putArray("enum").add("python").add("cmd");
        properties.getWindowsTools().keySet().stream().sorted().filter(name->!name.equals("python")&&!name.equals("cmd")).forEach(programs::add);
        fields.putObject("args").put("type","array").put("maxItems",128).putObject("items").put("type","string").put("maxLength",8000);
        fields.putObject("timeout_seconds").put("type","integer").put("minimum",1).put("maximum",1800);
    }
    static void configureNetwork(ObjectNode params,com.myharness.agent.config.AgentProperties properties) {
        String policy=properties.isPublicCommandNetwork()
                ? " Network mode: PUBLIC. Outbound public Internet requests, including HTTP/HTTPS APIs, are available to scripts and child processes. Windows network isolation still applies; no private-network capability, inbound-server capability or loopback exemption is granted. TLS certificate verification must remain enabled. Dependencies must already be available."
                : " Network mode: DISABLED. Commands and child processes cannot access the network.";
        for(var tool:params.path("dynamicTools")) {
            String name=tool.path("name").asText();
            if(NAME.equals(name)||WindowsCommandTool.NAME.equals(name)) {
                String description=tool.path("description").asText()
                        .replace("external user files and command networking are inaccessible", "external user files are inaccessible")
                        .replace("Processes cannot read external private files or access the network.", "Processes can read only authorized files, including the current Skill directories.")
                        .replace("per-project execution directory", "per-run execution directory");
                ((ObjectNode)tool).put("description",description+policy);
            }
        }
    }
    static String textArgument(JsonNode args,String field,int limit) {
        if(!args.isObject()||args.size()!=1||!args.path(field).isTextual()||args.path(field).asText().isBlank()||args.path(field).asText().length()>limit)
            throw new CodexException("Invalid tool arguments: expected only "+field);
        return args.path(field).asText();
    }
    static JsonNode eventSummary(JsonNode item,com.fasterxml.jackson.databind.ObjectMapper json) {
        if(!"dynamicToolCall".equals(item.path("type").asText()))return item;
        String tool=item.path("tool").asText();
        if(!List.of(NAME,WorkspacePatchTool.NAME,WorkspaceImageTool.NAME,WorkspaceImageGenerationTool.NAME,WindowsCommandTool.NAME).contains(tool))return item;
        var summary=json.createObjectNode().put("type","dynamicToolCall").put("tool",tool);
        for(String field:List.of("id","status","success","durationMs"))if(item.has(field))summary.set(field,item.get(field));
        var text=new StringBuilder();
        for(var content:item.path("contentItems"))if("inputText".equals(content.path("type").asText()))text.append(content.path("text").asText()).append('\n');
        if(text.isEmpty()) {
            var arguments=item.path("arguments");
            text.append(arguments.path("path").asText(arguments.path("output_path").asText("正在执行")));
        }
        summary.put("message",text.substring(0,Math.min(text.length(),128*1024)));
        return summary;
    }

    static WindowsIsolatedCommand.Result execute(Path workspace,com.myharness.agent.config.AgentProperties properties,JsonNode arguments) throws java.io.IOException {
        return execute(workspace,properties,arguments,null);
    }
    static WindowsIsolatedCommand.Result execute(Path workspace,com.myharness.agent.config.AgentProperties properties,JsonNode arguments,SkillExecutionScope scope) throws java.io.IOException {
        if(!arguments.isObject() || !arguments.path("script").isTextual()) throw new CodexException("Invalid isolated command arguments");
        for(var fields=arguments.fieldNames();fields.hasNext();) {
            String key=fields.next();if(!key.equals("script") && !key.equals("timeout_seconds")) throw new CodexException("Unsupported isolated command field: "+key);
        }
        if(arguments.has("timeout_seconds") && !arguments.path("timeout_seconds").isIntegralNumber()) throw new CodexException("Command timeout must be an integer");
        if(scope!=null)return WindowsIsolatedCommand.executeScoped(workspace,arguments.path("script").asText(),arguments.path("timeout_seconds").asInt(60),properties.getWindowsPython(),scope,List.of(),java.util.Map.of(),120,properties.isPublicCommandNetwork());
        return WindowsIsolatedCommand.execute(workspace,arguments.path("script").asText(),arguments.path("timeout_seconds").asInt(60),properties.getWindowsPython(),com.myharness.agent.workspace.AgentStorage.executionDirectory(properties.getDataDir(),workspace));
    }
}
