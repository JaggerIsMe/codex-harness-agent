package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.workspace.WorkspaceToolFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** Image service arguments never include caller-supplied URLs, credentials or host paths. */
final class WorkspaceImageGenerationTool {
    static final String NAME="harness_generate_image";
    @FunctionalInterface interface Generator {byte[] generate(ObjectNode request,boolean edit) throws Exception;}
    private WorkspaceImageGenerationTool(){ }
    static ObjectNode generate(Path workspace,JsonNode arguments,ObjectMapper json,Generator generator) throws Exception {
        if(!arguments.isObject())throw new IOException("图片生成参数必须为对象");
        for(var fields=arguments.fieldNames();fields.hasNext();)if(!Set.of("prompt","output_path","reference_paths").contains(fields.next()))throw new IOException("未知图片生成参数");
        String prompt=text(arguments,"prompt",12000),output=text(arguments,"output_path",2048);
        if(!output.toLowerCase(java.util.Locale.ROOT).endsWith(".png"))throw new IOException("生成图片的输出路径必须以 .png 结尾");
        var references=arguments.path("reference_paths");
        if(arguments.has("reference_paths")&&(!references.isArray()||references.size()>5))throw new IOException("参考图片必须为数组，最多 5 张");
        ObjectNode request=json.createObjectNode().put("model","gpt-image-2").put("prompt",prompt)
                .put("background","auto").put("quality","auto").put("size","auto");
        // Release reference handles before the network request. Keep only verified pixels in memory.
        try(var files=new WorkspaceToolFiles(workspace,false)) {
            files.requireAbsent(output);Set<String> seen=new HashSet<>();
            int total=0;
            for(var reference:references) {
                if(!reference.isTextual()||!seen.add(reference.asText().toLowerCase(java.util.Locale.ROOT)))throw new IOException("参考图片路径无效或重复");
                byte[] bytes=WorkspaceImageTool.normalize(files.read(reference.asText(),WorkspaceImageTool.MAX_BYTES));
                total+=bytes.length;if(total>20*1024*1024)throw new IOException("参考图片总大小超过 20 MiB");
                request.withArray("images").addObject().put("image_url","data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes));
            }
        }
        byte[] pixels=WorkspaceImageTool.normalize(generator.generate(request,!references.isEmpty()));
        if(Thread.currentThread().isInterrupted())throw new IOException("图片生成已取消，未写入文件");
        // Revalidate and exclusively CREATE_NEW after the service returns; never overwrite an existing file.
        try(var files=new WorkspaceToolFiles(workspace,true)){files.create(output,pixels);}
        var result=json.createObjectNode().put("success",true);var items=result.putArray("contentItems");
        items.addObject().put("type","inputText").put("text","Generated image saved in Workspace: "+output);
        items.addObject().put("type","inputImage").put("imageUrl","data:image/png;base64,"+Base64.getEncoder().encodeToString(pixels));
        return result;
    }
    private static String text(JsonNode arguments,String field,int maximum) throws IOException {
        if(!arguments.path(field).isTextual()||arguments.path(field).asText().isBlank()||arguments.path(field).asText().length()>maximum)throw new IOException("图片生成参数无效："+field);
        return arguments.path(field).asText();
    }
}
