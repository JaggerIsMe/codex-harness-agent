package com.myharness.agent.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.config.WorkspaceProperties;
import com.myharness.agent.config.WorkspaceRootProperties;
import com.myharness.agent.entity.dto.CreateWorkspaceCommandDTO;
import com.myharness.agent.entity.dto.WorkspaceCreateResultEventDTO;
import com.myharness.agent.entity.vo.WorkspaceRootVO;
import com.myharness.agent.entity.vo.WorkspaceVO;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Component
public class WorkspaceRegistry {
    private static final int MANIFEST_VERSION = 1;
    private static final String MANIFEST_FILE_NAME = "workspaces.json";
    private static final String MARKER_FILE_NAME = ".harness-workspace.json";
    private static final Pattern SAFE_WORKSPACE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern WINDOWS_RESERVED_NAME = Pattern.compile(
            "(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    private final ObjectMapper objectMapper;
    private final Path dataDirectory;
    private final Path manifestFile;
    private final Map<String, RegisteredRoot> roots;
    private final List<WorkspaceRootVO> rootViews;
    private final List<RegisteredWorkspace> configuredWorkspaces;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final ReentrantLock mutationLock = new ReentrantLock();
    private List<DynamicWorkspaceRecord> dynamicRecords;

    public WorkspaceRegistry(AgentProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dataDirectory = prepareDataDirectory(properties.getDataDir());
        this.manifestFile = dataDirectory.resolve(MANIFEST_FILE_NAME);
        this.roots = loadRoots(properties.getWorkspaceRoots());
        this.rootViews = rootViews(roots);
        this.configuredWorkspaces = loadConfiguredWorkspaces(properties.getWorkspaces());
        this.dynamicRecords = loadManifest();
        this.snapshot.set(recoverSnapshot());
    }

    public List<WorkspaceVO> list() { return snapshot.get().views; }

    public List<WorkspaceRootVO> roots() { return rootViews; }

    public WorkspaceCreateResultEventDTO create(CreateWorkspaceCommandDTO command) {
        String requestId = trim(command == null ? null : command.getRequestId());
        String parentName = trim(command == null ? null : command.getParentName());
        String workspaceName = trim(command == null ? null : command.getWorkspaceName());
        String projectType = trim(command == null ? null : command.getProjectType());
        if (projectType == null) projectType = "empty";

        try {
            requireRequestId(requestId);
            requireCreatableWorkspaceName(workspaceName);
            if (parentName == null) throw failure("INVALID_PARENT", "Workspace parent name must not be blank");
            if (!"empty".equals(projectType)) {
                throw failure("UNSUPPORTED_PROJECT_TYPE", "Unsupported project type: " + projectType);
            }
        } catch (WorkspaceCreationFailure exception) {
            return WorkspaceCreateResultEventDTO.failure(requestId, workspaceName,
                    exception.errorCode, exception.getMessage());
        }

        mutationLock.lock();
        try {
            DynamicWorkspaceRecord existingRequest = findByRequestId(requestId);
            if (existingRequest != null) {
                if (!sameRequest(existingRequest, parentName, workspaceName, projectType)) {
                    throw failure("REQUEST_CONFLICT", "Workspace request id was already used with different parameters");
                }
                if ("ENABLED".equals(existingRequest.getState())) {
                    Path existingTarget = target(existingRequest);
                    return WorkspaceCreateResultEventDTO.success(requestId, workspaceName, existingTarget.toString());
                }
                existingRequest.setState("CREATING");
                persistManifest();
                return continueCreation(existingRequest);
            }

            RegisteredRoot parent = findRoot(parentName);
            Path target = target(parent, workspaceName);
            rejectWorkspaceConflict(workspaceName, target, null);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("WORKSPACE_PATH_EXISTS", "Workspace target already exists: " + workspaceName);
            }

            DynamicWorkspaceRecord record = new DynamicWorkspaceRecord();
            record.setRequestId(requestId);
            record.setParentName(parent.displayName);
            record.setWorkspaceName(workspaceName);
            record.setProjectType(projectType);
            record.setState("CREATING");
            record.setTemporaryDirectoryName(".harness-create-" + UUID.randomUUID());
            dynamicRecords.add(record);
            persistManifest();
            return continueCreation(record);
        } catch (WorkspaceCreationFailure exception) {
            return WorkspaceCreateResultEventDTO.failure(requestId, workspaceName,
                    exception.errorCode, exception.getMessage());
        } catch (RuntimeException exception) {
            return WorkspaceCreateResultEventDTO.failure(requestId, workspaceName,
                    "CREATE_FAILED", safeMessage(exception));
        } finally {
            mutationLock.unlock();
        }
    }

