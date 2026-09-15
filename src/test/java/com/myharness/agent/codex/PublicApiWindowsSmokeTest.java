package com.myharness.agent.codex;

import com.myharness.agent.workspace.AgentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="windows.public-api.smoke",matches="true")
class PublicApiWindowsSmokeTest {
    @TempDir Path root;

    @Test void packagedSkillScriptRunsWithGetPostAndRedirectThroughStructuredTool() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("workspace"));
        Path data=Files.createDirectory(root.resolve("data"));AgentStorage.protectDataDirectory(data);
        Path pkg=AgentStorage.directory(data,"skill/script");
        Path fixture=Path.of("../../examples/public-api-skill/harness-public-api-check/scripts/check_api.py").toAbsolutePath().normalize();
        Path script=Files.copy(fixture,pkg.resolve("check_api.py"));
        byte[] original=Files.readAllBytes(script);
        var scope=new SkillExecutionScope("script",AgentStorage.directory(data,"skill-execution/script"),List.of(pkg));
        var properties=new com.myharness.agent.config.AgentProperties();properties.setDataDir(data);
        properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
        properties.setCommandNetworkMode(com.myharness.agent.config.AgentProperties.CommandNetworkMode.PUBLIC);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var args=json.createObjectNode().put("program","python").put("timeout_seconds",90);
        args.putArray("args").add(script.toString()).add("--output").add(workspace.resolve("api.json").toString());
        var result=WindowsCommandTool.execute(workspace,properties,args,json,scope);
        assertEquals(0,result.exitCode(),result.output());
        var report=json.readTree(Files.readString(workspace.resolve("api.json")));
        for(String check:List.of("get","post","redirect"))assertEquals("PASS",report.path(check).asText());
        assertEquals(script.toString(),report.path("entrypoint").asText());
        assertArrayEquals(original,Files.readAllBytes(script));
    }

    @Test void publicModeRetainsFileAndLoopbackIsolationAndDisabledModeCannotConnect() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("workspace"));
        Path data=Files.createDirectory(root.resolve("data"));AgentStorage.protectDataDirectory(data);
        Path privateFile=Files.writeString(data.resolve("private.txt"),"PRIVATE_FIXTURE");
        var scope=new SkillExecutionScope("isolation",AgentStorage.directory(data,"skill-execution/isolation"),List.of());
        var properties=new com.myharness.agent.config.AgentProperties();properties.setDataDir(data);
        properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
        properties.setCommandNetworkMode(com.myharness.agent.config.AgentProperties.CommandNetworkMode.PUBLIC);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        try(var listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(2000);
            try(var control=new java.net.Socket("127.0.0.1",listener.getLocalPort());var accepted=listener.accept()) {
                assertTrue(accepted.isConnected());
            }
            String source="""
                    import socket, os
                    from pathlib import Path
                    for host in ('127.0.0.1',socket.gethostbyname('localhost')):
                        try: socket.create_connection((host,%d),timeout=3)
                        except (PermissionError,TimeoutError): pass
                        else: raise AssertionError('loopback allowed')
                    try: Path(%s).read_bytes()
                    except PermissionError: pass
                    else: raise AssertionError('private file readable')
                    assert not os.environ.get('HARNESS_MODEL_API_KEY')
                    print('BOUNDARY_OK')
                    """.formatted(listener.getLocalPort(),json.writeValueAsString(privateFile.toString()));
            var result=WindowsExecutionTools.execute(workspace,properties,json.createObjectNode().put("script",source),scope);
            assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("BOUNDARY_OK"));
            listener.setSoTimeout(200);
            assertThrows(java.net.SocketTimeoutException.class,listener::accept);
        }
        properties.setCommandNetworkMode(com.myharness.agent.config.AgentProperties.CommandNetworkMode.DISABLED);
        String publicAddress=java.net.InetAddress.getByName("httpbin.org").getHostAddress();
        var denied=WindowsExecutionTools.execute(workspace,properties,json.createObjectNode().put("script","""
                import socket
                try: socket.create_connection((%s,443),timeout=10)
                except (PermissionError,TimeoutError): print('PUBLIC_DENIED')
                else: raise AssertionError('disabled mode connected')
                """.formatted(json.writeValueAsString(publicAddress))),scope);
        assertEquals(0,denied.exitCode(),denied.output());assertTrue(denied.output().contains("PUBLIC_DENIED"));
    }

    @Test void startupProbeVerifiesPublicModeBeforeAdvertisingIt() throws Exception {
        var properties=new com.myharness.agent.config.AgentProperties();
        properties.setDataDir(Files.createDirectory(root.resolve("data")));
        properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
        properties.setCommandNetworkMode(com.myharness.agent.config.AgentProperties.CommandNetworkMode.PUBLIC);
        assertEquals("UNSUPPORTED",properties.isolationMode("Windows 10"));
        new ProjectIsolationCheck(properties,new com.fasterxml.jackson.databind.ObjectMapper()).initializeFor("Windows 10");
        assertEquals("WINDOWS_LPAC_API_V3",properties.isolationMode("Windows 10"));
    }

    @Test void pythonCanResolveAndCallPublicHttpAndHttpsInsideLpac() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("workspace"));
        Path data=Files.createDirectory(root.resolve("data"));AgentStorage.protectDataDirectory(data);
        var scope=new SkillExecutionScope("api",AgentStorage.directory(data,"skill-execution/api"),List.of());
        var result=WindowsIsolatedCommand.executeScoped(workspace,"""
                import socket, urllib.request, json
                print(socket.getaddrinfo('httpbin.org',443),flush=True)
                for scheme in ('http','https'):
                    with urllib.request.urlopen(scheme+'://httpbin.org/get?harness=public-api-probe', timeout=15) as response:
                        value=json.load(response)
                    assert value['args']['harness']=='public-api-probe'
                print('PUBLIC_API_OK')
                """,45,Path.of(System.getProperty("windows.isolation.python")),scope,List.of(),Map.of(),120,true);
        assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("PUBLIC_API_OK"),result.output());
    }
}
