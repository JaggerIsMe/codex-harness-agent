package com.myharness.agent.codex;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Executes generated code with a Windows LPAC token and explicit network capabilities. */
final class WindowsIsolatedCommand {
    private static final int MODIFY=0x1301bf;
    private static final int OUTPUT_LIMIT=128*1024;
    private WindowsIsolatedCommand() { }
    record Result(int exitCode,String output) { }

    static Result execute(Path directory,String script,int timeoutSeconds,Path python,Path temporaryDirectory) {
        return execute(directory,script,timeoutSeconds,python,temporaryDirectory,java.util.List.of(),java.util.Map.of(),120);
    }
    static Result executeTool(Path directory,String script,int timeoutSeconds,Path python,Path temporaryDirectory,java.util.List<Path> runtimes,java.util.Map<String,String> environment) {
        return execute(directory,script,timeoutSeconds,python,temporaryDirectory,runtimes,environment,1800);
    }
    private static Result execute(Path directory,String script,int timeoutSeconds,Path python,Path temporaryDirectory,java.util.List<Path> runtimes,java.util.Map<String,String> extraEnvironment,int maximumTimeout) {
        return execute(directory,script,timeoutSeconds,python,temporaryDirectory,runtimes,extraEnvironment,maximumTimeout,null);
    }
    static Result executeScoped(Path directory,String script,int timeoutSeconds,Path python,SkillExecutionScope scope,java.util.List<Path> runtimes,java.util.Map<String,String> environment,int maximumTimeout) {
        return execute(directory,script,timeoutSeconds,python,scope.temporaryDirectory(),runtimes,environment,maximumTimeout,scope);
    }
    static Result executeScoped(Path directory,String script,int timeoutSeconds,Path python,SkillExecutionScope scope,java.util.List<Path> runtimes,java.util.Map<String,String> environment,int maximumTimeout,boolean publicNetwork) {
        return execute(directory,script,timeoutSeconds,python,scope.temporaryDirectory(),runtimes,environment,maximumTimeout,scope,publicNetwork);
    }
    private static Result execute(Path directory,String script,int timeoutSeconds,Path python,Path temporaryDirectory,java.util.List<Path> runtimes,java.util.Map<String,String> extraEnvironment,int maximumTimeout,SkillExecutionScope scope) {
        return execute(directory,script,timeoutSeconds,python,temporaryDirectory,runtimes,extraEnvironment,maximumTimeout,scope,false);
    }
    private static Result execute(Path directory,String script,int timeoutSeconds,Path python,Path temporaryDirectory,java.util.List<Path> runtimes,java.util.Map<String,String> extraEnvironment,int maximumTimeout,SkillExecutionScope scope,boolean publicNetwork) {
        if(!Platform.isWindows() || !Platform.is64Bit()) throw new CodexException("Windows isolation requires 64-bit Windows");
        if(script==null || script.isBlank() || script.length()>12000) throw new CodexException("Command must contain 1–12000 characters");
        if(timeoutSeconds<1 || timeoutSeconds>maximumTimeout) throw new CodexException("Command timeout must be 1–"+maximumTimeout+" seconds");
        try {
            Path workspace=directory.toRealPath();
            String profile=scope==null ? "harness.workspace."+UUID.nameUUIDFromBytes(workspace.toString().getBytes(StandardCharsets.UTF_8))
                    : "harness.command."+UUID.randomUUID();
            var granted=new java.util.ArrayList<Path>();
            WindowsCommandLease lease=scope==null?null:new WindowsCommandLease(scope.temporaryDirectory(),profile);
            var sid=new PointerByReference();
            int created=Userenv.API.CreateAppContainerProfile(profile,profile,"Harness project execution",null,0,sid);
            if(created==0x800700b7) created=Userenv.API.DeriveAppContainerSidFromAppContainerName(profile,sid);
            if(created!=0) throw new CodexException("Cannot create Windows project isolation profile: "+Integer.toHexString(created));
            try(var workspacePin=com.myharness.agent.workspace.WindowsWorkspaceHandles.pin(workspace,workspace)) {
                var packageSid=new WinNT.PSID(sid.getValue());
                if(lease!=null)lease.beforeGrant(workspace);granted.add(workspace);grant(workspace,packageSid,false,MODIFY);
                if(scope==null) for(String name:new String[]{".git",".codex",".agent",".agents"}) {
                    Path existing=workspace.resolve(name);
                    if(Files.exists(existing,java.nio.file.LinkOption.NOFOLLOW_LINKS))releaseLegacyReadOnly(workspace,existing,packageSid);
                }
                Path temp=temporaryDirectory.toRealPath();
                if(temp.startsWith(workspace) || workspace.startsWith(temp) || !temp.equals(temporaryDirectory.toAbsolutePath().normalize()))
                    throw new CodexException("Execution temporary directory must be separate from Workspace");
                try(var tempPin=com.myharness.agent.workspace.WindowsWorkspaceHandles.pin(temp,temp)) {if(lease!=null)lease.beforeGrant(temp);granted.add(temp);grant(temp,packageSid,false,MODIFY);}
                String windows=System.getenv("SystemRoot");
                if(python==null) throw new CodexException("Windows isolation requires a dedicated Python runtime");
                Path runtime=python.toRealPath();
                if(!runtime.getFileName().toString().equalsIgnoreCase("python.exe") || runtime.getParent().getParent()==null)
                    throw new CodexException("Invalid Windows Python runtime");
                if(workspace.startsWith(runtime.getParent()) || runtime.getParent().startsWith(workspace))
                    throw new CodexException("Python runtime must be separate from project files");
                if(lease!=null)lease.beforeGrant(runtime.getParent());granted.add(runtime.getParent());grant(runtime.getParent(),packageSid,false,0x1200a9);
                for(Path toolHome:runtimes) {
                    Path home=toolHome.toRealPath();
                    if(home.getParent()==null||workspace.startsWith(home)||home.startsWith(workspace))throw new CodexException("Tool runtime must be separate from Workspace");
                    if(lease!=null)lease.beforeGrant(home);granted.add(home);grant(home,packageSid,false,0x1200a9);
                }
                if(scope!=null) for(Path skill:scope.readableSkills()) {
                    SkillExecutionScope.validateTree(skill);
                    rejectHardLinks(skill);
                    try(var pin=com.myharness.agent.workspace.WindowsWorkspaceHandles.pin(skill,skill)) {
                        if(lease!=null)lease.beforeGrant(skill);granted.add(skill);grant(skill,packageSid,false,0x120089);
                    }
                }
                String shell=runtime.toString();
                // Windows rewrites AppContainer profile environment values during process creation.
                // Restore our per-project directory before evaluating any generated code.
                String encodedTemp=Base64.getEncoder().encodeToString(temp.toString().getBytes(StandardCharsets.UTF_8));
                String bootstrap="import os,base64,sys\nsys.dont_write_bytecode=True\n_harness_temp=base64.b64decode('"+encodedTemp+"').decode('utf-8')\n"
                        +"os.environ.update({k:_harness_temp for k in ('TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA')})\n"
                        +"sys.path.append(os.path.join(os.path.dirname(sys.executable),'Lib','site-packages'))\n";
                String payload=Base64.getEncoder().encodeToString((bootstrap+"exec(compile(base64.b64decode('"
                        +Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8))+"'),'<harness>','exec'))").getBytes(StandardCharsets.UTF_8));
                String command='"'+shell+'"'+" -I -S -X utf8 -c \"import base64;exec(compile(base64.b64decode('"+payload+"'),'<harness>','exec'))\"";
                if(command.length()>32000) throw new CodexException("Encoded command exceeds the Windows command-line limit");
                var environment=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);
                environment.put("SystemRoot",windows);environment.put("WINDIR",windows);
                environment.put("PATH",windows+"\\System32;"+windows+"\\System32\\WindowsPowerShell\\v1.0");
                environment.put("TEMP",temp.toString());environment.put("TMP",temp.toString());environment.put("USERPROFILE",temp.toString());
                environment.put("APPDATA",temp.toString());environment.put("LOCALAPPDATA",temp.toString());
                environment.put("ComSpec",windows+"\\System32\\cmd.exe");
                environment.put("COMPUTERNAME",System.getenv().getOrDefault("COMPUTERNAME","HARNESS"));
                environment.put("OS","Windows_NT");environment.put("PROCESSOR_ARCHITECTURE","AMD64");
                environment.putAll(extraEnvironment);
                environment.put("NPM_CONFIG_CACHE",temp.resolve("npm-cache").toString());
                environment.put("MAVEN_OPTS","-Dmaven.repo.local=\""+temp.resolve("maven-repository")+"\"");
                environment.put("JAVA_TOOL_OPTIONS","-Duser.home=\""+temp+"\" -Djava.io.tmpdir=\""+temp+"\"");
                StringBuilder block=new StringBuilder();environment.forEach((key,value)->block.append(key).append('=').append(value).append('\0'));block.append('\0');
                try(var env=new Memory((long)block.length()*2)) {
                    env.setWideString(0,block.substring(0,block.length()-1));
                    return normalizePythonStartup(launch(shell,command,workspace,sid.getValue(),env,timeoutSeconds,publicNetwork),runtime);
                }
            } finally {
                try {
                    if(scope!=null) {
                        revokeProfile(profile,granted);
                        lease.complete();
                    }
                } finally {Security.API.FreeSid(sid.getValue());}
            }
        } catch(CodexException failure) {throw failure;}
        catch(Exception failure) {if(failure instanceof InterruptedException) Thread.currentThread().interrupt();throw new CodexException("Windows isolated command failed",failure);}
    }

    static void revokeProfile(String profile,java.util.List<Path> paths) {
        var sid=new PointerByReference();
        if(Userenv.API.DeriveAppContainerSidFromAppContainerName(profile,sid)!=0)
            throw new CodexException("Cannot derive command isolation SID for cleanup");
        try {
            RuntimeException cleanupFailure=null;
            for(Path path:paths.reversed()) try {grant(path,new WinNT.PSID(sid.getValue()),false,0);}
            catch(RuntimeException failure) {if(cleanupFailure==null)cleanupFailure=failure;else cleanupFailure.addSuppressed(failure);}
            if(cleanupFailure!=null)throw cleanupFailure;
            int deleted=Userenv.API.DeleteAppContainerProfile(profile);
            if(deleted!=0 && deleted!=0x80070002)throw new CodexException("Cannot delete command isolation profile: "+Integer.toHexString(deleted));
        } finally {Security.API.FreeSid(sid.getValue());}
    }

    static Result normalizePythonStartup(Result result,Path runtime) {
        if(result.exitCode()!=0)return result;
        // CPython can execute successfully in LPAC while GetFinalPathNameByHandle is denied.
        // Only remove this exact runtime's leading startup diagnostic. Preserve failed commands,
        // all later output, and every other warning/error; never widen the token or filesystem ACL.
        String diagnostic="Failed to find real location of "+runtime;
        String output=result.output();
        while(output.startsWith(diagnostic+"\n")||output.startsWith(diagnostic+"\r\n"))
            output=output.substring(output.indexOf('\n')+1);
        return new Result(result.exitCode(),output);
    }

    private static Result launch(String executable,String command,Path cwd,Pointer sid,Pointer env,int timeout,boolean publicNetwork) throws Exception {
        var capabilitySet=new CapabilitySet(publicNetwork);
        WinNT.HANDLE job=null,read=null,write=null,input=null;
        CompletableFuture<String> output=null;
        var process=new WinBase.PROCESS_INFORMATION();boolean started=false;
        try {
            job=Kernel.API.CreateJobObject(null,null);check(job!=null,"create process job");
            try(var limits=new Memory(144)) {
                limits.clear();limits.setInt(16,0x2000|0x8);limits.setInt(40,32);
                check(Kernel.API.SetInformationJobObject(job,9,limits,144),"configure process job");
            }
            var security=new WinBase.SECURITY_ATTRIBUTES();security.bInheritHandle=true;security.dwLength=new WinDef.DWORD(security.size());
            var readRef=new WinNT.HANDLEByReference();var writeRef=new WinNT.HANDLEByReference();
            check(Kernel32.INSTANCE.CreatePipe(readRef,writeRef,security,0),"create output pipe");read=readRef.getValue();write=writeRef.getValue();
            check(Kernel32.INSTANCE.SetHandleInformation(read,1,0),"protect output reader");
            input=Kernel32.INSTANCE.CreateFile("NUL",0x80000000,3,security,3,0,null);
            check(input!=null && !WinBase.INVALID_HANDLE_VALUE.equals(input),"open input");
            var permissions=new Capabilities();permissions.sid=sid;permissions.capabilities=capabilitySet.entries[0].getPointer();permissions.count=capabilitySet.entries.length;permissions.write();
            var bytes=new LongByReference();Kernel.API.InitializeProcThreadAttributeList(null,3,0,bytes);
            try(var attributes=new Memory(bytes.getValue());var handles=new Memory(2L*Native.POINTER_SIZE)) {
                check(Kernel.API.InitializeProcThreadAttributeList(attributes,3,0,bytes),"initialize process attributes");
                try {
                    handles.setPointer(0,write.getPointer());handles.setPointer(Native.POINTER_SIZE,input.getPointer());
                    var optOut=new IntByReference(1);
                    attribute(attributes,0x20009,permissions.getPointer(),permissions.size());
                    attribute(attributes,0x2000f,optOut.getPointer(),4);
                    attribute(attributes,0x20002,handles,handles.size());
                    var startup=new StartupEx();startup.attributes=attributes;startup.startup.cb=new WinDef.DWORD(startup.size());
                    startup.startup.dwFlags=0x100;startup.startup.hStdInput=input;startup.startup.hStdOutput=write;startup.startup.hStdError=write;startup.write();
                    check(Kernel.API.CreateProcess(executable,Native.toCharArray(command),null,null,true,0x00080000|0x08000000|0x400|4,env,cwd.toString(),startup,process),"create isolated process");started=true;
                    check(Kernel.API.AssignProcessToJobObject(job,process.hProcess),"assign process job");
                    verifyToken(process.hProcess);
                    check(Kernel.API.ResumeThread(process.hThread)!=-1,"resume isolated process");
                } finally {Kernel.API.DeleteProcThreadAttributeList(attributes);}
            }
            close(write);write=null;close(input);input=null;
            WinNT.HANDLE pipe=read;WinNT.HANDLE ownedJob=job;
            output=CompletableFuture.supplyAsync(()->readOutput(pipe,ownedJob));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);
            while(Kernel32.INSTANCE.WaitForSingleObject(process.hProcess,100)==258) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if(System.nanoTime()>=deadline) throw new CodexException("Isolated command timed out");
            }
            var exit=new IntByReference();check(Kernel32.INSTANCE.GetExitCodeProcess(process.hProcess,exit),"read command exit");
            check(Kernel.API.TerminateJobObject(job,125),"stop command descendants");
            return new Result(exit.getValue(),output.get(5,TimeUnit.SECONDS));
        } finally {
            if(job!=null) Kernel.API.TerminateJobObject(job,125);
            if(started) {Kernel32.INSTANCE.TerminateProcess(process.hProcess,125);Kernel32.INSTANCE.WaitForSingleObject(process.hProcess,5000);close(process.hThread);close(process.hProcess);}
            if(output!=null) {
                WinNT.HANDLE ownedRead=read,ownedJob=job;
                output.whenComplete((value,failure)-> {close(ownedRead);close(ownedJob);});
                read=null;job=null;
            }
            close(job);close(write);close(input);close(read);
            capabilitySet.close();
        }
    }

    private static final class CapabilitySet implements AutoCloseable {
        private final java.util.List<PointerByReference> arrays=new java.util.ArrayList<>();
        private final java.util.List<IntByReference> counts=new java.util.ArrayList<>();
        private final SidAttributes[] entries;

        CapabilitySet(boolean publicNetwork) {
            String[] names=publicNetwork ? new String[]{"registryRead","internetClient"} : new String[]{"registryRead"};
            entries=(SidAttributes[])new SidAttributes().toArray(names.length);
            try {
                for(int i=0;i<names.length;i++) {
                    var groups=new PointerByReference();var groupCount=new IntByReference();
                    var caps=new PointerByReference();var capCount=new IntByReference();
                    arrays.add(groups);counts.add(groupCount);arrays.add(caps);counts.add(capCount);
                    check(KernelBase.API.DeriveCapabilitySidsFromName(names[i],groups,groupCount,caps,capCount),"derive "+names[i]+" capability");
                    check(capCount.getValue()>0,"resolve "+names[i]+" capability");
                    entries[i].sid=caps.getValue().getPointer(0);entries[i].attributes=4;entries[i].write();
                }
            } catch(RuntimeException failure) {close();throw failure;}
        }
        @Override public void close() {
            for(int i=0;i<arrays.size();i++)freeArray(arrays.get(i),counts.get(i));
            arrays.clear();counts.clear();
        }
    }

    private static String readOutput(WinNT.HANDLE pipe,WinNT.HANDLE job) {
        var output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];var count=new IntByReference();
        while(Kernel32.INSTANCE.ReadFile(pipe,buffer,buffer.length,count,null) && count.getValue()>0) {
            int accepted=Math.min(count.getValue(),OUTPUT_LIMIT-output.size());output.write(buffer,0,accepted);
            if(accepted<count.getValue() || output.size()>=OUTPUT_LIMIT) {Kernel.API.TerminateJobObject(job,126);output.writeBytes("\n[output limit reached]".getBytes(StandardCharsets.UTF_8));break;}
        }
        return output.toString(StandardCharsets.UTF_8);
    }
    private static void verifyToken(WinNT.HANDLE process) {
        var token=new WinNT.HANDLEByReference();check(Advapi32.INSTANCE.OpenProcessToken(process,8,token),"open isolated token");
        try(var value=new Memory(4)) {
            check(Security.API.GetTokenInformation(token.getValue(),29,value,4,new IntByReference()),"inspect isolated token");
            if(value.getInt(0)!=1) throw new CodexException("Process does not have an AppContainer token");
        } finally {close(token.getValue());}
    }
    private static void releaseLegacyReadOnly(Path workspace,Path path,WinNT.PSID sid) throws java.io.IOException {
        if(!path.toRealPath().equals(path) || !path.startsWith(workspace))throw new CodexException("Legacy metadata path cannot be a link");
        try(var pin=com.myharness.agent.workspace.WindowsWorkspaceHandles.pin(workspace,path)) {
            var acl=new PointerByReference();var descriptor=new PointerByReference();
            int result=Advapi32.INSTANCE.GetNamedSecurityInfo(com.myharness.agent.workspace.WindowsWorkspaceHandles.nativePath(path),1,4,null,null,acl,null,descriptor);
            if(result!=0 || acl.getValue()==null)throw new CodexException("Cannot inspect legacy metadata ACL");
            boolean managed=false;
            try {
                var old=new WinNT.ACL(acl.getValue());old.read();
                for(int i=0;i<Short.toUnsignedInt(old.AceCount);i++) {
                    var entry=new PointerByReference();check(Advapi32.INSTANCE.GetAce(old,i,entry),"inspect legacy ACL");
                    Pointer ace=entry.getValue();
                    if(ace.getByte(0)==0 && (ace.getByte(1)&16)==0 && ace.getInt(4)==0x1200a9
                            && Advapi32.INSTANCE.EqualSid(new WinNT.PSID(ace.share(8)),sid))managed=true;
                }
            } finally {Kernel32.INSTANCE.LocalFree(descriptor.getValue());}
            // Do not reset user ACLs. Replace only our old read-only package grant.
            if(managed)grant(path,sid,false,MODIFY);
        }
    }

    private static synchronized void grant(Path path,WinNT.PSID sid,boolean denyWrite,int allowedMask) {
        var oldAcl=new PointerByReference();var descriptor=new PointerByReference();
        int result=Advapi32.INSTANCE.GetNamedSecurityInfo(com.myharness.agent.workspace.WindowsWorkspaceHandles.nativePath(path),1,4,null,null,oldAcl,null,descriptor);
        if(result!=0 || oldAcl.getValue()==null) throw new CodexException("Cannot inspect Workspace ACL: "+result);
        try {
            var old=new WinNT.ACL(oldAcl.getValue());old.read();
            int desired=denyWrite?0x1200a9:allowedMask;
            for(int i=0;desired!=0 && i<Short.toUnsignedInt(old.AceCount);i++) {
                var entry=new PointerByReference();check(Advapi32.INSTANCE.GetAce(old,i,entry),"inspect existing isolation grant");
                Pointer ace=entry.getValue();
                if(ace.getByte(0)==0 && (ace.getByte(1)&16)==0 && ace.getInt(4)==desired
                        && Advapi32.INSTANCE.EqualSid(new WinNT.PSID(ace.share(8)),sid)) return;
            }
            // Never recursively refresh a grant over a tree that generated code may have
            // populated with hard links. Existing matching grants above are left untouched.
            if(!denyWrite && allowedMask==MODIFY) rejectHardLinks(path);
            int size=Short.toUnsignedInt(old.AclSize)+Advapi32.INSTANCE.GetLengthSid(sid)+8;
            var acl=new WinNT.ACL(size);check(Advapi32.INSTANCE.InitializeAcl(acl,size,2),"initialize Workspace ACL");
            var retained=new java.util.ArrayList<Pointer>();
            for(int i=0;i<Short.toUnsignedInt(old.AceCount);i++) {
                var ace=new PointerByReference();check(Advapi32.INSTANCE.GetAce(old,i,ace),"read Workspace ACL entry");
                Pointer item=ace.getValue();int kind=item.getByte(0)&255;
                if((kind==0 || kind==1) && (denyWrite || (item.getByte(1)&16)==0) && Advapi32.INSTANCE.EqualSid(new WinNT.PSID(item.share(8)),sid)) continue;
                retained.add(item);
            }
            for(Pointer item:retained) if(item.getByte(0)==1 && (item.getByte(1)&16)==0)
                check(Advapi32.INSTANCE.AddAce(acl,2,0xffffffff,item,Short.toUnsignedInt(item.getShort(2))),"preserve explicit deny");
            // Remove the inherited package write grant. A deny ACE for a package SID alone
            // did not suppress that grant in the Windows 10 LPAC execution probe.
            if(denyWrite) check(Advapi32.INSTANCE.AddAccessAllowedAceEx(acl,2,3,0x1200a9,sid),"protect Workspace metadata");
            else if(allowedMask!=0) check(Advapi32.INSTANCE.AddAccessAllowedAceEx(acl,2,3,allowedMask,sid),"grant Workspace access");
            for(Pointer item:retained) {
                if(item.getByte(0)==1 && (item.getByte(1)&16)==0) continue;
                check(Advapi32.INSTANCE.AddAce(acl,2,0xffffffff,item,Short.toUnsignedInt(item.getShort(2))),"copy Workspace ACL entry");
            }
            result=Advapi32.INSTANCE.SetNamedSecurityInfo(com.myharness.agent.workspace.WindowsWorkspaceHandles.nativePath(path),1,4|(denyWrite?0x80000000:0),null,null,acl.getPointer(),null);
            if(result!=0) throw new CodexException("Cannot apply isolation ACL to "+path+": "+result);
        } finally {Kernel32.INSTANCE.LocalFree(descriptor.getValue());}
    }
    private static void rejectHardLinks(Path root) {
        try {
            Files.walkFileTree(root,new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult preVisitDirectory(Path directory,java.nio.file.attribute.BasicFileAttributes attrs) {
                    int attributes=Kernel32.INSTANCE.GetFileAttributes(com.myharness.agent.workspace.WindowsWorkspaceHandles.nativePath(directory));
                    if(attributes==-1) throw new CodexException("Cannot inspect directory before granting isolation access");
                    return (attributes&0x400)!=0?java.nio.file.FileVisitResult.SKIP_SUBTREE:java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) {
                    if(!attrs.isRegularFile()) return java.nio.file.FileVisitResult.CONTINUE;
                    WinNT.HANDLE handle=Kernel32.INSTANCE.CreateFile(com.myharness.agent.workspace.WindowsWorkspaceHandles.nativePath(file),0x80,3,null,3,0x00200000,null);
                    check(handle!=null && !WinBase.INVALID_HANDLE_VALUE.equals(handle),"inspect Workspace file links");
                    try(var info=new Memory(24)) {
                        check(Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,1,info,new WinDef.DWORD(24)),"inspect Workspace file links");
                        if(info.getInt(16)>1) throw new CodexException("Workspace contains hard-linked files; remove shared links before enabling isolation");
                    } finally {close(handle);}
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch(java.io.IOException failure) {throw new CodexException("Cannot verify Workspace before granting isolation access",failure);}
    }
    private static void attribute(Pointer list,long kind,Pointer value,long size) {check(Kernel.API.UpdateProcThreadAttribute(list,0,new BaseTSD.ULONG_PTR(kind),value,new BaseTSD.SIZE_T(size),null,null),"set isolation attribute");}
    static void cleanupProfile(Path workspace,Path python) throws java.io.IOException {
        String name="harness.workspace."+UUID.nameUUIDFromBytes(workspace.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
        var sid=new PointerByReference();
        if(Userenv.API.DeriveAppContainerSidFromAppContainerName(name,sid)!=0) throw new CodexException("Cannot derive isolation profile for cleanup");
        try {
            try {
                if(python!=null && Files.exists(python)) grant(python.toRealPath().getParent(),new WinNT.PSID(sid.getValue()),false,0);
            } finally {
                int result=Userenv.API.DeleteAppContainerProfile(name);
                if(result!=0 && result!=0x80070002) throw new CodexException("Cannot delete isolation self-test profile");
            }
        } finally {Security.API.FreeSid(sid.getValue());}
    }
    private static void freeArray(PointerByReference array,IntByReference count) {if(array.getValue()!=null) {for(int i=0;i<count.getValue();i++) Kernel32.INSTANCE.LocalFree(array.getValue().getPointer((long)i*Native.POINTER_SIZE));Kernel32.INSTANCE.LocalFree(array.getValue());}}
    private static void close(WinNT.HANDLE handle) {if(handle!=null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) Kernel32.INSTANCE.CloseHandle(handle);}
    private static void check(boolean result,String action) {if(!result) throw new CodexException("Cannot "+action+" (Windows error "+Native.getLastError()+")");}

    interface Userenv extends StdCallLibrary {
        Userenv API=Native.load("userenv",Userenv.class,W32APIOptions.UNICODE_OPTIONS);
        int CreateAppContainerProfile(String name,String display,String description,Pointer capabilities,int count,PointerByReference sid);
        int DeriveAppContainerSidFromAppContainerName(String name,PointerByReference sid);
        int DeleteAppContainerProfile(String name);
    }
    interface Security extends StdCallLibrary {
        Security API=Native.load("advapi32",Security.class,W32APIOptions.UNICODE_OPTIONS);
        Pointer FreeSid(Pointer sid);
        boolean GetTokenInformation(WinNT.HANDLE token,int kind,Pointer value,int size,IntByReference returned);
    }
    interface KernelBase extends StdCallLibrary {
        KernelBase API=Native.load("kernelbase",KernelBase.class,W32APIOptions.UNICODE_OPTIONS);
        boolean DeriveCapabilitySidsFromName(String name,PointerByReference groups,IntByReference groupCount,PointerByReference sids,IntByReference count);
    }
    interface Kernel extends StdCallLibrary {
        Kernel API=Native.load("kernel32",Kernel.class,W32APIOptions.UNICODE_OPTIONS);
        boolean InitializeProcThreadAttributeList(Pointer list,int count,int flags,LongByReference bytes);
        boolean UpdateProcThreadAttribute(Pointer list,int flags,BaseTSD.ULONG_PTR attribute,Pointer value,BaseTSD.SIZE_T size,Pointer previous,Pointer returned);
        void DeleteProcThreadAttributeList(Pointer list);
        boolean CreateProcess(String application,char[] command,Pointer processSecurity,Pointer threadSecurity,boolean inherit,int flags,Pointer env,String cwd,StartupEx startup,WinBase.PROCESS_INFORMATION info);
        WinNT.HANDLE CreateJobObject(Pointer security,String name);
        boolean SetInformationJobObject(WinNT.HANDLE job,int kind,Pointer value,int size);
        boolean AssignProcessToJobObject(WinNT.HANDLE job,WinNT.HANDLE process);
        boolean TerminateJobObject(WinNT.HANDLE job,int exitCode);
        int ResumeThread(WinNT.HANDLE thread);
    }
    @Structure.FieldOrder({"startup","attributes"})
    public static class StartupEx extends Structure {public WinBase.STARTUPINFO startup=new WinBase.STARTUPINFO();public Pointer attributes;}
    @Structure.FieldOrder({"sid","capabilities","count","reserved"})
    public static class Capabilities extends Structure {public Pointer sid;public Pointer capabilities;public int count;public int reserved;}
    @Structure.FieldOrder({"sid","attributes"})
    public static class SidAttributes extends Structure {public Pointer sid;public int attributes;}
}
