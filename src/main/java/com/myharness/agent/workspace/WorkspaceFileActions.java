package com.myharness.agent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Owns checked filesystem changes, plans, archive snapshots and durable replay. */
final class WorkspaceFileActions {
    interface Transport {
        void upload(WorkspaceFileCommandDTO command,String suffix,Path bytes,String sha) throws IOException;
    }
    record PlanEntry(String path,String entryType,String revision,String identity,long sizeBytes) {}
    record DeletePlan(String projectId,String workspaceName,String rootIdentity,String instance,
                      WorkspaceDeletePlanDTO summary,List<PlanEntry> entries) {}
    private final WorkspacePathPolicy paths;
    private final AgentProperties properties;
    private final ObjectMapper json;
    private final WorkspaceExecutionCoordinator coordination;
    private final WorkspaceOperationJournal journal;
    private final Transport transport;
    private final Path plans;
    private final String instance=UUID.randomUUID().toString();
    private final Object[] operationLocks=java.util.stream.IntStream.range(0,64).mapToObj(ignored -> new Object()).toArray();
    WorkspaceFileActions(WorkspacePathPolicy paths,AgentProperties properties,ObjectMapper json,
            WorkspaceExecutionCoordinator coordination,Transport transport) throws IOException {
        this.paths=paths;this.properties=properties;this.json=json;this.coordination=coordination;this.transport=transport;
        this.plans=properties.getDataDir().resolve("workspace-delete-plans");Files.createDirectories(plans);
        this.journal=new WorkspaceOperationJournal(properties.getDataDir(),json);journal.restore(coordination);
        cleanup();
    }
    static boolean handles(String kind) {return Set.of("RELOCATE_WORKSPACE_ENTRY","PREPARE_WORKSPACE_DELETE",
            "DELETE_WORKSPACE_ENTRY","PREPARE_WORKSPACE_ARCHIVE","RECONCILE_WORKSPACE_OPERATION").contains(kind);}
    static boolean mutation(String kind){return "RELOCATE_WORKSPACE_ENTRY".equals(kind)||"DELETE_WORKSPACE_ENTRY".equals(kind);}
    void cleanup() throws IOException {
        // These are Agent-owned temporary namespaces, never Workspace paths. Do not follow links or recurse.
        cleanDirectory(plans,120_000L,name -> name.matches("[a-f0-9-]{36}\\.(json|used)"));
        cleanDirectory(properties.getDataDir().resolve("workspace-transfers"),86_400_000L,
                name -> name.matches("(archive-|download-).+\\.(zip|part)"));
        cleanDirectory(properties.getDataDir(),86_400_000L,name -> name.matches("workspace-items-.+\\.json"));
    }
    private void cleanDirectory(Path directory,long age,java.util.function.Predicate<String> owned) throws IOException {
        Path data=properties.getDataDir().toAbsolutePath().normalize(),target=directory.toAbsolutePath().normalize();
        if(!target.startsWith(data)||Files.isSymbolicLink(target)||!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS)
                ||!target.equals(target.toRealPath()))return;
        long cutoff=System.currentTimeMillis()-age;int visited=0;
        try(var stream=Files.newDirectoryStream(target)) {
            for(Path path:stream) {
                if(++visited>20000)break;
                if(!path.toAbsolutePath().normalize().getParent().equals(target)||!owned.test(path.getFileName().toString()))continue;
                var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(a.isRegularFile()&&!a.isSymbolicLink()&&a.lastModifiedTime().toMillis()<cutoff)Files.deleteIfExists(path);
            }
        }
    }
    WorkspaceFileResultDTO execute(String kind,WorkspaceFileCommandDTO command) {
        synchronized(operationLocks[Math.floorMod(command.operationId().hashCode(),operationLocks.length)]) {
            Path root=null;boolean claimed=false;
            try {
                root=paths.checked(command.workspaceName(),"");
                if("RECONCILE_WORKSPACE_OPERATION".equals(kind))return reconcile(command,root);
                WorkspaceOperationJournal.Claim previous=journal.claim(command.operationId());
                String digest=journal.digest(kind,command);
                if(previous!=null&&!previous.digest().equals(digest))
                    throw new WorkspaceFileException("REQUEST_CONFLICT","同一操作标识不能用于不同参数");
                var terminal=journal.terminal(command.operationId());
                if(terminal!=null)return deliver(command,terminal);
                if(previous!=null) {if(mutation(kind))coordination.holdUnknown(root,command.operationId());return unknown(command);}
                try(var lease=mutation(kind)?coordination.enterMutation(root,command.operationId()):coordination.enterRead(root)) {
                    if(mutation(kind)) {
                        if(!WindowsWorkspaceHandles.supported())throw new WorkspaceFileException("UNSUPPORTED_FILESYSTEM","当前平台不支持安全的工作区文件变更");
                    }
                    journal.claim(new WorkspaceOperationJournal.Claim(command.operationId(),digest,command.workspaceName(),root.toString(),kind));
                    claimed=mutation(kind);
                    WorkspaceOperationJournal.Terminal result;
                    try {
                        result=switch(kind) {
                            case "RELOCATE_WORKSPACE_ENTRY" -> relocate(command,root);
                            case "PREPARE_WORKSPACE_DELETE" -> plan(command,root);
                            case "DELETE_WORKSPACE_ENTRY" -> delete(command,root);
                            case "PREPARE_WORKSPACE_ARCHIVE" -> archive(command,root);
                            default -> throw new WorkspaceFileException("UNSUPPORTED_OPERATION","不支持的文件操作");
                        };
                    }catch(IOException|com.myharness.agent.command.AgentOperationException error) {
                        result=terminal(failure(command,error),List.of());
                    }
                    journal.complete(command.operationId(),result);
                    return deliver(command,result);
                }
            } catch(Exception error) {
                if(claimed && root!=null) {
                    // Even a failed result upload may hide a completed mutation from Server. Replay the journal on reconciliation.
                    coordination.holdUnknown(root,command.operationId());return unknown(command);
                }
                return failure(command,error);
            }
        }
    }
    private WorkspaceFileResultDTO reconcile(WorkspaceFileCommandDTO c,Path root) throws IOException {
        if(!c.operationId().equals(c.originalOperationId()))throw new WorkspaceFileException("INVALID_OPERATION","核实标识不匹配");
        var claim=journal.claim(c.operationId());
        if(claim!=null&&!claim.digest().equals(journal.digest(claim.kind(),c)))
            throw new WorkspaceFileException("REQUEST_CONFLICT","核实参数与原操作不一致");
        var terminal=journal.terminal(c.operationId());
        if(terminal==null){if(claim==null||mutation(claim.kind()))coordination.holdUnknown(root,c.operationId());return unknown(c);}
        WorkspaceFileResultDTO result=deliver(c,terminal);coordination.resolved(root,c.operationId());return result;
    }
    private WorkspaceFileResultDTO deliver(WorkspaceFileCommandDTO c,WorkspaceOperationJournal.Terminal result) throws IOException {
        if(!result.items().isEmpty()) {
            byte[] data=json.writeValueAsBytes(new ItemPayload(result.items()));
            Path temp=Files.createTempFile(properties.getDataDir(),"workspace-items-",".json");
            try {Files.write(temp,data);transport.upload(c,"/items",temp,WorkspacePathPolicy.digest(data));}
            finally {Files.deleteIfExists(temp);}
        }
        return result.result();
    }
    record ItemPayload(List<WorkspaceFileItemResultDTO> items) {}
    private WorkspaceOperationJournal.Terminal relocate(WorkspaceFileCommandDTO c,Path root) throws IOException {
        nonRoot(c.path());nonRoot(c.targetPath());
        Path source=paths.checked(c.workspaceName(),c.path());Path target=paths.checked(c.workspaceName(),c.targetPath());
        if(c.path().equals(c.targetPath()))throw new WorkspaceFileException("NO_CHANGE","源和目标相同");
        if(c.path().equalsIgnoreCase(c.targetPath()))throw new WorkspaceFileException("CASE_ONLY_RENAME_UNSUPPORTED","暂不支持仅修改名称大小写");
        if(!Files.isDirectory(target.getParent(),LinkOption.NOFOLLOW_LINKS))throw new WorkspaceFileException("TARGET_DIRECTORY_MISSING","目标目录不存在");
        try(var pins=WindowsWorkspaceHandles.pin(root,source.getParent(),target.getParent());var handle=WindowsWorkspaceHandles.entry(source,true)) {
            paths.expected(c.workspaceName(),c.path(),c.expectedRevision());
            BasicFileAttributes attributes=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            String type=attributes.isDirectory()?"DIRECTORY":"FILE";
            if(attributes.isDirectory()) {
                if(!source.getParent().equals(target.getParent()))throw new WorkspaceFileException("DIRECTORY_MOVE_UNSUPPORTED","本版仅支持移动文件，目录只能重命名");
                List<PlanEntry> before=scan(c.workspaceName(),c.path());
                if(!before.equals(scan(c.workspaceName(),c.path())))throw new WorkspaceFileException("SOURCE_CHANGED","目录内容正在变化，请结束写入后再重命名");
            }else if(!attributes.isRegularFile())throw new WorkspaceFileException("UNSUPPORTED_ENTRY","只支持普通文件或目录");
            paths.expected(c.workspaceName(),c.path(),c.expectedRevision());
            handle.relocate(target);
            String revision=null;
            try {revision=paths.revision(c.workspaceName(),c.targetPath());}
            catch(IOException ignored) { /* The native handle confirmed relocation. Refresh metadata independently. */ }
            return terminal(result(c,"SUCCEEDED","COMPLETE",null,null,type,revision,null,null,0,null,null),List.of());
        }
    }
    private WorkspaceOperationJournal.Terminal plan(WorkspaceFileCommandDTO c,Path root) throws IOException {
        nonRoot(c.path());
        if(!WindowsWorkspaceHandles.supported())throw new WorkspaceFileException("UNSUPPORTED_FILESYSTEM","当前平台不支持安全删除");
        Path source=paths.checked(c.workspaceName(),c.path());
        try(var pins=WindowsWorkspaceHandles.pin(root,source.getParent());var handle=WindowsWorkspaceHandles.entry(source,false)) {
            paths.expected(c.workspaceName(),c.path(),c.expectedRevision());
            List<PlanEntry> entries=scan(c.workspaceName(),c.path());
            String digest=WorkspacePathPolicy.digest(json.writeValueAsBytes(entries));String id=UUID.randomUUID().toString();
            long files=entries.stream().filter(e -> "FILE".equals(e.entryType())).count();
            long total=0;for(var entry:entries)total=Math.addExact(total,entry.sizeBytes());
            var summary=new WorkspaceDeletePlanDTO(id,digest,c.path(),entries.getFirst().entryType(),c.expectedRevision(),
                    files,entries.size()-files,total,Instant.now().plusSeconds(120).toEpochMilli());
            var plan=new DeletePlan(c.projectId(),c.workspaceName(),WorkspacePathPolicy.identity(root),instance,summary,entries);
            byte[] data=json.writeValueAsBytes(plan);if(data.length>8*1024*1024)throw new WorkspaceFileException("LIMIT_EXCEEDED","删除检查清单过大");
            WorkspaceOperationJournal.writeNew(plans.resolve(id+".json"),data);
            return terminal(result(c,"SUCCEEDED","COMPLETE",null,null,summary.entryType(),c.expectedRevision(),summary,null,0,null,null),List.of());
        }
    }
    private List<PlanEntry> scan(String workspace,String relative) throws IOException {
        List<PlanEntry> result=new ArrayList<>();long deadline=System.nanoTime()+10_000_000_000L;
        scanEntry(workspace,relative,0,deadline,result);
        if(json.writeValueAsBytes(result).length>8*1024*1024)throw new WorkspaceFileException("LIMIT_EXCEEDED","删除检查清单过大");
        return List.copyOf(result);
    }
    private void scanEntry(String workspace,String relative,int depth,long deadline,List<PlanEntry> result) throws IOException {
        if(depth>64||result.size()>=10000||System.nanoTime()>deadline||Thread.currentThread().isInterrupted())
            throw new WorkspaceFileException("LIMIT_EXCEEDED","目录检查超过条目、深度或时间限制");
        Path entry=paths.checked(workspace,relative);BasicFileAttributes a=Files.readAttributes(entry,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isDirectory()&&!a.isRegularFile())throw new WorkspaceFileException("UNSUPPORTED_ENTRY","目录包含链接或特殊文件");
        result.add(new PlanEntry(relative,a.isDirectory()?"DIRECTORY":"FILE",paths.revision(workspace,relative),WorkspacePathPolicy.identity(entry),a.isRegularFile()?a.size():0));
        if(a.isDirectory()) {
            List<String> children=new ArrayList<>();
            try(var stream=Files.newDirectoryStream(entry)) {
                for(Path child:stream) {
                    if(children.size()+result.size()>=10000||System.nanoTime()>deadline)throw new WorkspaceFileException("LIMIT_EXCEEDED","目录检查超过限制");
                    children.add(child.getFileName().toString());
                }
            }
            Collections.sort(children);
            for(String child:children)scanEntry(workspace,relative+"/"+child,depth+1,deadline,result);
        }
    }
    private WorkspaceOperationJournal.Terminal delete(WorkspaceFileCommandDTO c,Path root) throws IOException {
        if(c.planId()==null||!c.planId().matches("[a-f0-9-]{36}")||c.planDigest()==null)throw new WorkspaceFileException("INVALID_PLAN","删除计划无效");
        Path planPath=plans.resolve(c.planId()+".json");
        if(!Files.exists(planPath))throw new WorkspaceFileException("PLAN_EXPIRED","删除计划已失效，请重新检查");
        DeletePlan plan=json.readValue(Files.readAllBytes(planPath),DeletePlan.class);
        if(!plan.instance().equals(instance)||plan.summary().expiresAt()<System.currentTimeMillis())throw new WorkspaceFileException("PLAN_EXPIRED","删除计划已过期，请重新检查");
        if(!Objects.equals(plan.projectId(),c.projectId())||!Objects.equals(plan.workspaceName(),c.workspaceName())||
                !plan.rootIdentity().equals(WorkspacePathPolicy.identity(root))||!plan.summary().planDigest().equals(c.planDigest())||
                !Objects.equals(plan.summary().path(),c.path()))throw new WorkspaceFileException("INVALID_PLAN","删除计划归属或摘要不匹配");
        try {WorkspaceOperationJournal.writeNew(plans.resolve(c.planId()+".used"),c.operationId().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(FileAlreadyExistsException e){throw new WorkspaceFileException("PLAN_CONSUMED","删除计划已被使用，请重新检查");}
        List<PlanEntry> current=scan(c.workspaceName(),c.path());
        if(!plan.entries().equals(current))throw new WorkspaceFileException("SOURCE_CHANGED","删除范围已变化，请重新检查并确认");
        List<PlanEntry> ordered=new ArrayList<>(plan.entries());Collections.reverse(ordered);
        List<WorkspaceFileItemResultDTO> items=new ArrayList<>();long files=0,dirs=0;IOException failure=null;
        for(PlanEntry entry:ordered) {
            if(failure!=null) {items.add(item(entry,"REMAINING",null,null));continue;}
            try {
                Path target=paths.checked(c.workspaceName(),entry.path());
                try(var pins=WindowsWorkspaceHandles.pin(root,target.getParent());var handle=WindowsWorkspaceHandles.entry(target,true)) {
                    if("DIRECTORY".equals(entry.entryType())) {
                        if(!entry.identity().equals(WorkspacePathPolicy.identity(target)))throw new WorkspaceFileException("SOURCE_CHANGED","目录身份发生变化");
                        try(var stream=Files.newDirectoryStream(target)) {
                            if(stream.iterator().hasNext())throw new WorkspaceFileException("SOURCE_CHANGED","目录出现未确认的新文件");
                        }
                    }else paths.expected(c.workspaceName(),entry.path(),entry.revision());
                    handle.delete();
                }
                if("FILE".equals(entry.entryType()))files++;else dirs++;
                items.add(item(entry,"DELETED",null,null));
            }catch(IOException e) {failure=e;items.add(item(entry,"REMAINING",code(e),safe(e)));}
        }
        String status=failure==null?"SUCCEEDED":files+dirs>0?"PARTIAL_FAILED":"FAILED";
        var summary=new WorkspaceFileSummaryDTO(files,dirs,plan.entries().size()-files-dirs);
        String digest=WorkspacePathPolicy.digest(json.writeValueAsBytes(new ItemPayload(items)));
        return terminal(result(c,status,failure==null?"COMPLETE":files+dirs>0?"PARTIAL":"NO_CHANGE",failure==null?null:code(failure),
                failure==null?null:safe(failure),plan.summary().entryType(),null,null,summary,0,null,digest),items);
    }
    private WorkspaceOperationJournal.Terminal archive(WorkspaceFileCommandDTO c,Path root) throws IOException {
        WorkspaceFileLimitsDTO limits=effective(c.limits());
        if(c.items()==null||c.items().isEmpty()||c.items().size()>limits.maxFiles())throw new WorkspaceFileException("LIMIT_EXCEEDED","请选择限制数量内的普通文件");
        if(json.writeValueAsBytes(c).length>limits.maxRequestBytes()||json.writeValueAsBytes(c.items()).length>480*1024)throw new WorkspaceFileException("LIMIT_EXCEEDED","选择请求过大");
        LinkedHashMap<String,WorkspaceArchiveItemDTO> selected=new LinkedHashMap<>();Set<String> folded=new HashSet<>();
        for(var item:c.items()) {
            nonRoot(item.path());paths.checked(c.workspaceName(),item.path());
            if(selected.containsKey(item.path())) {
                if(!Objects.equals(selected.get(item.path()).expectedRevision(),item.expectedRevision()))throw new WorkspaceFileException("SOURCE_CHANGED","重复文件版本不一致");
                continue;
            }
            if(!folded.add(item.path().toLowerCase(Locale.ROOT)))throw new WorkspaceFileException("ARCHIVE_NAME_COLLISION","所选路径存在大小写冲突");
            selected.put(item.path(),item);
        }
        Path spool=properties.getDataDir().resolve("workspace-transfers");Files.createDirectories(spool);
        Path temporary=Files.createTempFile(spool,"archive-",".zip");List<WorkspaceFileItemResultDTO> items=new ArrayList<>();
        long total=0,deadline=System.nanoTime()+limits.maxDurationSeconds()*1_000_000_000L;
        try {
            try(var file=Files.newOutputStream(temporary);var bounded=new BoundedOutput(file,limits.maxOutputBytes());
                    var zip=new ZipOutputStream(bounded,java.nio.charset.StandardCharsets.UTF_8)) {
                for(var item:selected.values()) {
                    try {
                        Path source=paths.checked(c.workspaceName(),item.path());
                        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new WorkspaceFileException("UNSUPPORTED_ENTRY","ZIP 只能包含普通文件");
                        try(var reader=WorkspaceReadHandle.open(root,source)) {
                            paths.expected(c.workspaceName(),item.path(),item.expectedRevision());
                            BasicFileAttributes before=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                            if(!before.isRegularFile())throw new WorkspaceFileException("UNSUPPORTED_ENTRY","ZIP 只能包含普通文件");
                            if(before.size()>limits.maxFileBytes()||before.size()>limits.maxTotalBytes()-total)throw new WorkspaceFileException("LIMIT_EXCEEDED","所选文件大小超过打包限制");
                            total+=before.size();ZipEntry zipEntry=new ZipEntry(item.path());zip.putNextEntry(zipEntry);
                            java.security.MessageDigest digest;
                            try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
                            long read=0;byte[] buffer=new byte[8192];
                            try(var input=reader.input()) {
                                int n;while((n=input.read(buffer))!=-1) {
                                    if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw new WorkspaceFileException("LIMIT_EXCEEDED","打包超过时间限制");
                                    read+=n;if(read>before.size())throw new WorkspaceFileException("SOURCE_CHANGED","打包期间文件发生变化");
                                    digest.update(buffer,0,n);zip.write(buffer,0,n);
                                }
                            }
                            if(read!=before.size())throw new WorkspaceFileException("SOURCE_CHANGED","打包期间文件发生变化");
                            paths.expected(c.workspaceName(),item.path(),item.expectedRevision());
                            zipEntry.setComment("SHA-256:"+HexFormat.of().formatHex(digest.digest()));zip.closeEntry();
                            items.add(new WorkspaceFileItemResultDTO(item.path(),"FILE","ARCHIVED",null,null));
                        }
                    }catch(IOException e) {
                        items.add(new WorkspaceFileItemResultDTO(item.path(),"FILE","FAILED",code(e),safe(e)));
                        String digest=WorkspacePathPolicy.digest(json.writeValueAsBytes(new ItemPayload(items)));
                        return terminal(result(c,"FAILED","NO_CHANGE",code(e),safe(e),"FILE",null,null,null,0,null,digest),items);
                    }
                }
            }
            String hash;
            try(var input=Files.newInputStream(temporary)){hash=WorkspaceFileService.copy(input,OutputStream.nullOutputStream(),limits.maxOutputBytes(),false);}
            transport.upload(c,"/content",temporary,hash);
            String digest=WorkspacePathPolicy.digest(json.writeValueAsBytes(new ItemPayload(items)));
            return terminal(result(c,"SUCCEEDED","COMPLETE",null,null,"FILE",null,null,null,Files.size(temporary),hash,digest),items);
        }finally{Files.deleteIfExists(temporary);}
    }
    private WorkspaceFileLimitsDTO effective(WorkspaceFileLimitsDTO frozen) throws IOException {
        if(frozen==null)throw new WorkspaceFileException("INVALID_LIMITS","缺少文件操作限制");
        var value=new WorkspaceFileLimitsDTO(Math.min(frozen.maxFiles(),properties.getWorkspaceArchiveMaxFiles()),
                Math.min(frozen.maxFileBytes(),properties.getMaxAttachmentBytes()),Math.min(frozen.maxTotalBytes(),properties.getWorkspaceArchiveMaxTotalBytes()),
                Math.min(frozen.maxOutputBytes(),properties.getWorkspaceArchiveMaxOutputBytes()),Math.min(frozen.maxRequestBytes(),512*1024),Math.min(frozen.maxDurationSeconds(),300));
        if(value.maxFiles()<1||value.maxFileBytes()<1||value.maxTotalBytes()<1||value.maxOutputBytes()<1||value.maxRequestBytes()<1||value.maxDurationSeconds()<1)
            throw new WorkspaceFileException("INVALID_LIMITS","文件操作限制无效");
        return value;
    }
    private static final class BoundedOutput extends FilterOutputStream {
        private final long limit;private long size;
        BoundedOutput(OutputStream delegate,long limit){super(delegate);this.limit=limit;}
        @Override public void write(int value)throws IOException{check(1);out.write(value);}
        @Override public void write(byte[] data,int off,int len)throws IOException{check(len);out.write(data,off,len);}
        private void check(int n)throws IOException{if(n>limit-size)throw new WorkspaceFileException("LIMIT_EXCEEDED","ZIP 输出超过限制");size+=n;}
    }
    private static void nonRoot(String path) throws IOException {if(path==null||path.isEmpty())throw new WorkspaceFileException("INVALID_PATH","不能对工作区根目录执行此操作");}
    private static WorkspaceFileItemResultDTO item(PlanEntry e,String status,String code,String error){return new WorkspaceFileItemResultDTO(e.path(),e.entryType(),status,code,error);}
    private WorkspaceOperationJournal.Terminal terminal(WorkspaceFileResultDTO r,List<WorkspaceFileItemResultDTO> items){return new WorkspaceOperationJournal.Terminal(r,List.copyOf(items));}
    private WorkspaceFileResultDTO unknown(WorkspaceFileCommandDTO c){return result(c,"UNKNOWN","UNKNOWN","RESULT_UNKNOWN","操作结果尚未确认，请核实状态",null,null,null,null,0,null,null);}
    private WorkspaceFileResultDTO failure(WorkspaceFileCommandDTO c,Exception e){return result(c,"FAILED","NO_CHANGE",code(e),safe(e),null,null,null,null,0,null,null);}
    private static String code(Exception error) {
        if(error instanceof WorkspaceFileException value)return value.code;
        if(error instanceof com.myharness.agent.command.AgentOperationException value)return value.getErrorCode();
        if(error instanceof NoSuchFileException)return "SOURCE_CHANGED";
        if(error instanceof AccessDeniedException)return "ACCESS_DENIED";
        if(error instanceof FileAlreadyExistsException)return "TARGET_EXISTS";
        return "FILESYSTEM_ERROR";
    }
    private static String safe(Exception error) {
        if(error instanceof WorkspaceFileException || error instanceof com.myharness.agent.command.AgentOperationException)return error.getMessage();
        return "文件操作失败，请刷新目录或核实文件访问状态";
    }
    private WorkspaceFileResultDTO result(WorkspaceFileCommandDTO c,String status,String outcome,String code,String error,String type,String revision,
            WorkspaceDeletePlanDTO plan,WorkspaceFileSummaryDTO summary,long size,String sha,String resultDigest) {
        return new WorkspaceFileResultDTO(c.operationId(),"SUCCEEDED".equals(status),error,List.of(),null,0,size,sha,1,status,outcome,code,
                c.path(),c.targetPath(),type,revision,plan,summary,null,resultDigest);
    }
}
