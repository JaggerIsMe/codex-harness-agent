package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="windows.isolation.smoke",matches="true")
class WindowsCommandToolTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();private Path project;
    private AgentProperties properties() throws Exception {
        project=Files.createDirectory(root.resolve("project"));var properties=new AgentProperties();properties.setDataDir(Files.createDirectory(root.resolve("data")));
        properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));return properties;
    }
    private ObjectNode command(String program,String...args) {
        var input=json.createObjectNode().put("program",program);var values=input.putArray("args");for(String arg:args)values.add(arg);return input;
    }
    @AfterEach void cleanup() throws Exception {if(project!=null)WindowsIsolatedCommand.cleanupProfile(project,Path.of(System.getProperty("windows.isolation.python")));}
    @Test @EnabledIfSystemProperty(named="windows.command.long",matches="true")
    void commandCanRunBeyondOriginalTwoMinuteLimit() throws Exception {
        var properties=properties();var input=command("python","-c","import time;time.sleep(121);print('LONG_COMMAND_OK')");input.put("timeout_seconds",150);
        var result=WindowsCommandTool.execute(project,properties,input,json);assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("LONG_COMMAND_OK"));
    }
    @Test void structuredPythonArgumentsPreserveTextAndBlockOutsideReads() throws Exception {
        var properties=properties();Path outside=Files.writeString(root.resolve("outside.txt"),"PRIVATE_FIXTURE");
        var input=command("python","-c","import sys;from pathlib import Path;print(sys.argv[1]);Path('done.txt').write_text('ok');\ntry: Path(sys.argv[2]).read_text()\nexcept PermissionError: print('READ_DENIED')\nelse: raise AssertionError('leak')","spaces & 中文",outside.toString());
        input.put("timeout_seconds",1800);
        var result=WindowsCommandTool.execute(project,properties,input,json);
        assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("spaces & 中文"));assertTrue(result.output().contains("READ_DENIED"));
        assertEquals("ok",Files.readString(project.resolve("done.txt")));
    }
    @Test void timeoutStopsNestedCommandAndRejectsUnconfiguredPrograms() throws Exception {
        var properties=properties();
        assertThrows(CodexException.class,()->WindowsCommandTool.execute(project,properties,command("powershell","-Command","anything"),json));
        var input=command("python","-c","import os,time;from pathlib import Path;Path('pid.txt').write_text(str(os.getpid()));time.sleep(30)");input.put("timeout_seconds",2);
        assertThrows(CodexException.class,()->WindowsCommandTool.execute(project,properties,input,json));
        long pid=Long.parseLong(Files.readString(project.resolve("pid.txt")));assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void privateNodeToolchainCanBuildInsideWorkspace() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("windows.toolchain.test.home")!=null);
        var properties=properties();Path runtimes=Path.of(System.getProperty("windows.toolchain.test.home"));
        tool(properties,"node",runtimes.resolve("node"),"node.exe");
        Files.writeString(project.resolve("build.js"),"require('fs').writeFileSync('built.txt','NODE_OK'); console.log('NODE_OK')");
        var node=WindowsCommandTool.execute(project,properties,command("node","build.js"),json);assertEquals(0,node.exitCode(),node.output());
        assertEquals("NODE_OK",Files.readString(project.resolve("built.txt")));
    }
    @Test void privateJavaToolchainCanBuildInsideWorkspace() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("windows.toolchain.test.home")!=null);
        var properties=properties();Path runtimes=Path.of(System.getProperty("windows.toolchain.test.home"));
        tool(properties,"java",runtimes.resolve("java"),"bin/java.exe");tool(properties,"javac",runtimes.resolve("java"),"bin/javac.exe");
        Files.writeString(project.resolve("Hello.java"),"class Hello { public static void main(String[] args) { System.out.println(\"JAVA_OK\"); } }");
        var compile=WindowsCommandTool.execute(project,properties,command("javac","Hello.java"),json);assertEquals(0,compile.exitCode(),compile.output());
        var java=WindowsCommandTool.execute(project,properties,command("java","-cp",".","Hello"),json);assertEquals(0,java.exitCode(),java.output());assertTrue(java.output().contains("JAVA_OK"));
    }
    private void tool(AgentProperties properties,String name,Path home,String executable) {
        var tool=new AgentProperties.WindowsTool();tool.setHome(home);tool.setExecutable(executable);properties.getWindowsTools().put(name,tool);
    }
    @Test void privateNpmLauncherWorksWithoutNetwork() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("windows.toolchain.test.home")!=null);
        var properties=properties();Path runtimes=Path.of(System.getProperty("windows.toolchain.test.home"));
        tool(properties,"node",runtimes.resolve("node"),"node.exe");tool(properties,"npm",runtimes.resolve("node"),"npm.cmd");
        Files.writeString(project.resolve("package.json"),"{\"scripts\":{\"build\":\"node build.js\"}}");
        Files.writeString(project.resolve("build.js"),"require('fs').writeFileSync('built.txt','NPM_OK')");
        var npm=WindowsCommandTool.execute(project,properties,command("npm","run","build","--offline"),json);assertEquals(0,npm.exitCode(),npm.output());
        assertEquals("NPM_OK",Files.readString(project.resolve("built.txt")));
    }
    @Test void mavenCanonicalPathCompatibilityDoesNotWeakenIsolation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("windows.toolchain.test.home")!=null);
        var properties=properties();Path runtimes=Path.of(System.getProperty("windows.toolchain.test.home"));
        tool(properties,"java",runtimes.resolve("java"),"bin/java.exe");tool(properties,"maven",runtimes.resolve("maven"),"bin/mvn.cmd");
        Files.writeString(project.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>fixture</artifactId><version>1</version><packaging>pom</packaging></project>");
        Files.writeString(project.resolve("PathProbe.java"),"class PathProbe {public static void main(String[] a) throws Exception {System.out.println(java.nio.file.Path.of(\"\").toAbsolutePath());try {System.out.println(java.nio.file.Path.of(\"\").toRealPath());}catch(Exception e){e.printStackTrace();}}}");
        var probe=WindowsCommandTool.execute(project,properties,command("java","PathProbe.java"),json);
        assertTrue(probe.output().contains("AccessDeniedException"),probe.output());
        var maven=WindowsCommandTool.execute(project,properties,command("maven","-o","validate"),json);
        assertNotEquals(0,maven.exitCode(),"Maven became compatible: review this limitation test before enabling it");
        assertTrue(maven.output().contains("MavenCli"),maven.output());
    }
}
