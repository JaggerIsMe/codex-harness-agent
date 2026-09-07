package com.myharness.agent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.WorkspaceFileCommandDTO;
import com.myharness.agent.entity.dto.WorkspaceFileResultDTO;
import com.myharness.agent.entity.vo.WorkspaceFileEntryVO;
import com.myharness.agent.security.DeviceIdentityProvider;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** File operations never invoke Codex. All public operations cross the same checked path seam. */
@Component
public class WorkspaceFileService {
    private static final int PAGE_SIZE = 200;
    private static final Set<String> HIDDEN_NAMES = Set.of(".codex", ".git", ".harness", ".agent", ".agents", ".harness-workspace.json");
    private final WorkspaceRegistry workspaces;
    private final AgentProperties properties;
    private final DeviceIdentityProvider identity;
    private final ObjectMapper json;

    public WorkspaceFileService(WorkspaceRegistry workspaces, AgentProperties properties,
                                DeviceIdentityProvider identity, ObjectMapper json) {
        this.workspaces=workspaces; this.properties=properties; this.identity=identity; this.json=json;
    }

    public WorkspaceFileResultDTO execute(String kind, WorkspaceFileCommandDTO command) {
        try {
            if (command==null || command.operationId()==null || !command.operationId().matches("[1-9][0-9]{0,18}"))
                throw new IOException("操作标识无效");
            authorize(command);
            return switch(kind) {
                case "SYNC_WORKSPACE_TREE" -> list(command);
                case "CREATE_WORKSPACE_DIRECTORY" -> create(command);
                case "UPLOAD_WORKSPACE_FILE" -> upload(command);
                case "PREPARE_WORKSPACE_DOWNLOAD" -> download(command);
                default -> throw new IOException("不支持的文件操作");
            };
        } catch(Exception error) {
            return new WorkspaceFileResultDTO(command==null ? null : command.operationId(),false,
                    error.getMessage()==null ? "文件操作失败" : error.getMessage(),List.of(),null,0,0,null);
        }
    }

