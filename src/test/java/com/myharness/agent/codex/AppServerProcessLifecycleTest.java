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
