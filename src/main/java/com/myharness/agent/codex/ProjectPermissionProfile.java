package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** Shared by the startup probe and every thread. */
final class ProjectPermissionProfile {
    private ProjectPermissionProfile() { }
    static ObjectNode policy(ObjectMapper json) {
        var policy=json.createObjectNode().put("extends",":workspace");
        var filesystem=policy.putObject("filesystem");
        filesystem.put(":root","deny").put(":minimal","read").put(":tmpdir","deny").put(":slash_tmp","deny");
        if("Linux".equalsIgnoreCase(System.getProperty("os.name")))
            filesystem.put(System.getProperty("java.home"),"read");
        filesystem.putObject(":workspace_roots").put(".","write").put(".git","write").put(".codex","write").put(".agent","write").put(".agents","write");
        policy.putObject("network").put("enabled",false);
        return policy;
    }
    static ObjectNode policy(ObjectMapper json,java.nio.file.Path data,java.nio.file.Path workspace) {
        var policy=policy(json);
        try {
            var files=policy.withObject("filesystem");
            java.nio.file.Files.createDirectories(data);
            files.put(data.toRealPath().toString(),"deny");
            files.put(workspace.toRealPath().toString(),"write");
            files.put(com.myharness.agent.workspace.AgentStorage.executionDirectory(data,workspace).toString(),"write");
        } catch(java.io.IOException failure) {throw new CodexException("Cannot prepare project storage permissions",failure);}
        return policy;
    }

    static ObjectNode policy(ObjectMapper json,java.nio.file.Path data,java.nio.file.Path workspace,SkillExecutionScope scope) {
        if(scope==null)return policy(json,data,workspace);
        var policy=policy(json);
        var files=policy.withObject("filesystem");
        // :root denies all private storage by default. Do not add an ancestor deny
        // which could shadow the exact child grants on runtimes with deny precedence.
        files.put(workspace.toAbsolutePath().normalize().toString(),"write");
        files.put(scope.temporaryDirectory().toString(),"write");
        scope.readableSkills().forEach(path->files.put(path.toString(),"read"));
        return policy;
    }

    static List<String> commandOverrides(ObjectMapper json,String name,java.nio.file.Path data,java.nio.file.Path workspace) {
        var result=new ArrayList<String>();
        flatten("permissions.\""+name+"\"",policy(json,data,workspace),result);
        return result;
    }
    static List<String> commandOverrides(ObjectMapper json,String name,java.nio.file.Path data,java.nio.file.Path workspace,SkillExecutionScope scope) {
        var result=new ArrayList<String>();
        flatten("permissions.\""+name+"\"",policy(json,data,workspace,scope),result);
        return result;
    }
    static List<String> commandOverrides(ObjectMapper json,String name) {
        var result=new ArrayList<String>();
        flatten("permissions.\""+name+"\"",policy(json),result);
        return result;
    }
    private static void flatten(String key,JsonNode value,List<String> result) {
        if(value.isObject()) value.properties().forEach(entry ->
                flatten(key+"."+new com.fasterxml.jackson.databind.node.TextNode(entry.getKey()),entry.getValue(),result));
        else { result.add("-c");result.add(key+"="+value); }
    }
}
