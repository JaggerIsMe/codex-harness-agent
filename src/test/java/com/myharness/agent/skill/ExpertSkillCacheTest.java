package com.myharness.agent.skill;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ExpertSkillCacheTest {
    @TempDir Path root;
    @Test void cachesByVersionAndWorkspaceWithoutPublicInstallation() throws Exception {
        Path zip=archive("SKILL.md");var count=new AtomicInteger();var cache=cache(zip,count);
        var request=request(zip,"7-10","a");Path first=cache.prepare(request,null);
        assertEquals(first,cache.prepare(request,null));assertEquals(1,count.get());
        Path next=cache.prepare(request(zip,"7-11","a"),null);
        Path other=cache.prepare(request(zip,"7-10","b"),null);
        assertNotEquals(first,next);assertNotEquals(first,other);assertEquals(3,count.get());
        assertTrue(Files.isRegularFile(first.resolve("SKILL.md")));
        assertFalse(Files.exists(root.resolve("a/.agents/skills")));
    }
    @Test void acceptsSingleTopLevelDirectory() throws Exception {
        Path zip=archive("demo/SKILL.md");Path result=cache(zip,new AtomicInteger()).prepare(request(zip,"7-10","a"),null);
        assertTrue(Files.isRegularFile(result.resolve("SKILL.md")));
    }
    @Test void rejectsTraversalAndReclaimsTemporaryFiles() throws Exception {
        Path zip=archive("../outside.txt");
        assertThrows(SkillException.class,()->cache(zip,new AtomicInteger()).prepare(request(zip,"7-10","a"),null));
        assertFalse(Files.exists(root.resolve("outside.txt")));
        try(var files=Files.list(root.resolve("data/tmp"))){assertEquals(0,files.count());}
    }
    @Test void rejectsChecksumMismatch() throws Exception {
        Path zip=archive("SKILL.md");var request=new ExpertSkillCacheRequest("7-10","1","https://fixture/skill.zip","0".repeat(64),"a");
        assertThrows(SkillException.class,()->cache(zip,new AtomicInteger()).prepare(request,null));
        assertFalse(Files.exists(root.resolve("a/.harness/expert-skills/harness-7-10")));
    }
    @Test void requiresAuthorizedWorkspace() throws Exception {
        Path zip=archive("SKILL.md");var properties=new AgentProperties();properties.setDataDir(root.resolve("data"));
        var registry=mock(WorkspaceRegistry.class);when(registry.resolve(anyString(),anyString())).thenThrow(new IllegalArgumentException("not authorized"));
        var cache=new ExpertSkillCache(properties,mock(SkillDownloadClient.class),registry);
        assertThrows(SkillException.class,()->cache.prepare(request(zip,"7-10","unknown"),null));
    }
    private ExpertSkillCache cache(Path zip,AtomicInteger count) {
        var properties=new AgentProperties();properties.setDataDir(root.resolve("data"));
        var registry=mock(WorkspaceRegistry.class);
        when(registry.resolve(anyString(),eq(".harness/expert-skills"))).thenAnswer(i->root.resolve(i.getArgument(0,String.class)).resolve(".harness/expert-skills"));
        return new ExpertSkillCache(properties,(url,target,max)->{try{Files.copy(zip,target);count.incrementAndGet();}catch(Exception e){throw new SkillException("fixture",e);}},registry);
    }
    private ExpertSkillCacheRequest request(Path zip,String id,String workspace) throws Exception {
        return new ExpertSkillCacheRequest(id,"1","https://fixture/skill.zip",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip))),workspace);
    }
    private Path archive(String entry) throws Exception {
        Path path=Files.createTempFile(root,"fixture-",".zip");try(var zip=new ZipOutputStream(Files.newOutputStream(path))){zip.putNextEntry(new ZipEntry(entry));zip.write("# Skill".getBytes());zip.closeEntry();}return path;
    }
}
