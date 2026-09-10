package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AppServerProcessLifecycleTest {
    @Test void failedStopRetainsDescendantsAfterTheirLauncherHasExited() throws Exception {
        var wrapper=org.mockito.Mockito.mock(Process.class);var parent=org.mockito.Mockito.mock(ProcessHandle.class);var child=org.mockito.Mockito.mock(ProcessHandle.class);
        org.mockito.Mockito.when(wrapper.toHandle()).thenReturn(parent);
        org.mockito.Mockito.when(wrapper.descendants()).thenAnswer(ignored->java.util.stream.Stream.of(child));
        org.mockito.Mockito.when(wrapper.getOutputStream()).thenReturn(java.io.OutputStream.nullOutputStream());
        org.mockito.Mockito.when(parent.onExit()).thenReturn(CompletableFuture.completedFuture(parent));
        org.mockito.Mockito.when(child.onExit()).thenReturn(CompletableFuture.completedFuture(child));
        org.mockito.Mockito.when(child.isAlive()).thenReturn(true);
        var adapter=new AppServerCodexAdapter(new AgentProperties(),new ObjectMapper());ReflectionTestUtils.setField(adapter,"process",wrapper);
        org.junit.jupiter.api.Assertions.assertThrows(CodexException.class,adapter::close);
        assertThat(ReflectionTestUtils.getField(adapter,"process")).isSameAs(wrapper);
        org.mockito.Mockito.when(wrapper.descendants()).thenAnswer(ignored->java.util.stream.Stream.empty());
        org.junit.jupiter.api.Assertions.assertThrows(CodexException.class,adapter::close);
        org.mockito.Mockito.verify(child,org.mockito.Mockito.times(2)).destroyForcibly();
        org.mockito.Mockito.when(child.isAlive()).thenReturn(false);adapter.close();
        assertThat(ReflectionTestUtils.getField(adapter,"process")).isNull();
    }
    @Test
    void closeWaitsForDescendantsEvenWhenLauncherExitsOnStdinEof() throws Exception {
        Process wrapper = fixture("wrapper").start();
        List<ProcessHandle> owned = new ArrayList<>();
        AppServerCodexAdapter adapter = new AppServerCodexAdapter(new AgentProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(adapter, "process", wrapper);
        try {
            CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
                try {
                    return new BufferedReader(new InputStreamReader(wrapper.getInputStream(), StandardCharsets.UTF_8)).readLine();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertThat(ready.get(5, TimeUnit.SECONDS)).isEqualTo("READY");
            owned.addAll(wrapper.descendants().toList());
            assertThat(owned).isNotEmpty();
            owned.add(wrapper.toHandle());

            adapter.close();

            assertThat(owned).noneMatch(ProcessHandle::isAlive);
            adapter.close();
        } finally {
            owned.addAll(wrapper.descendants().toList());
            owned.add(wrapper.toHandle());
            for (ProcessHandle handle : owned) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                    handle.onExit().get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static ProcessBuilder fixture(String mode) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName(), mode);
    }

    public static class ProcessFixture {
        public static void main(String[] args) throws Exception {
            if ("wrapper".equals(args[0])) {
                fixture("child").inheritIO().start();
                while (System.in.read() != -1) {
                    // Model a launcher that exits on EOF without reaping its child.
                }
            } else {
                System.out.println("READY");
                System.out.flush();
                Thread.sleep(60_000);
            }
        }
    }
}
