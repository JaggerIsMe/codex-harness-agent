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
        filesystem.putObject(":workspace_roots").put(".","write").put(".git","read").put(".codex","read");
        policy.putObject("network").put("enabled",false);
        return policy;
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
