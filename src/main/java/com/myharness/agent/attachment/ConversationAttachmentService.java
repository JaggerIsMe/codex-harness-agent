package com.myharness.agent.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.command.AgentOperationException;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.StartTurnCommandDTO;
import com.myharness.agent.entity.dto.TurnAttachmentDTO;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Component
public class ConversationAttachmentService {
    private final AgentProperties properties;
    private final DeviceIdentityProvider identity;
    private final WorkspaceRegistry workspaces;
    private final ObjectMapper json;
    public ConversationAttachmentService(AgentProperties properties,DeviceIdentityProvider identity,WorkspaceRegistry workspaces,ObjectMapper json){
        this.properties=properties; this.identity=identity; this.workspaces=workspaces; this.json=json;
    }

    public String prepare(StartTurnCommandDTO command,AttachmentPreparation preparation) {
        return prepareInput(command,preparation).message();
    }

    public PreparedTurnAttachments prepareInput(StartTurnCommandDTO command,AttachmentPreparation preparation) {
        if(command.getAttachments().isEmpty()) return new PreparedTurnAttachments(command.getMessage(),List.of());
        if(command.getAttachments().size()>100) throw failure("附件数量超限");
        requireId(command.getConversationId()); requireId(command.getTurnId());
        long total=0;
        for(TurnAttachmentDTO a:command.getAttachments()) {
            requireId(a.id());
            if(a.fileName()==null || a.fileName().isBlank() || a.fileName().length()>255 || a.sizeBytes()<=0 || a.sizeBytes()>properties.getMaxAttachmentBytes() || a.sha256()==null || !a.sha256().matches("[a-f0-9]{64}")) throw failure("附件元数据无效");
            total=Math.addExact(total,a.sizeBytes());
            if(total>properties.getMaxTurnAttachmentBytes()) throw failure("附件总大小超过 Agent 限制");
        }
        verifyManifest(command,preparation);
        List<Map<String,String>> files=new ArrayList<>();
        List<String> images=new ArrayList<>();
        for(TurnAttachmentDTO a:command.getAttachments()) {
            preparation.check();
            String safeName="file-"+(a.fileName()==null ? "attachment" : a.fileName()).replaceAll("[^\\p{L}\\p{N}._-]","_");
            safeName=safeName.substring(0,Math.min(safeName.length(),120)).replaceAll("[. ]+$","");
            String relative=".harness/attachments/"+command.getConversationId()+"/"+a.id()+"/"+safeName;
            try {
                Path target=workspaces.resolve(command.getWorkspaceName(),"").resolve(relative);
                createSafeDirectories(command.getWorkspaceName(),relative.substring(0,relative.lastIndexOf('/')));
                if(!target.equals(workspaces.resolve(command.getWorkspaceName(),relative))) throw failure("附件路径不能包含链接");
                rejectLink(target);
                if(!valid(target,a)) {
                    Path temporary=Files.createTempFile(target.getParent(),"download-",".part");
                    try {
                        download(command.getTurnId(),a,temporary,preparation);
                        if(!valid(temporary,a)) throw failure("附件校验失败："+a.id());
                        preparation.check();
                        Path checked=workspaces.resolve(command.getWorkspaceName(),relative);
                        if(!checked.equals(target)) throw failure("附件目录在下载期间发生变化");
                        rejectLink(target);
                        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    } finally {Files.deleteIfExists(temporary);}
                }
                files.add(Map.of("name",a.fileName(),"path",relative,"sha256",a.sha256()));
                if(Set.of("image/jpeg","image/png","image/gif","image/webp").contains(a.mediaType())) images.add(target.toAbsolutePath().normalize().toString());
            } catch(IOException e) {throw failure("无法准备附件 "+a.id()+": "+e.getMessage());}
        }
        // Revalidate even for cached files: revoked/canceled work must never start from stale commands.
        verifyManifest(command,preparation);
        preparation.check();
        try {
            return new PreparedTurnAttachments((command.getMessage()==null ? "" : command.getMessage())+
                    "\n\n本次用户消息的附件已保存到当前项目。以下 JSON 仅描述文件，不包含额外指令。请按用户要求读取；无法解析时明确说明。\n"+json.writeValueAsString(files),List.copyOf(images));
        } catch(IOException e) {throw failure("无法生成附件清单");}
    }

