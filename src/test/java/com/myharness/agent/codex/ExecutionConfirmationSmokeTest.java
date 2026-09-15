package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import com.myharness.agent.entity.enums.ApprovalDecision;
import com.myharness.agent.entity.enums.ApprovalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real local Codex process, deterministic loopback provider; no external model or credential. */
@EnabledIfSystemProperty(named="codex.confirmation.smoke",matches="true")
class ExecutionConfirmationSmokeTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false,false","true,false,false","false,true,false","true,true,false","false,true,true","true,true,true"})
    void confirmsOnNewAndResumedThreadsWithoutNativeApproval(boolean choice,boolean privateSkill,boolean orchestration,@TempDir Path workspace,@TempDir Path data) throws Exception {
        var json=new ObjectMapper();var nextConfirmation=new AtomicBoolean(true);
        var cancelMode=new AtomicBoolean(false);
        var continuedInput=new AtomicReference<String>();
        var firstRequest=new AtomicReference<String>();
        var advertisedTools=new java.util.concurrent.LinkedBlockingQueue<String>();
        var provider=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/responses",exchange -> {
            String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            boolean confirm=nextConfirmation.getAndSet(false);
            if(confirm) { firstRequest.set(input); advertisedTools.add(json.readTree(input).path("tools").toString()); }
            ObjectNode item=json.createObjectNode().put("id",confirm?"fc-confirm":"msg-confirm").put("status","completed");
            if(confirm) {
                item.put("type","function_call").put("name","request_user_input").put("call_id","call-confirm");
                var args=json.createObjectNode();var question=args.putArray("questions").addObject()
                        .put("id",choice?"method":ExecutionConfirmation.QUESTION_ID).put("header",choice?"替代方案":"执行前确认").put("question",choice?"你希望采用哪种方式？":"允许本次只读测试操作吗？");
                var options=question.putArray("options");
                for(String label:choice?List.of("手绘矢量插画 (Recommended)","只给绘图提示词","两个都要"):List.of("批准本次","拒绝操作","拒绝并中断")) options.addObject().put("label",label).put("description",label);
                item.put("arguments",args.toString());
            } else {
                continuedInput.set(input);item.put("type","message").put("role","assistant");
                // Keep the simulated next model response in flight while turn/interrupt is exercised.
                if(cancelMode.get()) try {Thread.sleep(1500);} catch(InterruptedException failure) {Thread.currentThread().interrupt();}
                item.putArray("content").addObject().put("type","output_text").put("text","CONFIRMATION_FINISHED").putArray("annotations");
            }
            var added=json.createObjectNode().put("type","response.output_item.added").put("output_index",0);added.set("item",item);
            var done=json.createObjectNode().put("type","response.output_item.done").put("output_index",0);done.set("item",item);
            var completed=json.createObjectNode().put("type","response.completed");
            var response=completed.putObject("response").put("id","resp-confirm").put("status","completed");
            response.putArray("output").add(item);response.putObject("usage").put("input_tokens",1).put("output_tokens",1).put("total_tokens",2);
            var stream=new StringBuilder();for(var event:List.of(added,done,completed))
                stream.append("event: ").append(event.path("type").asText()).append("\ndata: ").append(event).append("\n\n");
            byte[] bytes=stream.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });provider.start();
        try {
            var runtime=new ModelRuntimeDTO();runtime.setSchemaVersion(2);runtime.setRuntimeMode("MANAGED_PROVIDER");
            runtime.setRuntimeKey("c".repeat(64));runtime.setConfigurationVersionId(1L);runtime.setModelId("harness-confirmation-probe");
            runtime.setProviderName("Local confirmation fixture");runtime.setBaseUrl("http://127.0.0.1:"+provider.getAddress().getPort());runtime.setApiKey("synthetic-local-test-key");
            var properties=new AgentProperties();properties.setDataDir(data);properties.setCodexRequestTimeoutSeconds(20);
            if(System.getProperty("windows.isolation.python")!=null)properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
            List<CodexSkillInput> skills=List.of();
            if(privateSkill) {
                Path privateRoot=com.myharness.agent.workspace.AgentStorage.workspaceRoot(data,workspace);
                Path packageRoot=com.myharness.agent.workspace.AgentStorage.directory(privateRoot,
                        "expert-runtimes/confirmation-probe/"+"d".repeat(64)+"/skills/harness-confirmation-probe");
                Path skill=packageRoot.resolve("SKILL.md");
                java.nio.file.Files.writeString(skill,"---\nname: harness-confirmation-probe\ndescription: Private confirmation regression fixture\n---\nFollow Harness execution confirmation rules.\n");
                java.nio.file.Files.writeString(packageRoot.resolve("reference.md"),"PRIVATE_APPROVAL_RESOURCE_SENTINEL");
                skills=List.of(new CodexSkillInput("harness-confirmation-probe",skill.toString()));
                assertFalse(skill.startsWith(workspace));
            }
            var options=new CodexThreadOptions("confirmation-probe",workspace,runtime).withExpertRuntime(skills,List.of()).withOrchestration(orchestration);
            String thread=null;
            for(var decision:List.of(ApprovalDecision.ACCEPT,ApprovalDecision.DECLINE,ApprovalDecision.CANCEL)) {
                nextConfirmation.set(true);continuedInput.set(null);
                cancelMode.set(decision==ApprovalDecision.CANCEL);
                try(var adapter=new AppServerCodexAdapter(properties,json) {
                    @Override Path startupModelCatalog() {
                        Path path=super.startupModelCatalog();
                        try {
                            var catalog=json.readTree(path.toFile());
                            // Native models can supply a default-mode template which takes precedence
                            // over collaborationMode.settings.developer_instructions (Conversation 37).
                            if(privateSkill) ((ObjectNode)catalog.path("models").get(0))
                                    .putObject("model_messages").putObject("collaboration_modes")
                                    .put("default","NATIVE_DEFAULT_MODE_SENTINEL: Never use request_user_input for permission requests.")
                                    .putNull("plan");
                            ((ObjectNode)catalog.path("models").get(0)).putArray("experimental_supported_tools")
                                    .add("send_user_message_async").add("clock");
                            if(choice)((ObjectNode)catalog.path("models").get(0)).put("tool_mode","code_mode_only");
                            json.writeValue(path.toFile(),catalog);
                            return path;
                        } catch(java.io.IOException failure) {throw new RuntimeException(failure);}
                    }
                }) {
                    if(thread==null) thread=adapter.startThread(options);else adapter.resumeThread(thread,options);
                    var requested=new CompletableFuture<CodexApproval>();var finished=new CompletableFuture<String>();
                    var input=new CodexTurnInput("请执行测试操作，执行前必须让我确认。");
                    if(choice || privateSkill)input=input.withExpert("EXPERT_PROMPT_"+decision+": Follow Harness confirmation rules.",skills);
                    input.withOrchestration(orchestration);
                    adapter.startTurn(thread,input,new CodexEventListener() {
                        public void onEvent(CodexEvent event) { }
                        public void onApproval(CodexApproval approval) { requested.complete(approval); }
                        public void onCompleted(String id,String status,String reason) { finished.complete(status); }
                    });
                    String tools=advertisedTools.poll(30,TimeUnit.SECONDS);
                    assertNotNull(tools,"The real runtime must send a tool declaration to the local model");
                    assertFalse(tools.contains("request_user_input_async"),"Harness must not advertise a question tool that bypasses its waiting/answer protocol");
                    assertTrue(tools.contains("request_user_input"),"Blocking questions must remain available");
                    assertEquals(orchestration,tools.contains(OrchestrationOutcomeTool.NAME));
                    if(privateSkill) {
                        String developer=developerText(json,firstRequest.get());
                        assertTrue(developer.contains("PRIVATE_APPROVAL_RESOURCE_SENTINEL"),"The private text resource must reach real developer messages");
                        assertTrue(developer.contains("EXPERT_PROMPT_"+decision),"The current Expert prompt must reach the model after start/resume");
                        assertTrue(developer.contains(ExecutionConfirmation.QUESTION_ID),"Private Skill loading must preserve the confirmation protocol");
                        assertFalse(developer.contains("NATIVE_DEFAULT_MODE_SENTINEL"),"A model mode template must not shadow the Expert or contradict approvals");
                    }
                    var approval=requested.get(30,TimeUnit.SECONDS);
                    assertEquals(choice?ApprovalType.MCP_TOOL_CALL:ApprovalType.EXECUTION_CONFIRMATION,approval.getType());
                    assertFalse(finished.isDone());assertNull(continuedInput.get(),"Model must wait for the decision");
                    if(choice && decision==ApprovalDecision.ACCEPT) {
                        assertThrows(com.myharness.agent.command.AgentOperationException.class,()->adapter.resolveApproval(approval.getRequestId(),decision));
                        assertFalse(finished.isDone());
                        var answers=json.createObjectNode();answers.putObject("method").putArray("answers").add("只给绘图提示词");
                        adapter.resolveApproval(approval.getRequestId(),decision,answers);
                    } else adapter.resolveApproval(approval.getRequestId(),decision);
                    assertEquals(decision==ApprovalDecision.CANCEL?"interrupted":"completed",finished.get(30,TimeUnit.SECONDS));
                    if(choice && decision==ApprovalDecision.ACCEPT)assertTrue(continuedInput.get().contains("只给绘图提示词"));
                    else if(!choice&&decision!=ApprovalDecision.CANCEL)
                        assertTrue(continuedInput.get().contains(decision==ApprovalDecision.ACCEPT?"批准本次":"拒绝操作"));
                    if(privateSkill && decision==ApprovalDecision.ACCEPT) {
                        // The same loaded process must use fresh instructions, without resume/restart.
                        var nextFinished=new CompletableFuture<String>();
                        adapter.startTurn(thread,new CodexTurnInput("继续下一轮")
                                .withExpert("EXPERT_PROMPT_FOLLOWUP",skills).withOrchestration(orchestration),new CodexEventListener() {
                            public void onEvent(CodexEvent event) { }
                            public void onApproval(CodexApproval approval) {nextFinished.completeExceptionally(new AssertionError("Unexpected approval"));}
                            public void onCompleted(String id,String status,String reason) {nextFinished.complete(status);}
                        });
                        assertEquals("completed",nextFinished.get(30,TimeUnit.SECONDS));
                        String developer=developerText(json,continuedInput.get());
                        assertTrue(developer.contains("EXPERT_PROMPT_FOLLOWUP"));
                        assertTrue(developer.contains("PRIVATE_APPROVAL_RESOURCE_SENTINEL"));
                        assertFalse(developer.contains("NATIVE_DEFAULT_MODE_SENTINEL"));
                    }
                }
            }
        } finally {
            provider.stop(0);
            if(System.getProperty("os.name").startsWith("Windows")&&System.getProperty("windows.isolation.python")!=null)
                WindowsIsolatedCommand.cleanupProfile(workspace,Path.of(System.getProperty("windows.isolation.python")));
        }
    }

    private static String developerText(ObjectMapper json,String request) throws Exception {
        var result=new StringBuilder();
        for(var item:json.readTree(request).path("input")) if("developer".equals(item.path("role").asText()))
            for(var content:item.path("content"))result.append(content.path("text").asText()).append('\n');
        return result.toString();
    }
}