    public Path checkedPath(String workspace, String relative, boolean write) throws IOException {
        validateRelative(relative);
        Path root=workspaces.resolve(workspace,"").toRealPath();
        Path target=root;
        if (!relative.isEmpty()) for(String segment:relative.split("/")) {
            if (write && Set.of(".git",".codex",".agents",".harness",".harness-workspace.json").contains(segment.toLowerCase(Locale.ROOT)))
                throw new IOException("不能修改受保护的工作区路径");
            target=target.resolve(segment);
            if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !target.equals(target.toRealPath()))
                    throw new IOException("不支持链接或重解析路径");
            }
        }
        if (!target.startsWith(root) || !target.equals(workspaces.resolve(workspace,relative)))
            throw new IOException("路径超出工作区");
        return target;
    }

    public static void validateRelative(String path) throws IOException {
        if (path==null || path.length()>2048 || path.startsWith("/") || path.contains("\\")) throw new IOException("工作区相对路径无效");
        if (path.isEmpty()) return;
        for(String part:path.split("/",-1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.length()>255 || part.endsWith(".") || part.endsWith(" ") ||
                    part.chars().anyMatch(c -> c<32 || c==127 || "<>:\"|?*".indexOf(c)>=0) ||
                    part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) throw new IOException("文件或目录名称无效");
        }
    }

    private WorkspaceFileResultDTO list(WorkspaceFileCommandDTO c) throws IOException {
        Path directory=checkedPath(c.workspaceName(),c.path(),false);
        if (!visiblePath(c.path())) throw new IOException("内部目录不在工作区目录树中展示");
        if (!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) throw new IOException("目录不存在");
        String cursor=c.cursor()==null ? "" : c.cursor();
        Comparator<Path> order=Comparator.comparing(p -> p.getFileName().toString());
        PriorityQueue<Path> candidates=new PriorityQueue<>(PAGE_SIZE+1,order.reversed());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        int visited=0;
        try(var stream=Files.newDirectoryStream(directory)) {
            for(Path p:stream) {
                if (++visited>200000 || System.nanoTime()>deadline) throw new IOException("目录过大，请缩小目录范围后重试");
                String name=p.getFileName().toString();
                if (!visiblePath(name) || name.compareTo(cursor)<=0) continue;
                candidates.add(p);
                if (candidates.size()>PAGE_SIZE+1) candidates.poll();
            }
        }
        List<Path> paths=candidates.stream().sorted(order).toList();
        List<WorkspaceFileEntryVO> entries=new ArrayList<>();
        for(Path p:paths.subList(0,Math.min(PAGE_SIZE,paths.size()))) {
            String name=p.getFileName().toString();
            String relative=c.path().isEmpty() ? name : c.path()+"/"+name;
            String type="UNAVAILABLE"; long size=0,modified=0;
            try {
                Path safe=checkedPath(c.workspaceName(),relative,false);
                BasicFileAttributes a=Files.readAttributes(safe,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                type=a.isDirectory() ? "DIRECTORY" : a.isRegularFile() ? "FILE" : "UNAVAILABLE";
                size=a.isRegularFile() ? a.size() : 0; modified=a.lastModifiedTime().toMillis();
            } catch(IOException|RuntimeException ignored) { /* Keep unreadable entries visible without following links. */ }
            entries.add(new WorkspaceFileEntryVO(name,relative,type,size,modified));
        }
        return new WorkspaceFileResultDTO(c.operationId(),true,null,List.copyOf(entries),
                paths.size()>PAGE_SIZE ? paths.get(PAGE_SIZE-1).getFileName().toString() : null,System.currentTimeMillis(),0,null);
    }

    private static boolean visiblePath(String path) {
        for (String part : path.split("/")) {
            String name = part.toLowerCase(Locale.ROOT);
            if (HIDDEN_NAMES.contains(name) || name.startsWith(".harness-upload-")) return false;
        }
        return true;
    }

    private WorkspaceFileResultDTO create(WorkspaceFileCommandDTO c) throws IOException {
        claim(c);
        if (c.path().isEmpty()) throw new IOException("不能创建工作区根目录");
        Files.createDirectory(checkedPath(c.workspaceName(),c.path(),true));
        return success(c,0,null);
    }

    private WorkspaceFileResultDTO upload(WorkspaceFileCommandDTO c) throws IOException {
        claim(c);
        if (c.path().isEmpty() || c.sizeBytes()<0 || c.sizeBytes()>properties.getMaxAttachmentBytes() ||
                c.sha256()==null || !c.sha256().matches("[a-f0-9]{64}")) throw new IOException("上传文件超限或元数据无效");
        Path target=checkedPath(c.workspaceName(),c.path(),true);
        if (Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IOException("同名文件已存在，请改名后上传");
        Path temporary=Files.createTempFile(target.getParent(),".harness-upload-",".part");
        try {
            HttpURLConnection connection=open(c,"/content","GET");
            String hash;
            try {
                requireOk(connection);
                try(var input=connection.getInputStream();var output=Files.newOutputStream(temporary)) {
                    hash=copy(input,output,c.sizeBytes(),true);
                }
            } finally {connection.disconnect();}
            if (!c.sha256().equals(hash)) throw new IOException("文件校验失败");
            authorize(c);
            if (!target.equals(checkedPath(c.workspaceName(),c.path(),true))) throw new IOException("上传期间目录发生变化");
            // ATOMIC_MOVE may replace an existing target on some providers. A hard link publishes
            // a complete file with CREATE_NEW semantics, then the temporary name is removed.
            Files.createLink(target,temporary);
            return success(c,c.sizeBytes(),hash);
        } finally {Files.deleteIfExists(temporary);}
    }

    private WorkspaceFileResultDTO download(WorkspaceFileCommandDTO c) throws IOException {
        Path target=checkedPath(c.workspaceName(),c.path(),false);
        BasicFileAttributes before=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size()>properties.getMaxAttachmentBytes()) throw new IOException("不是普通文件或超过下载大小限制");
        Path spool=properties.getDataDir().resolve("workspace-transfers"); Files.createDirectories(spool);
        Path temporary=Files.createTempFile(spool,"download-",".part");
        try {
            String hash;
            try(var input=Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS);var output=Files.newOutputStream(temporary)) {
                hash=copy(input,output,before.size(),true);
            }
            BasicFileAttributes after=Files.readAttributes(checkedPath(c.workspaceName(),c.path(),false),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (!before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size()!=after.size() || !Objects.equals(before.fileKey(),after.fileKey()))
                throw new IOException("文件在下载准备期间发生变化，请重试");
            HttpURLConnection connection=open(c,"/content","PUT");
            connection.setDoOutput(true); connection.setFixedLengthStreamingMode(before.size());
            connection.setRequestProperty("Content-Type","application/octet-stream");
            connection.setRequestProperty("X-Content-SHA256",hash);
            try {
                try(var input=Files.newInputStream(temporary);var output=connection.getOutputStream()) {copy(input,output,before.size(),true);}
                requireOk(connection);
            } finally {connection.disconnect();}
            return success(c,before.size(),hash);
        } finally {Files.deleteIfExists(temporary);}
    }

    private void authorize(WorkspaceFileCommandDTO c) throws IOException {
        HttpURLConnection connection=open(c,"","GET");
        try {
            requireOk(connection);
            try(var input=connection.getInputStream()) {
                byte[] data=input.readNBytes(32769);
                if(data.length>32768) throw new IOException("文件操作授权响应超限");
                var response=json.readTree(data);
                if (!"success".equals(response.path("status").asText()) || !c.equals(json.treeToValue(response.path("data"),WorkspaceFileCommandDTO.class)))
                    throw new IOException("文件操作已失效或授权不匹配");
            }
        } finally {connection.disconnect();}
    }

    private HttpURLConnection open(WorkspaceFileCommandDTO c,String suffix,String method) throws IOException {
        URI base=properties.getEnrollmentUrl();
        if(base==null || !("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()) && Set.of("localhost","127.0.0.1","::1","[::1]").contains(base.getHost())))
            throw new IOException("文件传输需要 HTTPS 或本机 HTTP");
        HttpURLConnection connection=(HttpURLConnection)base.resolve("/api/v1/agent/workspace-file-operations/"+c.operationId()+suffix).toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        var device=identity.get();
        connection.setRequestProperty("Authorization","Bearer "+device.getDeviceToken());
        connection.setRequestProperty("X-Harness-Device-Code",device.getDeviceCode());
        return connection;
    }

    private void claim(WorkspaceFileCommandDTO c) throws IOException {
        Path ledger=properties.getDataDir().resolve("workspace-file-claims"); Files.createDirectories(ledger);
        // Persist before mutation; an ambiguous restart never repeats a write.
        try(var channel=java.nio.channels.FileChannel.open(ledger.resolve(c.operationId()),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1})); channel.force(true);
        } catch(FileAlreadyExistsException e) {throw new IOException("此操作已尝试执行，请刷新目录核实结果后再操作");}
    }

    private static void requireOk(HttpURLConnection connection) throws IOException {
        if(connection.getResponseCode()!=200) throw new IOException("文件传输或授权失败，HTTP "+connection.getResponseCode());
    }
    private static String copy(InputStream input,OutputStream output,long limit,boolean exact) throws IOException {
        MessageDigest digest;
        try {digest=MessageDigest.getInstance("SHA-256");} catch(Exception e) {throw new IllegalStateException(e);}
        byte[] buffer=new byte[8192]; long total=0; int n;
        while((n=input.read(buffer))!=-1) {
            if(Thread.currentThread().isInterrupted()) throw new IOException("文件操作已取消");
            total+=n; if(total>limit) throw new IOException("文件大小超过限制");
            digest.update(buffer,0,n); output.write(buffer,0,n);
        }
        if(exact && total!=limit) throw new IOException("文件大小不匹配");
        return HexFormat.of().formatHex(digest.digest());
    }
    private WorkspaceFileResultDTO success(WorkspaceFileCommandDTO c,long size,String sha) {
        return new WorkspaceFileResultDTO(c.operationId(),true,null,List.of(),null,0,size,sha);
    }
}
