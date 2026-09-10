package com.myharness.agent.workspace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowsWorkspaceHandlesTest {
    @TempDir Path root;
    @BeforeEach void windows(){Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());}
    @Test void targetCreatedAfterSourceHandleWasOpenedIsNeverOverwritten() throws Exception {
        Path source=Files.writeString(root.resolve("a.txt"),"source"),target=root.resolve("b.txt");
        try(var pins=WindowsWorkspaceHandles.pin(root,root);var handle=WindowsWorkspaceHandles.entry(source,true)) {
            Files.writeString(target,"concurrent destination");
            var error=assertThrows(WorkspaceFileException.class,()->handle.relocate(target));assertEquals("TARGET_EXISTS",error.code);
        }
        assertEquals("source",Files.readString(source));assertEquals("concurrent destination",Files.readString(target));
    }
    @Test void pinnedAncestorCannotBeRenamedOrReplacedDuringTheOperation() throws Exception {
        Path parent=Files.createDirectory(root.resolve("docs"));Files.writeString(parent.resolve("a.txt"),"a");
        try(var pins=WindowsWorkspaceHandles.pin(root,parent)) {
            assertThrows(IOException.class,()->Files.move(parent,root.resolve("elsewhere")));
            assertTrue(Files.exists(parent.resolve("a.txt")));
        }
        Files.move(parent,root.resolve("elsewhere"));assertTrue(Files.exists(root.resolve("elsewhere/a.txt")));
    }
    @Test void openEntryCannotBeReplacedBeforeHandleBasedDeletion() throws Exception {
        Path source=Files.writeString(root.resolve("a.txt"),"original");String identity=WindowsWorkspaceHandles.identity(source);
        try(var handle=WindowsWorkspaceHandles.entry(source,true)) {
            assertThrows(IOException.class,()->Files.delete(source));
            assertThrows(IOException.class,()->Files.move(source,root.resolve("other.txt")));
            assertEquals(identity,WindowsWorkspaceHandles.identity(source));handle.delete();
        }
        assertFalse(Files.exists(source));
    }
}
