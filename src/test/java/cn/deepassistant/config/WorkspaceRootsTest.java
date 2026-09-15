package cn.deepassistant.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceRootsTest {

    @TempDir
    Path temp;

    @Test
    void keepsConfiguredPathWhenItAlreadyHasSeeds() throws Exception {
        Path ws = temp.resolve("workspace");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("tools.json"), "{}");
        Path resolved = WorkspaceRoots.resolve(ws.toString(), WorkspaceRoots.class);
        assertEquals(ws.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void fallsBackToModuleWorkspaceWhenConfiguredPathHasNoSeeds() {
        Path bogus = temp.resolve("agentscope").resolve("workspace");
        Path resolved = WorkspaceRoots.resolve(bogus.toString(), WorkspaceRoots.class);
        assertTrue(Files.isRegularFile(resolved.resolve("tools.json")), resolved.toString());
        assertTrue(Files.isRegularFile(resolved.resolve("AGENTS.md")), resolved.toString());
    }
}
