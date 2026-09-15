package com.myharness.agent.codex;

import com.myharness.agent.workspace.AgentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="windows.isolation.smoke",matches="true")
class SkillReadOnlyWindowsTest {
    @TempDir Path root;
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();

    @Test void validationSkillCalculatesInsideLpacWithoutResolvingHostParents() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("project")),data=Files.createDirectory(root.resolve("data"));
        AgentStorage.protectDataDirectory(data);
        Path fixtures=Path.of("../../examples/skill-validation/skills").toAbsolutePath().normalize();
        Path source=fixtures.resolve("harness-validation-calculate"),pkg=AgentStorage.directory(data,"private/calculate");
        try(var files=Files.walk(source)) {
            for(Path file:files.toList()) {
                Path target=pkg.resolve(source.relativize(file));
                if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target);
            }
        }
        Path run=Files.createDirectories(workspace.resolve("skill-validation/lpac-probe"));
        byte[] orders=Files.readAllBytes(fixtures.resolve("harness-validation-prepare/assets/orders.csv"));
        Files.write(run.resolve("orders.csv"),orders);
        String requestId=java.util.UUID.randomUUID().toString();
        var prepared=json.createObjectNode().put("schema_version",1).put("state","PREPARED")
                .put("fixture_id","orders-demo-v1").put("run_id","lpac-probe").put("request_id",requestId)
                .put("prepare_marker","PREPARE_BODY_V1")
                .put("input_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(orders)));
        Files.writeString(run.resolve("prepared.json"),json.writeValueAsString(prepared));
        var scope=new SkillExecutionScope("validation",AgentStorage.directory(data,"skill-execution/validation"),List.of(pkg));
        var properties=new com.myharness.agent.config.AgentProperties();properties.setDataDir(data);
        properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
        var arguments=json.createObjectNode().put("program","python");
        arguments.putArray("args").add(pkg.resolve("scripts/run_report.py").toString()).add("--run-dir").add(run.toString());
        var result=WindowsCommandTool.execute(workspace,properties,arguments,json,scope);
        assertEquals(0,result.exitCode(),result.output());
        assertTrue(result.output().contains("CALCULATE_SCRIPT_V1"),result.output());
        var report=json.readTree(Files.readString(run.resolve("calculation/report.json")));
        assertEquals(14850,report.path("total_amount_cents").asInt());
        assertEquals(requestId,report.path("request_id").asText());
        assertFalse(Files.exists(pkg.resolve("scripts/__pycache__")));
    }

    @Test void simultaneousCommandsInOneWorkspaceDoNotUnionSkillPermissions() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("project")),data=Files.createDirectory(root.resolve("data"));
        AgentStorage.protectDataDirectory(data);
        Path a=AgentStorage.directory(data,"private/a"),b=AgentStorage.directory(data,"private/b");
        Files.writeString(a.resolve("SKILL.md"),"A");Files.writeString(b.resolve("SKILL.md"),"B");
        Path python=Path.of(System.getProperty("windows.isolation.python"));
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<WindowsIsolatedCommand.Result>>();
            for(int i=0;i<2;i++) {
                Path own=i==0?a:b,other=i==0?b:a;String label=i==0?"a":"b",peer=i==0?"b":"a";
                var scope=new SkillExecutionScope(label,AgentStorage.directory(data,"skill-execution/"+label),List.of(own));
                String script="""
                        from pathlib import Path
                        import time
                        assert Path(%s).read_text()
                        Path('%s.ready').write_text('ready')
                        deadline=time.monotonic()+10
                        while not Path('%s.ready').exists():
                            if time.monotonic()>deadline: raise AssertionError('peer not ready')
                            time.sleep(.02)
                        try: Path(%s).read_text()
                        except PermissionError: print('PEER_DENIED')
                        else: raise AssertionError('peer Skill readable')
                        """.formatted(json.writeValueAsString(own.resolve("SKILL.md").toString()),label,peer,json.writeValueAsString(other.resolve("SKILL.md").toString()));
                futures.add(workers.submit(()->WindowsIsolatedCommand.executeScoped(workspace,script,15,python,scope,List.of(),Map.of(),120)));
            }
            for(var future:futures) {var result=future.get(25,java.util.concurrent.TimeUnit.SECONDS);assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("PEER_DENIED"));}
        }
    }

    @Test void interruptedLeaseCanBeRecoveredWithoutTouchingAnotherFile() throws Exception {
        Path data=Files.createDirectory(root.resolve("data"));AgentStorage.protectDataDirectory(data);
        Path pkg=AgentStorage.directory(data,"private/pkg"),temp=AgentStorage.directory(data,"skill-execution/recovery");
        String profile="harness.command."+java.util.UUID.randomUUID();
        var sid=new com.sun.jna.ptr.PointerByReference();
        assertEquals(0,WindowsIsolatedCommand.Userenv.API.CreateAppContainerProfile(profile,profile,"test",null,0,sid));
        var lease=new WindowsCommandLease(temp,profile);lease.beforeGrant(pkg);
        try {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(WindowsIsolatedCommand.class,"grant",pkg,new com.sun.jna.platform.win32.WinNT.PSID(sid.getValue()),false,0x120089);
        } finally {WindowsIsolatedCommand.Security.API.FreeSid(sid.getValue());}
        Path record=data.resolve("skill-execution/.command-leases/"+profile+".json");
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(record));value.put("pid",2147483647L);
        Files.writeString(record,value.toString());
        WindowsCommandLease.recover(data);
        assertFalse(Files.exists(record));assertTrue(Files.isDirectory(pkg));
        assertTrue(Files.getFileAttributeView(pkg,java.nio.file.attribute.AclFileAttributeView.class).getAcl().stream()
                .noneMatch(ace->ace.principal().getName().startsWith("S-1-15-2-")));
    }

    @Test void structuredScriptLoadsSiblingModulesAndReportsMissingDependenciesWithoutInstallation() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("project")),data=Files.createDirectory(root.resolve("data"));
        AgentStorage.protectDataDirectory(data);
        Path pkg=AgentStorage.directory(data,"private/pkg");
        Files.writeString(pkg.resolve("helper.py"),"VALUE='MODULE_OK'\n");
        Path script=Files.writeString(pkg.resolve("main.py"),"import helper\nprint(helper.VALUE)\nimport harness_missing_fixture_dependency\n");
        var scope=new SkillExecutionScope("imports",AgentStorage.directory(data,"skill-execution/imports"),List.of(pkg));
        var properties=new com.myharness.agent.config.AgentProperties();properties.setDataDir(data);
        properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
        var arguments=json.createObjectNode().put("program","python");arguments.putArray("args").add(script.toString());
        var result=WindowsCommandTool.execute(workspace,properties,arguments,json,scope);
        assertNotEquals(0,result.exitCode());assertTrue(result.output().contains("MODULE_OK"),result.output());
        assertTrue(result.output().contains("ModuleNotFoundError"),result.output());
        assertFalse(Files.exists(pkg.resolve("__pycache__")));
        try(var files=Files.list(workspace)) {assertEquals(0,files.count());}
    }

    @Test void nodeReadsSkillResourcesAndUsesBundledDependencies() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("windows.skill.node")!=null);
        Path workspace=Files.createDirectory(root.resolve("project")),data=Files.createDirectory(root.resolve("data"));
        AgentStorage.protectDataDirectory(data);
        Path pkg=AgentStorage.directory(data,"private/node-skill");
        Files.writeString(AgentStorage.directory(pkg,"node_modules/fixture").resolve("index.js"),"module.exports='NODE_SKILL_OK';");
        Path script=Files.writeString(pkg.resolve("main.js"),"require('fs').writeFileSync('node-result.txt',require('fixture'));");
        var scope=new SkillExecutionScope("node",AgentStorage.directory(data,"skill-execution/node"),List.of(pkg));
        var properties=new com.myharness.agent.config.AgentProperties();properties.setDataDir(data);
        properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
        var node=new com.myharness.agent.config.AgentProperties.WindowsTool();
        node.setHome(Path.of(System.getProperty("windows.skill.node")));node.setExecutable("node.exe");properties.getWindowsTools().put("node",node);
        var arguments=json.createObjectNode().put("program","node");arguments.putArray("args").add(script.toString());
        var result=WindowsCommandTool.execute(workspace,properties,arguments,json,scope);
        assertEquals(0,result.exitCode(),result.output());assertEquals("NODE_SKILL_OK",Files.readString(workspace.resolve("node-result.txt")));
    }

    @Test void readsAndRunsOnlyCurrentSkillThenRevokesAllCommandGrants() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("project")),data=Files.createDirectory(root.resolve("data"));
        AgentStorage.protectDataDirectory(data);
        Path pkg=AgentStorage.directory(data,"private/current"),other=AgentStorage.directory(data,"private/other");
        Path entry=Files.writeString(pkg.resolve("SKILL.md"),"CURRENT_BODY");
        Path hidden=Files.writeString(other.resolve("SKILL.md"),"OTHER_BODY");
        Files.writeString(pkg.resolve("data.txt"),"RESOURCE");
        Files.writeString(pkg.resolve("main.py"),"from pathlib import Path\nassert Path(__file__).with_name('data.txt').read_text()=='RESOURCE'\nPath('result.txt').write_text('OK')\n");
        var scope=new SkillExecutionScope("first",AgentStorage.directory(data,"skill-execution/first"),List.of(pkg));
        Path python=Path.of(System.getProperty("windows.isolation.python"));
        var result=WindowsIsolatedCommand.executeScoped(workspace,"""
                import sys, subprocess, shutil
                from pathlib import Path
                entry=Path(%s)
                assert entry.read_text()=='CURRENT_BODY'
                for action in [lambda: entry.write_text('bad'),lambda: entry.unlink(),lambda: entry.rename(entry.with_name('moved.md')),lambda: entry.with_name('new.txt').write_text('bad'),lambda: Path(%s).read_text(),lambda: list(entry.parent.parent.iterdir())]:
                    try: action()
                    except PermissionError: pass
                    else: raise AssertionError('unauthorized operation allowed')
                child=subprocess.run([sys.executable,'-I','-S',str(entry.with_name('main.py'))],capture_output=True)
                assert child.returncode==0,child.stderr
                shutil.copyfile(entry,'copy.txt')
                print('READONLY_OK')
                """.formatted(json.writeValueAsString(entry.toString()),json.writeValueAsString(hidden.toString())),30,python,scope,List.of(),Map.of(),120);
        assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("READONLY_OK"));
        assertEquals("CURRENT_BODY",Files.readString(workspace.resolve("copy.txt")));
        assertEquals("OK",Files.readString(workspace.resolve("result.txt")));
        assertEquals("CURRENT_BODY",Files.readString(entry));
        var next=new SkillExecutionScope("next",AgentStorage.directory(data,"skill-execution/next"),List.of(other));
        var denied=WindowsIsolatedCommand.executeScoped(workspace,"from pathlib import Path;Path("+json.writeValueAsString(entry.toString())+").read_text()",15,python,next,List.of(),Map.of(),120);
        assertNotEquals(0,denied.exitCode());assertTrue(denied.output().contains("PermissionError"),denied.output());
        for(Path path:List.of(pkg,other,workspace,scope.temporaryDirectory(),next.temporaryDirectory())) {
            var acl=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class).getAcl();
            assertTrue(acl.stream().noneMatch(ace->ace.principal().getName().startsWith("S-1-15-2-")),path+": "+acl);
        }
    }
}
