package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fail closed before registration when the installed sandbox cannot enforce project isolation. */
@Component
public class ProjectIsolationCheck {
    private final AgentProperties properties;
    private final ObjectMapper json;
    public ProjectIsolationCheck(AgentProperties properties,ObjectMapper json) { this.properties=properties;this.json=json; }

    @PostConstruct public void initialize() {
        initializeFor(System.getProperty("os.name"));
    }
    void initializeFor(String osName) {
        if(!properties.isStrictProjectIsolation()) return;
        if(osName!=null && osName.startsWith("Windows")) {
            verifyWindows();properties.confirmReadIsolation();return;
        }
        if(!"Linux".equalsIgnoreCase(osName)) return;
        verify();
        properties.confirmReadIsolation();
    }

    private void verifyWindows() {
        Path root=null;
        try {
            if(properties.getWindowsPython()==null) throw new CodexException("Windows 读取隔离需要配置 harness.agent.windows-python，指向独立 Python 运行时的 python.exe");
            if(!Files.isRegularFile(properties.getWindowsPython()))
                throw new CodexException("Windows 独立 Python 运行时不存在或不可访问："+properties.getWindowsPython()
                        +"。请运行 deploy/prepare-windows-runtime.ps1 从完整 CPython 安装准备专用目录，"
                        +"并将 harness.agent.windows-python 配置为该目录中的 python.exe，然后重启 Agent。");
            Process version=new ProcessBuilder(CodexProcessCommand.version(properties.getCodexCommand())).redirectErrorStream(true).start();
            try {
                if(!version.waitFor(10,TimeUnit.SECONDS) || version.exitValue()!=0
                        || !new String(version.getInputStream().readNBytes(1024),java.nio.charset.StandardCharsets.UTF_8).trim().equals("codex-cli 0.153.0"))
                    throw new CodexException("Windows LPAC 当前仅验证 Codex 0.153.0；其他版本需先通过工具入口隔离回归");
            } finally {if(version.isAlive()) {version.descendants().forEach(ProcessHandle::destroyForcibly);version.destroyForcibly();}}
            Path data=properties.getDataDir().toAbsolutePath();Files.createDirectories(data);
            if(data.toRealPath().startsWith(properties.getWindowsPython().toRealPath().getParent()))
                throw new CodexException("独立 Python 目录不得包含 Agent 状态或凭据目录");
            root=Files.createTempDirectory(data,"windows-isolation-check-");
            Path project=Files.createDirectory(root.resolve("project"));
            Path outside=Files.writeString(root.resolve("outside.txt"),"OUTSIDE");
            Files.writeString(project.resolve("inside.txt"),"INSIDE");
            var result=WindowsIsolatedCommand.execute(project,"""
                    from pathlib import Path
                    import subprocess, os
                    assert Path('inside.txt').read_text()=='INSIDE'
                    Path('created.txt').write_text('WRITE_OK')
                    outside=Path(%s)
                    for path in [outside, Path('../outside.txt')]:
                        try: path.read_bytes()
                        except PermissionError: pass
                        else: raise AssertionError('outside read allowed')
                    try: outside.write_text('CHANGED')
                    except PermissionError: pass
                    else: raise AssertionError('outside write allowed')
                    child=subprocess.run([os.environ['ComSpec'],'/d','/c','type',str(outside)],capture_output=True)
                    assert b'OUTSIDE' not in child.stdout and child.stderr
                    assert not os.environ.get('HARNESS_MODEL_API_KEY')
                    try: Path('.codex/probe').write_text('CHANGED')
                    except PermissionError: pass
                    else: raise AssertionError('metadata write allowed')
                    print('HARNESS_ISOLATION_OK')
                    """.formatted(json.writeValueAsString(outside.toAbsolutePath().toString())),30,properties.getWindowsPython());
            if(result.exitCode()!=0 || !result.output().lines().anyMatch("HARNESS_ISOLATION_OK"::equals)
                    || !Files.readString(outside).equals("OUTSIDE") || !Files.readString(project.resolve("created.txt")).equals("WRITE_OK"))
                throw new CodexException("Windows LPAC isolation self-test failed; execution remains disabled: "+result.output());
        } catch(CodexException failure) {throw failure;}
        catch(Exception failure) {throw new CodexException("Cannot verify Windows native read isolation",failure);}
        finally {
            if(root!=null) try {
                WindowsIsolatedCommand.cleanupProfile(root.resolve("project"),properties.getWindowsPython());
                try(var paths=Files.walk(root)) {for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
            } catch(Exception failure) {throw new CodexException("Cannot clean Windows isolation self-test",failure);}
        }
    }

    void verify() {
        Path root=null;Process process=null;
        try {
            Path data=properties.getDataDir().toAbsolutePath().normalize();Files.createDirectories(data);
            root=Files.createTempDirectory(data,"isolation-check-").toRealPath();
            Path workspace=Files.createDirectory(root.resolve("project"));
            Files.writeString(workspace.resolve("inside.txt"),"INSIDE");
            Path outside=Files.writeString(root.resolve("outside.txt"),"OUTSIDE");
            Path script=Files.writeString(workspace.resolve("probe.sh"),"""
                    set -eu
                    test "$(cat ./inside.txt)" = INSIDE
                    printf WRITE_OK > ./created.txt
                    ln -s "$1" ./outside-link
                    if cat ../outside.txt >/dev/null 2>&1; then exit 21; fi
                    if cat "$1" >/dev/null 2>&1; then exit 22; fi
                    if cat ./outside-link >/dev/null 2>&1; then exit 23; fi
                    if (printf CHANGED > "$1") 2>/dev/null; then exit 24; fi
                    printf 'HARNESS_ISOLATION_OK\\n'
                    """);
            var command=new ArrayList<String>(List.of(properties.getCodexCommand(),"sandbox","-P","harness-isolation-check","-C",workspace.toString()));
            command.addAll(ProjectPermissionProfile.commandOverrides(json,"harness-isolation-check"));
            command.addAll(List.of("--","/bin/sh",script.toString(),outside.toString()));
            Path output=root.resolve("probe-output.txt");
            process=launch(command,workspace,output);
            if(!process.waitFor(properties.getCodexRequestTimeoutSeconds(),TimeUnit.SECONDS))
                throw new CodexException("Linux project isolation self-test timed out; execution remains disabled");
            if(process.exitValue()!=0 || !Files.readString(output).lines().anyMatch("HARNESS_ISOLATION_OK"::equals)
                    || !Files.readString(outside).equals("OUTSIDE") || !Files.readString(workspace.resolve("created.txt")).equals("WRITE_OK"))
                throw new CodexException("Linux project isolation self-test failed (exit "+process.exitValue()+"); check Codex and kernel/container sandbox support. Execution remains disabled");
        } catch(CodexException failure) { throw failure; }
        catch(Exception failure) {
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CodexException("Cannot verify Linux project read isolation; execution remains disabled",failure);
        } finally {
            if(process!=null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
                try {process.waitFor(5,TimeUnit.SECONDS);} catch(InterruptedException failure) {Thread.currentThread().interrupt();}
            }
            if(root!=null) try(var paths=Files.walk(root)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch(java.io.IOException failure) {throw new CodexException("Cannot clean isolation self-test files",failure);}
        }
    }

    Process launch(List<String> command,Path workspace,Path output) throws java.io.IOException {
        return new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }
}