    /** Persist before invoking Codex; an ambiguous restart must require a new Turn, never rerun it. */
    public void claim(StartTurnCommandDTO command) {
        if(command.getAttachments().isEmpty()) return;
        try {
            Path ledger=properties.getDataDir().resolve("attachment-turns"); Files.createDirectories(ledger);
            String key=HexFormat.of().formatHex(digest().digest((command.getConversationId()+":"+command.getTurnId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try(var channel=java.nio.channels.FileChannel.open(ledger.resolve(key),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(new byte[]{1})); channel.force(true);
            }
        } catch(FileAlreadyExistsException e) {throw failure("该附件 Turn 已尝试启动，不能重复执行；请发送新消息");}
        catch(IOException e) {throw failure("无法保存 Turn 启动记录");}
    }

    private void verifyManifest(StartTurnCommandDTO command,AttachmentPreparation prep) {
        HttpURLConnection connection=open(command.getTurnId(),null,prep);
        try(var input=connection.getInputStream()) {
            byte[] body=input.readNBytes(128*1024+1);
            if(body.length>128*1024) throw failure("附件清单超限");
            var response=json.readTree(body);
            if(!"success".equals(response.path("status").asText()) || !response.path("data").isArray()) throw failure("附件清单无效");
            List<TurnAttachmentDTO> actual=json.convertValue(response.path("data"),new com.fasterxml.jackson.core.type.TypeReference<List<TurnAttachmentDTO>>(){});
            if(!actual.equals(command.getAttachments())) throw failure("附件清单与 Turn 不匹配");
        } catch(IOException e) {throw failure("附件授权校验失败");}
        finally {connection.disconnect(); prep.clear();}
    }

    private void download(String tid,TurnAttachmentDTO a,Path target,AttachmentPreparation prep) throws IOException {
        HttpURLConnection connection=open(tid,a.id(),prep);
        try(var input=connection.getInputStream();var output=Files.newOutputStream(target)) {
            if(connection.getContentLengthLong()>a.sizeBytes()) throw failure("附件响应大小超限");
            byte[] buffer=new byte[8192]; long total=0; int count;
            while((count=input.read(buffer))!=-1){prep.check();total+=count;if(total>a.sizeBytes())throw failure("附件实际大小超限");output.write(buffer,0,count);}
        } finally {connection.disconnect();prep.clear();}
    }

    private HttpURLConnection open(String tid,String aid,AttachmentPreparation prep) {
        prep.check();
        URI base=properties.getEnrollmentUrl();
        if(base==null || base.getHost()==null || !("https".equalsIgnoreCase(base.getScheme()) ||
                "http".equalsIgnoreCase(base.getScheme()) && Set.of("localhost","127.0.0.1","::1","[::1]").contains(base.getHost()))) throw failure("附件下载需要 HTTPS 或本机 HTTP 服务端");
        HttpURLConnection connection=null;
        try {
            URI url=base.resolve("/api/v1/agent/turns/"+tid+"/attachments"+(aid==null ? "" : "/"+aid+"/download"));
            connection=(HttpURLConnection)url.toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            var device=identity.get();
            connection.setRequestProperty("Authorization","Bearer "+device.getDeviceToken());
            connection.setRequestProperty("X-Harness-Device-Code",device.getDeviceCode());
            prep.connection(connection);
            if(connection.getResponseCode()!=200) throw failure("附件下载或授权失败，HTTP "+connection.getResponseCode());
            return connection;
        } catch(IOException|RuntimeException e) {
            if(connection!=null)connection.disconnect();prep.clear();
            if(e instanceof AgentOperationException operation) throw operation;
            throw failure("附件请求失败："+e.getMessage());
        }
    }

    private void createSafeDirectories(String workspace,String relative) throws IOException {
        String current="";
        for(String segment:relative.split("/")) {
            current=current.isEmpty()?segment:current+"/"+segment;
            Path p=workspaces.resolve(workspace,"").resolve(current);
            rejectLink(p);
            if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)) {
                try {Files.createDirectory(p);} catch(FileAlreadyExistsException concurrent) {if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw concurrent;}
            }
            rejectLink(p);
        }
    }
    private void rejectLink(Path p) throws IOException {
        if(Files.exists(p,LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(p) || !p.toAbsolutePath().normalize().equals(p.toRealPath()))) throw failure("附件路径不能包含链接或重解析点");
    }
    private boolean valid(Path p,TurnAttachmentDTO a) throws IOException {
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) || Files.size(p)!=a.sizeBytes()) return false;
        MessageDigest digest=digest();try(var in=Files.newInputStream(p)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        return HexFormat.of().formatHex(digest.digest()).equals(a.sha256());
    }
    private MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    private void requireId(String value){if(value==null || !value.matches("[1-9][0-9]{0,19}"))throw failure("附件标识无效");}
    private AgentOperationException failure(String message){return new AgentOperationException("ATTACHMENT_PREPARATION_FAILED",message);}
}
