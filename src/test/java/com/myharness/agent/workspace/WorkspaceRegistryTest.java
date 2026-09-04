package com.myharness.agent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.config.WorkspaceProperties;
import com.myharness.agent.config.WorkspaceRootProperties;
import com.myharness.agent.entity.dto.CreateWorkspaceCommandDTO;
import com.myharness.agent.entity.dto.WorkspaceCreateResultEventDTO;
import com.myharness.agent.entity.vo.WorkspaceVO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceRegistryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldListConfiguredWorkspacesAndResolvePaths() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path existingDirectory = Files.createDirectories(root.resolve("src/main"));
        WorkspaceRegistry registry = registry(workspace("demo-project", root));

        List<WorkspaceVO> workspaces = registry.list();

        assertEquals(1, workspaces.size());
        assertEquals("demo-project", workspaces.get(0).getName());
        assertEquals(root.toRealPath().toString(), workspaces.get(0).getRootPath());
        assertEquals(existingDirectory.toRealPath(), registry.resolve("DEMO-PROJECT", "src/main"));
        assertEquals(root.toRealPath().resolve("src/new-file.txt"),
                registry.resolve("demo-project", "src/new-file.txt"));
    }

    @Test
    void shouldRejectParentAndAbsolutePathEscapes() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        WorkspaceRegistry registry = registry(workspace("demo", root));

        assertThrows(WorkspaceAccessException.class, () -> registry.resolve("demo", "../outside.txt"));
        assertThrows(WorkspaceAccessException.class,
                () -> registry.resolve("demo", temporaryDirectory.resolve("outside.txt").toString()));
    }

    @Test
    void shouldRejectUnknownWorkspace() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        WorkspaceRegistry registry = registry(workspace("demo", root));

        assertThrows(WorkspaceAccessException.class, () -> registry.resolve("missing", "README.md"));
    }

    @Test
    void shouldRejectRelativeWorkspaceRoot() {
        WorkspaceProperties configured = workspace("demo", temporaryDirectory.getFileName());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> registry(configured));

        assertEquals("Workspace path must be absolute: demo", exception.getMessage());
    }

    @Test
    void shouldRejectDuplicateWorkspaceNamesIgnoringCase() throws IOException {
        Path firstRoot = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path secondRoot = Files.createDirectory(temporaryDirectory.resolve("second"));
        AgentProperties properties = properties();
        properties.setWorkspaces(Arrays.asList(
                workspace("Demo", firstRoot),
                workspace("demo", secondRoot)
        ));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new WorkspaceRegistry(properties, new ObjectMapper()));

        assertEquals("Duplicate workspace name: demo", exception.getMessage());
    }

    @Test
    void shouldRejectWorkspaceOverlappingAgentDataDirectory() throws IOException {
        Path dataDirectory = Files.createDirectory(temporaryDirectory.resolve("agent-data"));
        Path workspaceRoot = Files.createDirectory(dataDirectory.resolve("project"));
        AgentProperties properties = properties();
        properties.setDataDir(dataDirectory);
        properties.setWorkspaces(Collections.singletonList(workspace("demo", workspaceRoot)));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new WorkspaceRegistry(properties, new ObjectMapper()));

        assertEquals("Workspace must not overlap the agent data directory: demo", exception.getMessage());
    }

    @Test
    void shouldRejectSymbolicLinkEscapeWhenSupported() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Path link = root.resolve("external");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable in this test environment");
        }
        WorkspaceRegistry registry = registry(workspace("demo", root));

        assertThrows(WorkspaceAccessException.class, () -> registry.resolve("demo", "external/secret.txt"));
    }

    @Test
    void shouldCreatePersistAndReloadDynamicWorkspace() throws IOException {
        Path parent = Files.createDirectory(temporaryDirectory.resolve("projects"));
        AgentProperties properties = properties();
        properties.setWorkspaceRoots(Collections.singletonList(workspaceRoot("development", parent)));
        WorkspaceRegistry registry = new WorkspaceRegistry(properties, new ObjectMapper());

        WorkspaceCreateResultEventDTO result = registry.create(createCommand("41", "development", "order-service"));

        assertTrue(result.isSuccess());
        assertTrue(Files.isDirectory(parent.resolve("order-service")));
        assertEquals(parent.resolve("order-service").toRealPath(), registry.resolve("order-service", ""));
        WorkspaceRegistry reloaded = new WorkspaceRegistry(properties, new ObjectMapper());
        assertEquals(parent.resolve("order-service").toRealPath(), reloaded.resolve("ORDER-SERVICE", ""));
    }

    @Test
    void shouldReturnSameWorkspaceForDurableDuplicateRequest() throws IOException {
        Path parent = Files.createDirectory(temporaryDirectory.resolve("projects"));
        AgentProperties properties = properties();
        properties.setWorkspaceRoots(Collections.singletonList(workspaceRoot("development", parent)));
        WorkspaceRegistry registry = new WorkspaceRegistry(properties, new ObjectMapper());

        WorkspaceCreateResultEventDTO first = registry.create(createCommand("42", "development", "billing"));
        WorkspaceCreateResultEventDTO duplicate = registry.create(createCommand("42", "development", "billing"));

        assertTrue(first.isSuccess());
        assertTrue(duplicate.isSuccess());
        assertEquals(first.getRootPath(), duplicate.getRootPath());
        assertEquals(1, registry.list().size());
    }

    @Test
    void shouldRejectUnsafeNameAndExistingTarget() throws IOException {
        Path parent = Files.createDirectory(temporaryDirectory.resolve("projects"));
        Files.createDirectory(parent.resolve("existing"));
        AgentProperties properties = properties();
        properties.setWorkspaceRoots(Collections.singletonList(workspaceRoot("development", parent)));
        WorkspaceRegistry registry = new WorkspaceRegistry(properties, new ObjectMapper());

        WorkspaceCreateResultEventDTO unsafe = registry.create(createCommand("43", "development", "../escape"));
        WorkspaceCreateResultEventDTO existing = registry.create(createCommand("44", "development", "existing"));

        assertFalse(unsafe.isSuccess());
        assertEquals("INVALID_WORKSPACE_NAME", unsafe.getErrorCode());
        assertFalse(existing.isSuccess());
        assertEquals("WORKSPACE_PATH_EXISTS", existing.getErrorCode());
    }

    @Test
    void shouldRejectSameNameFromAnotherDurableRequest() throws IOException {
        Path parent = Files.createDirectory(temporaryDirectory.resolve("projects"));
        AgentProperties properties = properties();
        properties.setWorkspaceRoots(Collections.singletonList(workspaceRoot("development", parent)));
        WorkspaceRegistry registry = new WorkspaceRegistry(properties, new ObjectMapper());

        assertTrue(registry.create(createCommand("45", "development", "catalog")).isSuccess());
        WorkspaceCreateResultEventDTO duplicateName = registry.create(
                createCommand("46", "development", "CATALOG"));

        assertFalse(duplicateName.isSuccess());
        assertEquals("WORKSPACE_ALREADY_EXISTS", duplicateName.getErrorCode());
    }

    private WorkspaceRegistry registry(WorkspaceProperties workspace) {
        AgentProperties properties = properties();
        properties.setWorkspaces(Collections.singletonList(workspace));
        return new WorkspaceRegistry(properties, new ObjectMapper());
    }

    private AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.setDataDir(temporaryDirectory.resolve("agent-state"));
        return properties;
    }

    private WorkspaceProperties workspace(String name, Path path) {
        WorkspaceProperties workspace = new WorkspaceProperties();
        workspace.setName(name);
        workspace.setPath(path);
        return workspace;
    }

    private WorkspaceRootProperties workspaceRoot(String name, Path path) {
        WorkspaceRootProperties root = new WorkspaceRootProperties();
        root.setName(name);
        root.setPath(path);
        return root;
    }

    private CreateWorkspaceCommandDTO createCommand(String requestId, String parentName, String workspaceName) {
        CreateWorkspaceCommandDTO command = new CreateWorkspaceCommandDTO();
        command.setRequestId(requestId);
        command.setParentName(parentName);
        command.setWorkspaceName(workspaceName);
        command.setProjectType("empty");
        return command;
    }
}
