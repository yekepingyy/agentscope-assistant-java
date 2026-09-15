package cn.deepassistant.config;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

/**
 * 解析 Harness 种子工作区。yml 默认是 {@code ${user.dir}/workspace}，IntelliJ 若把
 * Working directory 设成别的工程（例如 {@code IdeaProjects/agentscope}），那个目录没有
 * {@code tools.json} / {@code AGENTS.md}。此时用本类 Class 的 {@code target/classes}
 * 反推模块根下的 {@code workspace/}。
 */
public final class WorkspaceRoots {

    private WorkspaceRoots() {
    }

    public static boolean hasSeeds(Path workspace) {
        if (workspace == null) {
            return false;
        }
        return Files.isRegularFile(workspace.resolve("AGENTS.md"))
                || Files.isRegularFile(workspace.resolve("tools.json"));
    }

    public static Path resolve(String configured, Class<?> anchor) {
        Path configuredPath = Path.of(
                        configured == null || configured.isBlank() ? "workspace" : configured)
                .toAbsolutePath()
                .normalize();
        if (hasSeeds(configuredPath)) {
            return configuredPath;
        }
        Path fromCode = fromCodeSource(anchor);
        if (hasSeeds(fromCode)) {
            return fromCode;
        }
        return configuredPath;
    }

    static Path fromCodeSource(Class<?> anchor) {
        if (anchor == null) {
            return null;
        }
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            URL location = source.getLocation();
            if (location == null) {
                return null;
            }
            Path loc = Path.of(location.toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(loc)) {
                Path parent = loc.getParent();
                return parent == null ? null : parent.resolve("workspace");
            }
            Path target = loc.getParent();
            if (target != null && "target".equals(target.getFileName().toString())) {
                Path moduleRoot = target.getParent();
                if (moduleRoot != null) {
                    return moduleRoot.resolve("workspace");
                }
            }
            return loc.resolve("workspace");
        } catch (Exception ignored) {
            return null;
        }
    }
}
