package com.myharness.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentApplicationTest {
    @Test
    void shouldRemainRunningAfterApplicationStarts() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory(
                Paths.get("target").toAbsolutePath().normalize(), "agent-lifetime-");
        Path dataDirectory = Files.createDirectories(temporaryDirectory.resolve("data"));
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Path outputFile = temporaryDirectory.resolve("agent-output.log");

        Process process = null;
        try (TestWebSocketServer server = new TestWebSocketServer()) {
            process = new ProcessBuilder(command(dataDirectory, workspace, server.getPort()))
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            String output = waitForConnection(process, outputFile);
            assertTrue(output.contains("Connected to Harness Server"),
                    "Agent did not connect to the test server. Output:\n" + output);

            Thread.sleep(2_000L);

            assertTrue(process.isAlive(),
                    "Agent exited after startup instead of remaining online. Output:\n" + read(outputFile));
        } finally {
            if (process != null) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            deleteRecursively(temporaryDirectory);
        }
    }

    private List<String> command(Path dataDirectory, Path workspace, int serverPort) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AgentApplication.class.getName());
        command.add("--spring.main.banner-mode=off");
        command.add("--harness.agent.server-url=ws://127.0.0.1:" + serverPort + "/ws/agent");
        command.add("--harness.agent.device-code=test-device");
        command.add("--harness.agent.device-token=test-token");
        command.add("--harness.agent.data-dir=" + dataDirectory.toAbsolutePath());
        command.add("--harness.agent.workspaces[0].name=test-workspace");
        command.add("--harness.agent.workspaces[0].path=" + workspace.toAbsolutePath());
        return command;
    }

    private String waitForConnection(Process process, Path outputFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String output = "";
        while (System.nanoTime() < deadline) {
            output = read(outputFile);
            if (output.contains("Connected to Harness Server") || !process.isAlive()) {
                return output;
            }
            Thread.sleep(50L);
        }
        return output;
    }

    private String read(Path outputFile) throws IOException {
        return Files.exists(outputFile)
                ? new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8) : "";
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", executable).toString();
    }

    private void deleteRecursively(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to clean test directory " + path, exception);
                }
            });
        }
    }

    private static final class TestWebSocketServer implements AutoCloseable {
        private static final String WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private final ServerSocket serverSocket;
        private volatile Socket connection;

        private TestWebSocketServer() throws IOException {
            serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread serverThread = new Thread(this::accept, "agent-application-test-websocket");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        private int getPort() {
            return serverSocket.getLocalPort();
        }

        private void accept() {
            try {
                connection = serverSocket.accept();
                InputStream input = connection.getInputStream();
                String request = readHeaders(input);
                String key = header(request, "Sec-WebSocket-Key");
                String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                                .digest((key + WEB_SOCKET_GUID).getBytes(StandardCharsets.ISO_8859_1)));
                OutputStream output = connection.getOutputStream();
                output.write(("HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                output.flush();
                while (input.read() != -1) {
                    // Drain client frames while keeping the connection open.
                }
            } catch (Exception ignored) {
                // Closing the fixture terminates the blocking accept/read operations.
            }
        }

        private String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int matched = 0;
            int current;
            while ((current = input.read()) != -1) {
                bytes.write(current);
                int expected = "\r\n\r\n".charAt(matched);
                matched = current == expected ? matched + 1 : 0;
                if (matched == 4) {
                    break;
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        private String header(String request, String name) {
            for (String line : request.split("\r\n")) {
                int separator = line.indexOf(':');
                if (separator > 0 && name.equalsIgnoreCase(line.substring(0, separator).trim())) {
                    return line.substring(separator + 1).trim();
                }
            }
            throw new IllegalStateException("Missing WebSocket header " + name);
        }

        @Override
        public void close() throws IOException {
            Socket current = connection;
            if (current != null) {
                current.close();
            }
            serverSocket.close();
        }
    }
}
