package com.myharness.agent.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="windows.isolation.smoke",matches="true")
class WindowsIsolatedCommandTest {
    private Path project;
    private Path python() {return Path.of(System.getProperty("windows.isolation.python"));}
    @org.junit.jupiter.api.AfterEach void cleanup() throws Exception {
        if(project!=null) WindowsIsolatedCommand.cleanupProfile(project,python());
    }
    @Test void runsPythonInsideLpac(@TempDir Path root) throws Exception {
        project=Files.createDirectory(root.resolve("project"));
        Path outside=Files.writeString(root.resolve("outside.txt"),"OUTSIDE");
        Files.writeString(project.resolve("inside.txt"),"INSIDE");
        var result=WindowsIsolatedCommand.execute(project,"""
                from pathlib import Path
                assert Path('inside.txt').read_text()=='INSIDE'
                Path('created.txt').write_text('WRITE_OK')
                outside=Path(%s)
                read_denied=write_denied=False
                try: outside.read_bytes()
                except PermissionError: read_denied=True
                try: outside.write_text('CHANGED')
                except PermissionError: write_denied=True
                print(f'RESULT read={read_denied} write={write_denied}')
                """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(outside.toString())),15,
                Path.of(System.getProperty("windows.isolation.python")));
        assertEquals(0,result.exitCode(),result.output());
        assertTrue(result.output().contains("RESULT read=True write=True"),result.output());
        assertEquals("OUTSIDE",Files.readString(outside));
        assertTrue(Files.readString(project.resolve("created.txt")).contains("WRITE_OK"));
    }

    @Test void deniesJunctionPublicAppFileNetworkAndMetadataWrites(@TempDir Path root) throws Exception {
        project=Files.createDirectory(root.resolve("project"));
        Path outsideDirectory=Files.createDirectory(root.resolve("outside"));
        Path outside=Files.writeString(outsideDirectory.resolve("secret.txt"),"OUTSIDE_SECRET");
        var acl=new ProcessBuilder("icacls.exe",outside.toString(),"/grant","*S-1-15-2-1:R").redirectErrorStream(true).start();
        assertTrue(acl.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,acl.exitValue());
        Path link=project.resolve("outside-link");
        var junction=new ProcessBuilder(System.getenv("ComSpec"),"/d","/c","mklink","/J",link.toString(),outsideDirectory.toString()).redirectErrorStream(true).start();
        assertTrue(junction.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,junction.exitValue());
        Files.createDirectory(project.resolve(".git"));Files.writeString(project.resolve(".git/config"),"PROTECTED");
        try(var server=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            try(var connection=new java.net.Socket("127.0.0.1",server.getLocalPort());var accepted=server.accept()) {assertTrue(connection.isConnected());}
            var result=WindowsIsolatedCommand.execute(project,"""
                    from pathlib import Path
                    import socket,subprocess,sys
                    for path in [Path(%s),Path('outside-link/secret.txt')]:
                        try: path.read_bytes()
                        except PermissionError: pass
                        else: raise AssertionError('external read allowed')
                    for path in [Path('.git/config'),Path('.codex/new-config')]:
                        try: path.write_text('CHANGED')
                        except PermissionError: pass
                        else: raise AssertionError('metadata write allowed: '+str(path))
                    child=subprocess.run([sys.executable,'-I','-S','-c',"from pathlib import Path;Path('../outside/secret.txt').read_bytes()"],capture_output=True)
                    assert child.returncode!=0 and b'PermissionError' in child.stderr
                    s=None
                    try:
                        s=socket.socket();s.settimeout(2);s.connect(('127.0.0.1',%d))
                    except PermissionError: pass
                    else: raise AssertionError('network allowed')
                    finally:
                        if s is not None: s.close()
                    print('BOUNDARIES_OK')
                    """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(outside.toString()),server.getLocalPort()),15,python());
            assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("BOUNDARIES_OK"),result.output());
            assertEquals("OUTSIDE_SECRET",Files.readString(outside));assertEquals("PROTECTED",Files.readString(project.resolve(".git/config")));
        } finally {Files.deleteIfExists(link);}
    }

    @Test void timeoutTerminatesChildProcesses(@TempDir Path root) throws Exception {
        project=Files.createDirectory(root.resolve("project"));
        assertThrows(CodexException.class,()->WindowsIsolatedCommand.execute(project,"""
                import subprocess,sys,time
                from pathlib import Path
                child=subprocess.Popen([sys.executable,'-I','-S','-c','import time;time.sleep(30)'])
                Path('child.pid').write_text(str(child.pid))
                time.sleep(30)
                """,2,python()));
        long pid=Long.parseLong(Files.readString(project.resolve("child.pid")));
        for(int i=0;i<20 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);i++) Thread.sleep(100);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"Timed out child remained alive");
    }

    @Test void interruptTerminatesRunningCommandBeforeReturning(@TempDir Path root) throws Exception {
        project=Files.createDirectory(root.resolve("project"));
        var result=new java.util.concurrent.CompletableFuture<Throwable>();
        Thread worker=Thread.ofVirtual().start(()-> {
            try {WindowsIsolatedCommand.execute(project,"import os,time;from pathlib import Path;Path('running.pid').write_text(str(os.getpid()));time.sleep(30)",40,python());result.complete(null);}
            catch(Throwable failure) {result.complete(failure);}
        });
        try {
            for(int i=0;i<100 && !Files.exists(project.resolve("running.pid"));i++) Thread.sleep(50);
            long pid=Long.parseLong(Files.readString(project.resolve("running.pid")));
            worker.interrupt();worker.join(5000);
            assertFalse(worker.isAlive());assertInstanceOf(CodexException.class,result.get(1,java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {worker.interrupt();worker.join(5000);}
    }

    @Test void startupSelfTestAdvertisesNativeWindowsOnlyAfterPassing(@TempDir Path root) {
        var properties=new com.myharness.agent.config.AgentProperties();properties.setDataDir(root);properties.setWindowsPython(python());
        assertEquals("UNSUPPORTED",properties.isolationMode("Windows 10"));
        new ProjectIsolationCheck(properties,new com.fasterxml.jackson.databind.ObjectMapper()).initializeFor("Windows 10");
        assertEquals("WINDOWS_LPAC_V1",properties.isolationMode("Windows 10"));
    }

    @Test void neverPromotesOutsideHardLinksIntoWorkspaceAccess(@TempDir Path root) throws Exception {
        project=Files.createDirectory(root.resolve("project"));
        Path outside=Files.writeString(root.resolve("outside.txt"),"OUTSIDE");
        Files.createLink(project.resolve("preexisting-link"),outside);
        assertThrows(CodexException.class,()->WindowsIsolatedCommand.execute(project,"pass",10,python()));
        Files.delete(project.resolve("preexisting-link"));
        assertEquals(0,WindowsIsolatedCommand.execute(project,"pass",10,python()).exitCode());
        // Emulate a link placed after initial provisioning; a later command must not
        // reapply inheritable write grants to the linked outside file.
        Files.createLink(project.resolve("late-link"),outside);
        var result=WindowsIsolatedCommand.execute(project,"""
                from pathlib import Path
                try: Path('late-link').read_text()
                except PermissionError: print('HARDLINK_DENIED')
                else: raise AssertionError('hard link escaped isolation')
                """,10,python());
        assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("HARDLINK_DENIED"));
        assertEquals("OUTSIDE",Files.readString(outside));
    }

    @Test void cannotRewriteAclEvenForFilesOwnedByTheHostUser(@TempDir Path root) throws Exception {
        project=Files.createDirectory(root.resolve("project"));
        Path outside=Files.writeString(root.resolve("outside.txt"),"OUTSIDE");
        String user=com.sun.jna.platform.win32.Advapi32Util.getUserName();
        for(Path path:new Path[]{project,outside}) {
            String owner=Files.getOwner(path).getName();
            assertTrue(owner.equalsIgnoreCase(user) || owner.toLowerCase(java.util.Locale.ROOT).endsWith("\\"+user.toLowerCase(java.util.Locale.ROOT)),"Fixture must be owned by current user: "+owner+" / "+user);
        }
        var result=WindowsIsolatedCommand.execute(project,"""
                import ctypes
                from ctypes import wintypes
                from pathlib import Path
                update=ctypes.WinDLL('advapi32',use_last_error=True).SetNamedSecurityInfoW
                update.restype=wintypes.DWORD
                update.argtypes=[wintypes.LPWSTR,wintypes.DWORD,wintypes.DWORD,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p]
                for path in [%s,str(Path('.').resolve()),str(Path('.codex').resolve())]:
                    assert update(path,1,4,None,None,None,None)==5, 'unexpected WRITE_DAC access'
                print('ACL_ESCAPE_DENIED')
                """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(outside.toString())),10,python());
        assertEquals(0,result.exitCode(),result.output());assertTrue(result.output().contains("ACL_ESCAPE_DENIED"));
        assertEquals("OUTSIDE",Files.readString(outside));
    }
}
