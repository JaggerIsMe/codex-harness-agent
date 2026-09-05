package com.myharness.agent.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.command.AgentOperationException;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.config.ArtifactProperties;
import com.myharness.agent.entity.dto.ArtifactJobDTO;
import com.myharness.agent.entity.dto.ArtifactManifestDTO;
import com.myharness.agent.security.DeviceIdentityProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Captures deliveries once; the durable spool retries bytes without invoking Codex. */
@Component
public class ConversationArtifactService {
    private final AgentProperties agent;
    private final ArtifactProperties limits;
    private final DeviceIdentityProvider identity;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("harness-artifacts").factory());

    public ConversationArtifactService(AgentProperties agent,ArtifactProperties limits,
            DeviceIdentityProvider identity,ObjectMapper json) {
        this.agent=agent;this.limits=limits;this.identity=identity;this.json=json;
    }
    @PostConstruct public void start(){executor.scheduleWithFixedDelay(this::drainSafely,3,15,TimeUnit.SECONDS);}
    @PreDestroy public void close(){executor.shutdownNow();http.shutdownNow();}

    public String instructions(String tid) {
        requireId(tid);
        return "\n\n文件交付约定：如果本轮需要向用户交付文件，请把最终文件保存到当前 Workspace 的 .harness/outputs/"+tid+
                "/，并在该目录写 manifest.json，格式为 {\"files\":[{\"path\":\"report.pdf\",\"name\":\"报告.pdf\"}]}。"+
                "path 必须是相对此输出目录的普通文件路径，name 是下载文件名。仅列出最终交付文件，不列出缓存、凭证或中间文件。"+
                "最多 "+limits.getMaxFiles()+" 个，单文件最多 "+limits.getMaxFileBytes()+" 字节，总计最多 "+limits.getMaxTotalBytes()+
                " 字节。没有交付文件时不要创建清单；最终回答说明交付文件名，平台会提供下载卡片。";
    }

    /** Called before releasing the completed Turn, so a subsequent Turn cannot change its delivery. */
    public synchronized int capture(Path workspace,String tid) {
        requireId(tid);
        List<Path> staged=new ArrayList<>();
        try {
            Path root=workspace.toRealPath();
            Path output=root.resolve(".harness/outputs/"+tid);
            Path manifest=output.resolve("manifest.json");
            if(!Files.exists(manifest,LinkOption.NOFOLLOW_LINKS)) return 0;
            safe(root,manifest);
            byte[] data;
            try(var in=Files.newInputStream(manifest,LinkOption.NOFOLLOW_LINKS)) {data=in.readNBytes(65537);}
            if(data.length>65536) throw new IOException("清单超过 64 KB");
            ArtifactManifestDTO list=json.readValue(data,ArtifactManifestDTO.class);
            if(list.files()==null || list.files().size()>limits.getMaxFiles()) throw new IOException("产物数量超限");
            Set<String> paths=new HashSet<>();
            List<ArtifactJobDTO> jobs=new ArrayList<>();
            Path spool=spool();
            long queued;
            try(var files=Files.list(spool)) {
                var pending=files.filter(p -> p.getFileName().toString().endsWith(".bin")).toList();
                if(pending.size()+list.files().size()>limits.getMaxSpoolFiles()) throw new IOException("待上传快照数量超限");
                queued=pending.stream().mapToLong(p -> {
                    try{return Files.exists(p,LinkOption.NOFOLLOW_LINKS)?Files.size(p):0;}
                    catch(IOException e){throw new UncheckedIOException(e);}
                }).sum();
            }
            long total=0;
            for(ArtifactManifestDTO.FileEntry entry:list.files()) {
                if(entry==null || entry.path()==null || entry.path().isBlank() || entry.path().contains("\\")
                        || entry.path().contains(":") || entry.path().startsWith("/")
                        || Arrays.asList(entry.path().split("/")).contains("..")) throw new IOException("产物相对路径无效");
                Path source=output.resolve(entry.path()).normalize();
                if(!source.startsWith(output) || source.equals(manifest) || !paths.add(source.toString()))
                    throw new IOException("产物路径越界或重复");
                safe(root,source);
                String name=entry.name()==null ? source.getFileName().toString() : entry.name();
                if(name.isBlank() || name.length()>255 || name.contains("/") || name.contains("\\") || name.contains(":")
                        || name.chars().anyMatch(Character::isISOControl) || Set.of(".","..").contains(name))
                    throw new IOException("下载文件名无效");
                BasicFileAttributes before=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(before.size()>limits.getMaxFileBytes()) throw new IOException("产物超过单文件限制");
                total=Math.addExact(total,before.size());
                if(total>limits.getMaxTotalBytes() || total>limits.getMaxSpoolBytes()-queued)
                    throw new IOException("产物或待上传快照总大小超限");
                String key=UUID.randomUUID().toString();
                Path snapshot=spool.resolve(key+".bin"); staged.add(snapshot);
                MessageDigest digest=digest(); long size=0;
                try(var in=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);
                        var out=Files.newOutputStream(snapshot,StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer=new byte[8192]; int count;
                    while((count=in.read(buffer))!=-1) {
                        size+=count; if(size>before.size()) throw new IOException("复制期间文件发生变化");
                        digest.update(buffer,0,count); out.write(buffer,0,count);
                    }
                }
                safe(root,source);
                BasicFileAttributes after=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                String sha=HexFormat.of().formatHex(digest.digest());
                if(size!=before.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !Objects.equals(before.fileKey(),after.fileKey()) || !sha.equals(hash(source,size)))
                    throw new IOException("复制期间文件发生变化，请重新生成产物");
                jobs.add(new ArtifactJobDTO(tid,key,name,size,sha));
            }
            // Expose the entire capture to the uploader only after every file passed validation.
            List<Path> manifests=new ArrayList<>();
            try {
                for(ArtifactJobDTO job:jobs) {
                    Path target=spool.resolve(job.artifactKey()+".json"); manifests.add(target);
                    Path temporary=spool.resolve(job.artifactKey()+".part"); staged.add(temporary);
                    json.writeValue(temporary.toFile(),job);
                    Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
                }
            } catch(IOException e) {
                for(Path path:manifests) Files.deleteIfExists(path);
                throw e;
            }
            staged.clear();
            return jobs.size();
        } catch(IOException | RuntimeException e) {
            for(Path p:staged) {try{Files.deleteIfExists(p);}catch(IOException ignored){}}
            throw new AgentOperationException("ARTIFACT_CAPTURE_FAILED","交付文件准备失败："+e.getMessage(),e);
        }
    }

    /** One bounded pass. FAILED records remain in the spool until the user requests retry. */
    public void drain() throws IOException {
        Path root=spool();
        List<Path> manifests;
        synchronized(this) {
            try(var files=Files.list(root)) {
                manifests=files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                        .sorted().toList();
            }
        }
        for(Path manifest:manifests) {
            if(Thread.currentThread().isInterrupted()) return;
            String stage="READ_QUEUE";
            String turnId="unknown";
            try {
                safe(root,manifest);
                if(Files.size(manifest)>65536) throw new IOException("上传记录无效");
                ArtifactJobDTO job=json.readValue(manifest.toFile(),ArtifactJobDTO.class);
                requireId(job.turnId());
                turnId=job.turnId();
                if(job.artifactKey()==null || !manifest.getFileName().toString().equals(job.artifactKey()+".json"))
                    throw new IOException("上传记录标识无效");
                var body=json.valueToTree(job).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode)body).remove("turnId");
                String base="/api/v1/agent/turns/"+job.turnId()+"/artifacts";
                stage="REGISTER";
                var state=request("POST",base,HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)),"application/json");
                String aid=state.path("id").asText(); requireId(aid);
                if("READY".equals(state.path("status").asText())) {stage="CLEANUP";remove(root,job,manifest);continue;}
                if(!"UPLOADING".equals(state.path("status").asText())) continue;
                try {
                    stage="VERIFY_SNAPSHOT";
                    Path snapshot=root.resolve(job.artifactKey()+".bin"); safe(root,snapshot);
                    if(!hash(snapshot,job.sizeBytes()).equals(job.sha256())) throw new IOException("上传快照已损坏");
                    stage="UPLOAD";
                    var result=request("PUT",base+"/"+aid+"/content",HttpRequest.BodyPublishers.ofFile(snapshot),"application/octet-stream");
                    if("READY".equals(result.path("status").asText())) {stage="CLEANUP";remove(root,job,manifest);}
                } catch(IOException | RuntimeException e) {
                    logDeferred(manifest,turnId,stage,e);
                    // If the PUT response was lost, FAILED is conditional and cannot undo READY.
                    stage="REPORT_FAILURE";
                    request("POST",base+"/"+aid+"/failed",HttpRequest.BodyPublishers.noBody(),"application/json");
                }
            } catch(IOException | RuntimeException e) {
                logDeferred(manifest,turnId,stage,e);
            }
        }
        cleanOrphans(root);
    }
    // Use the capture lock only for spool mutations, never for network transfers.
    private synchronized void cleanOrphans(Path root) throws IOException {
        // Only orphaned snapshots/temporary files are disposable; pending deliveries remain durable.
        try(var files=Files.list(root)) {
            for(Path p:files.filter(f -> f.getFileName().toString().matches("[a-f0-9-]{36}\\.(bin|part)")).toList()) {
                String key=p.getFileName().toString().substring(0,36);
                if(!Files.exists(root.resolve(key+".json")) && Files.getLastModifiedTime(p,LinkOption.NOFOLLOW_LINKS).toMillis()
                        <System.currentTimeMillis()-86400000L) Files.deleteIfExists(p);
            }
        }
    }
    private void drainSafely() {
        try{drain();}catch(IOException | RuntimeException e){
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Artifact queue will retry: cause={}",e.getClass().getSimpleName());
        }
    }
    private void logDeferred(Path manifest,String turnId,String stage,Exception failure) {
        // Never log response bodies, authorization headers, arbitrary exception messages or file contents.
        String reason=failure instanceof ArtifactHttpException httpFailure
                ? "httpStatus="+httpFailure.status+" apiCode="+httpFailure.apiCode
                : "cause="+failure.getClass().getSimpleName();
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Artifact transfer deferred: file={} turnId={} stage={} {}",
                manifest.getFileName(),turnId,stage,reason);
    }
    private static final class ArtifactHttpException extends IOException {
        private final int status;
        private final Integer apiCode;
        private ArtifactHttpException(int status,Integer apiCode) {
            super("Artifact HTTP request rejected");this.status=status;this.apiCode=apiCode;
        }
    }
    private com.fasterxml.jackson.databind.JsonNode request(String method,String path,HttpRequest.BodyPublisher body,String type) throws IOException {
        URI base=agent.getEnrollmentUrl();
        if(base==null || base.getHost()==null || !("https".equalsIgnoreCase(base.getScheme())
                || "http".equalsIgnoreCase(base.getScheme()) && Set.of("localhost","127.0.0.1","::1","[::1]").contains(base.getHost())))
            throw new IOException("产物上传需要 HTTPS 或本机 HTTP 服务端");
        var device=identity.get();
        HttpRequest request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(60))
                .header("Authorization","Bearer "+device.getDeviceToken())
                .header("X-Harness-Device-Code",device.getDeviceCode()).header("Content-Type",type).method(method,body).build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200) {
                Integer code=null;
                if(response.body().length<=65536) {
                    try {
                        var error=json.readTree(response.body());
                        if(error!=null && error.path("code").isIntegralNumber() && error.path("code").canConvertToInt())
                            code=error.path("code").intValue();
                    } catch(IOException ignored) { /* An HTML proxy error still has a useful HTTP status. */ }
                }
                throw new ArtifactHttpException(response.statusCode(),code);
            }
            if(response.body().length>65536) throw new IOException("产物响应超过限制");
            var result=json.readTree(response.body());
            if(result==null || !"success".equals(result.path("status").asText()))
                throw new ArtifactHttpException(response.statusCode(),result!=null && result.path("code").isIntegralNumber()
                        && result.path("code").canConvertToInt() ? result.path("code").intValue() : null);
            return result.path("data");
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("上传已停止",e);}
    }
    private Path spool() throws IOException {
        Path root=agent.getDataDir().toAbsolutePath().normalize(); Files.createDirectories(root); root=root.toRealPath();
        Path result=root.resolve("artifact-spool"); Files.createDirectories(result);
        if(!result.toRealPath().equals(result)) throw new IOException("上传队列目录不能包含链接");
        return result;
    }
    private void safe(Path root,Path file) throws IOException {
        Path normalized=file.toAbsolutePath().normalize();
        if(!normalized.startsWith(root)) throw new IOException("产物路径越界");
        Path current=root;
        for(Path segment:root.relativize(normalized)) {
            current=current.resolve(segment);
            var attrs=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attrs.isSymbolicLink() || attrs.isOther() || !current.toRealPath().equals(current))
                throw new IOException("产物路径不能包含链接或重解析点");
        }
        if(!Files.isRegularFile(normalized,LinkOption.NOFOLLOW_LINKS)) throw new IOException("产物必须是普通文件");
    }
    private String hash(Path file,long expected) throws IOException {
        if(expected<0 || expected>limits.getMaxFileBytes()) throw new IOException("快照大小超限");
        MessageDigest digest=digest(); long size=0;
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=new byte[8192]; int count;
            while((count=in.read(bytes))!=-1) {size+=count;if(size>expected)throw new IOException("文件大小发生变化");digest.update(bytes,0,count);}
        }
        if(size!=expected) throw new IOException("文件大小发生变化");
        return HexFormat.of().formatHex(digest.digest());
    }
    private synchronized void remove(Path root,ArtifactJobDTO job,Path manifest) throws IOException {
        Files.deleteIfExists(root.resolve(job.artifactKey()+".bin")); Files.deleteIfExists(manifest);
    }
    private void requireId(String value){if(value==null || !value.matches("[1-9][0-9]{0,19}"))throw new AgentOperationException("INVALID_ARTIFACT_ID","产物 Turn 标识无效");}
    private MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
}