    public Path resolve(String workspaceName, String relativePath) {
        RegisteredWorkspace workspace = findWorkspace(workspaceName);
        Path requestedPath = parseRelativePath(relativePath);
        Path lexicalTarget = workspace.root.resolve(requestedPath).normalize();
        if (!lexicalTarget.startsWith(workspace.root)) {
            throw new WorkspaceAccessException("Path escapes workspace: " + relativePath);
        }
        Path resolvedTarget = resolveThroughExistingAncestor(workspace.root, lexicalTarget);
        if (!resolvedTarget.startsWith(workspace.root)) {
            throw new WorkspaceAccessException("Path escapes workspace through a symbolic link: " + relativePath);
        }
        return resolvedTarget;
    }

    private WorkspaceCreateResultEventDTO continueCreation(DynamicWorkspaceRecord record) {
        try {
            RegisteredRoot parent = findRoot(record.getParentName());
            Path target = target(parent, record.getWorkspaceName());
            Path temporary = parent.root.resolve(record.getTemporaryDirectoryName()).normalize();
            if (!temporary.getParent().equals(parent.root)) {
                throw failure("INVALID_TEMPORARY_PATH", "Invalid workspace temporary directory");
            }

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!markerMatches(target, record.getRequestId())) {
                    throw failure("WORKSPACE_PATH_EXISTS", "Workspace target is not owned by this request");
                }
            } else {
                if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(temporary);
                    writeMarker(temporary, record);
                } else if (!markerMatches(temporary, record.getRequestId())) {
                    throw failure("TEMPORARY_PATH_CONFLICT", "Workspace temporary directory is not owned by this request");
                }
                moveDirectory(temporary, target);
            }

            Path realTarget = target.toRealPath();
            if (!realTarget.getParent().equals(parent.root) || !Files.isDirectory(realTarget)) {
                throw failure("WORKSPACE_PATH_ESCAPE", "Created workspace escaped the configured parent");
            }
            rejectWorkspaceConflict(record.getWorkspaceName(), realTarget, record.getRequestId());
            record.setState("ENABLED");
            persistManifest();
            snapshot.set(buildSnapshot(dynamicRecords));
            return WorkspaceCreateResultEventDTO.success(record.getRequestId(), record.getWorkspaceName(),
                    realTarget.toString());
        } catch (WorkspaceCreationFailure exception) {
            return WorkspaceCreateResultEventDTO.failure(record.getRequestId(), record.getWorkspaceName(),
                    exception.errorCode, exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            return WorkspaceCreateResultEventDTO.failure(record.getRequestId(), record.getWorkspaceName(),
                    "CREATE_FAILED", safeMessage(exception));
        }
    }

    private Snapshot recoverSnapshot() {
        boolean changed = false;
        for (DynamicWorkspaceRecord record : dynamicRecords) {
            if (!"CREATING".equals(record.getState())) continue;
            try {
                RegisteredRoot parent = findRoot(record.getParentName());
                Path target = target(parent, record.getWorkspaceName());
                Path temporary = parent.root.resolve(record.getTemporaryDirectoryName()).normalize();
                if (Files.isDirectory(target) && markerMatches(target, record.getRequestId())) {
                    record.setState("ENABLED");
                    changed = true;
                } else if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(temporary) && markerMatches(temporary, record.getRequestId())) {
                    moveDirectory(temporary, target);
                    record.setState("ENABLED");
                    changed = true;
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep CREATING so a durable retry can resume the same request.
            }
        }
        Snapshot recovered = buildSnapshot(dynamicRecords);
        if (changed) persistManifest();
        return recovered;
    }

    private Snapshot buildSnapshot(List<DynamicWorkspaceRecord> records) {
        LinkedHashMap<String, RegisteredWorkspace> values = new LinkedHashMap<>();
        for (RegisteredWorkspace configured : configuredWorkspaces) addWorkspace(values, configured);
        for (DynamicWorkspaceRecord record : records) {
            if (!"ENABLED".equals(record.getState())) continue;
            requireRequestId(record.getRequestId());
            requireCreatableWorkspaceName(record.getWorkspaceName());
            RegisteredRoot parent = findRoot(record.getParentName());
            Path target = target(parent, record.getWorkspaceName());
            try {
                target = target.toRealPath();
            } catch (IOException exception) {
                throw new IllegalStateException("Dynamic workspace path does not exist: " + record.getWorkspaceName(), exception);
            }
            RegisteredWorkspace workspace = new RegisteredWorkspace(record.getWorkspaceName(), target);
            rejectOverlap(values, workspace);
            addWorkspace(values, workspace);
        }
        List<WorkspaceVO> views = new ArrayList<>();
        for (RegisteredWorkspace workspace : values.values()) {
            views.add(new WorkspaceVO(workspace.displayName, workspace.root.toString()));
        }
        return new Snapshot(Collections.unmodifiableMap(values), Collections.unmodifiableList(views));
    }

    private Map<String, RegisteredRoot> loadRoots(List<WorkspaceRootProperties> configuredRoots) {
        LinkedHashMap<String, RegisteredRoot> values = new LinkedHashMap<>();
        if (configuredRoots == null) configuredRoots = Collections.emptyList();
        for (WorkspaceRootProperties configured : configuredRoots) {
            String name = requireName(configured.getName(), "Workspace parent name must not be blank");
            String key = normalizeName(name);
            if (values.containsKey(key)) throw new IllegalStateException("Duplicate workspace parent name: " + name);
            Path root = requireDirectory(name, configured.getPath(), "Workspace parent");
            rejectDataDirectoryOverlap(name, root);
            for (RegisteredRoot existing : values.values()) {
                if (overlaps(root, existing.root)) {
                    throw new IllegalStateException("Workspace parents must not overlap: " + name);
                }
            }
            values.put(key, new RegisteredRoot(name, root));
        }
        return Collections.unmodifiableMap(values);
    }

    private List<RegisteredWorkspace> loadConfiguredWorkspaces(List<WorkspaceProperties> configured) {
        LinkedHashMap<String, RegisteredWorkspace> values = new LinkedHashMap<>();
        if (configured == null) configured = Collections.emptyList();
        for (WorkspaceProperties item : configured) {
            String name = requireName(item.getName(), "Workspace name must not be blank");
            Path root = requireDirectory(name, item.getPath(), "Workspace");
            rejectDataDirectoryOverlap(name, root);
            RegisteredWorkspace workspace = new RegisteredWorkspace(name, root);
            rejectOverlap(values, workspace);
            addWorkspace(values, workspace);
        }
        return Collections.unmodifiableList(new ArrayList<>(values.values()));
    }

    private List<WorkspaceRootVO> rootViews(Map<String, RegisteredRoot> values) {
        List<WorkspaceRootVO> views = new ArrayList<>();
        for (RegisteredRoot root : values.values()) views.add(new WorkspaceRootVO(root.displayName));
        return Collections.unmodifiableList(views);
    }

    private List<DynamicWorkspaceRecord> loadManifest() {
        if (!Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Workspace manifest is not a regular file: " + manifestFile);
        }
        try {
            WorkspaceManifest manifest = objectMapper.readValue(manifestFile.toFile(), WorkspaceManifest.class);
            if (manifest.getVersion() != MANIFEST_VERSION) {
                throw new IllegalStateException("Unsupported workspace manifest version: " + manifest.getVersion());
            }
            return new ArrayList<>(manifest.getEntries());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read workspace manifest", exception);
        }
    }

    private void persistManifest() {
        WorkspaceManifest manifest = new WorkspaceManifest();
        manifest.setVersion(MANIFEST_VERSION);
        manifest.setEntries(dynamicRecords);
        Path temporary = manifestFile.resolveSibling(manifestFile.getFileName() + ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                objectMapper.writeValue(output, manifest);
            }
            moveReplacing(temporary, manifestFile);
        } catch (IOException exception) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("Unable to persist workspace manifest", exception);
        }
    }

    private void writeMarker(Path directory, DynamicWorkspaceRecord record) throws IOException {
        Map<String, String> marker = new LinkedHashMap<>();
        marker.put("requestId", record.getRequestId());
        marker.put("workspaceName", record.getWorkspaceName());
        marker.put("parentName", record.getParentName());
        try (OutputStream output = Files.newOutputStream(directory.resolve(MARKER_FILE_NAME))) {
            objectMapper.writeValue(output, marker);
        }
    }

    private boolean markerMatches(Path directory, String requestId) throws IOException {
        Path marker = directory.resolve(MARKER_FILE_NAME);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        JsonNode value = objectMapper.readTree(marker.toFile());
        return value.has("requestId") && requestId.equals(value.get("requestId").asText());
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target); }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private DynamicWorkspaceRecord findByRequestId(String requestId) {
        for (DynamicWorkspaceRecord record : dynamicRecords) {
            if (requestId.equals(record.getRequestId())) return record;
        }
        return null;
    }

    private boolean sameRequest(DynamicWorkspaceRecord record, String parentName,
                                String workspaceName, String projectType) {
        return normalizeName(parentName).equals(normalizeName(record.getParentName()))
                && normalizeName(workspaceName).equals(normalizeName(record.getWorkspaceName()))
                && projectType.equals(record.getProjectType());
    }

    private RegisteredRoot findRoot(String parentName) {
        RegisteredRoot root = roots.get(normalizeName(parentName));
        if (root == null) throw failure("UNKNOWN_PARENT", "Unknown workspace parent: " + parentName);
        return root;
    }

    private Path target(DynamicWorkspaceRecord record) {
        return target(findRoot(record.getParentName()), record.getWorkspaceName());
    }

    private Path target(RegisteredRoot parent, String workspaceName) {
        Path target = parent.root.resolve(workspaceName).normalize();
        if (!parent.root.equals(target.getParent())) {
            throw failure("WORKSPACE_PATH_ESCAPE", "Workspace path escapes the configured parent");
        }
        return target;
    }

    private void rejectWorkspaceConflict(String workspaceName, Path target, String ignoredRequestId) {
        for (DynamicWorkspaceRecord record : dynamicRecords) {
            if ((ignoredRequestId == null || !ignoredRequestId.equals(record.getRequestId()))
                    && normalizeName(workspaceName).equals(normalizeName(record.getWorkspaceName()))) {
                throw failure("WORKSPACE_ALREADY_EXISTS", "Workspace name already belongs to another request: " + workspaceName);
            }
        }
        Snapshot current = snapshot.get();
        if (current != null && current.workspaces.containsKey(normalizeName(workspaceName))) {
            throw failure("WORKSPACE_ALREADY_EXISTS", "Workspace name already exists: " + workspaceName);
        }
        if (current != null) {
            for (RegisteredWorkspace workspace : current.workspaces.values()) {
                if (overlaps(target, workspace.root)) {
                    throw failure("WORKSPACE_OVERLAP", "Workspace path overlaps an existing workspace");
                }
            }
        }
    }

    private RegisteredWorkspace findWorkspace(String workspaceName) {
        if (workspaceName == null || workspaceName.trim().isEmpty()) {
            throw new WorkspaceAccessException("Workspace name must not be blank");
        }
        RegisteredWorkspace workspace = snapshot.get().workspaces.get(normalizeName(workspaceName.trim()));
        if (workspace == null) throw new WorkspaceAccessException("Unknown workspace: " + workspaceName);
        return workspace;
    }

    private Path parseRelativePath(String relativePath) {
        if (relativePath == null) throw new WorkspaceAccessException("Workspace-relative path must not be null");
        final Path requestedPath;
        try { requestedPath = relativePath.trim().isEmpty() ? Paths.get("") : Paths.get(relativePath); }
        catch (RuntimeException exception) {
            throw new WorkspaceAccessException("Invalid workspace-relative path: " + relativePath, exception);
        }
        if (requestedPath.isAbsolute()) {
            throw new WorkspaceAccessException("Absolute paths are not accepted: " + relativePath);
        }
        return requestedPath;
    }

    private Path resolveThroughExistingAncestor(Path root, Path lexicalTarget) {
        Path existingAncestor = lexicalTarget;
        while (existingAncestor != null && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            throw new WorkspaceAccessException("No existing ancestor for path: " + lexicalTarget);
        }
        try {
            Path realAncestor = existingAncestor.toRealPath();
            Path unresolvedSuffix = existingAncestor.relativize(lexicalTarget);
            return realAncestor.resolve(unresolvedSuffix).normalize();
        } catch (IOException exception) {
            throw new WorkspaceAccessException("Unable to resolve workspace path: " + lexicalTarget, exception);
        }
    }

    private Path prepareDataDirectory(Path configuredPath) {
        if (configuredPath == null) throw new IllegalStateException("Agent data directory must be configured");
        try {
            Files.createDirectories(configuredPath.toAbsolutePath().normalize());
            return configuredPath.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to prepare agent data directory: " + configuredPath, exception);
        }
    }

    private Path requireDirectory(String name, Path configuredPath, String type) {
        if (configuredPath == null || !configuredPath.isAbsolute()) {
            throw new IllegalStateException(type + " path must be absolute: " + name);
        }
        try {
            Path root = configuredPath.toRealPath();
            if (!Files.isDirectory(root)) throw new IllegalStateException(type + " path is not a directory: " + name);
            return root;
        } catch (IOException exception) {
            throw new IllegalStateException(type + " path does not exist or cannot be read: " + name, exception);
        }
    }

    private String requireName(String value, String message) {
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(message);
        return value.trim();
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.length() > 128) {
            throw failure("INVALID_REQUEST_ID", "Workspace request id must contain between 1 and 128 characters");
        }
    }

    private void requireCreatableWorkspaceName(String name) {
        if (name == null || !SAFE_WORKSPACE_NAME.matcher(name).matches()
                || ".".equals(name) || "..".equals(name)
                || name.endsWith(".") || name.endsWith(" ")
                || WINDOWS_RESERVED_NAME.matcher(name).matches()) {
            throw failure("INVALID_WORKSPACE_NAME", "Workspace name contains unsupported characters");
        }
    }

    private void rejectDataDirectoryOverlap(String name, Path root) {
        if (overlaps(root, dataDirectory)) {
            throw new IllegalStateException("Workspace must not overlap the agent data directory: " + name);
        }
    }

    private void rejectOverlap(Map<String, RegisteredWorkspace> workspaces, RegisteredWorkspace candidate) {
        if (workspaces.containsKey(normalizeName(candidate.displayName))) {
            throw new IllegalStateException("Duplicate workspace name: " + candidate.displayName);
        }
        for (RegisteredWorkspace existing : workspaces.values()) {
            if (overlaps(existing.root, candidate.root)) {
                throw new IllegalStateException("Workspace paths must not overlap: " + candidate.displayName);
            }
        }
    }

    private void addWorkspace(Map<String, RegisteredWorkspace> workspaces, RegisteredWorkspace workspace) {
        workspaces.put(normalizeName(workspace.displayName), workspace);
    }

    private boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    private WorkspaceCreationFailure failure(String code, String message) {
        return new WorkspaceCreationFailure(code, message);
    }

    private static final class Snapshot {
        private final Map<String, RegisteredWorkspace> workspaces;
        private final List<WorkspaceVO> views;
        private Snapshot(Map<String, RegisteredWorkspace> workspaces, List<WorkspaceVO> views) {
            this.workspaces = workspaces;
            this.views = views;
        }
    }

    private static final class RegisteredRoot {
        private final String displayName;
        private final Path root;
        private RegisteredRoot(String displayName, Path root) { this.displayName = displayName; this.root = root; }
    }

    private static final class RegisteredWorkspace {
        private final String displayName;
        private final Path root;
        private RegisteredWorkspace(String displayName, Path root) { this.displayName = displayName; this.root = root; }
    }

    private static final class WorkspaceCreationFailure extends RuntimeException {
        private final String errorCode;
        private WorkspaceCreationFailure(String errorCode, String message) { super(message); this.errorCode = errorCode; }
    }
}
