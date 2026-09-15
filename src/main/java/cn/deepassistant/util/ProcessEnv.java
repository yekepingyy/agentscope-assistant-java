package cn.deepassistant.util;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 把键写入 {@link System#getenv()}。Harness {@code tools.json} 的 {@code ${ENV}} 只认进程环境变量，
 * 不认 Spring {@code application.yml}。
 *
 * <p>JDK 17 默认不允许打开 {@code java.lang.ProcessEnvironment}，写入会失败；调用方必须再走
 * 本地 {@code tools.json} 替换（与 {@code ToolsConfigLoader.substituteEnv} 等价）。
 */
@Slf4j
public final class ProcessEnv {

    private ProcessEnv() {
    }

    /**
     * 环境里已有非空值则保持不动。否则尝试写入 {@code value}。
     *
     * @return 调用结束后 {@code System.getenv(key)} 非空
     */
    public static boolean ensure(String key, String value) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String existing = System.getenv(key);
        if (existing != null && !existing.isBlank()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            put(key, value);
        } catch (Exception e) {
            log.warn("无法写入 System.getenv({})：{}。请在 IDE 环境变量或 shell 里 export，或依赖 yml 回退替换。",
                    key, e.toString());
            return false;
        }
        String now = System.getenv(key);
        return now != null && !now.isBlank();
    }

    private static void put(String key, String value) throws Exception {
        Exception last = null;
        try {
            mutateProcessEnvironment(key, value);
            if (notBlank(System.getenv(key))) {
                return;
            }
        } catch (Exception e) {
            last = e;
        }
        try {
            mutateGetenvView(key, value);
            if (notBlank(System.getenv(key))) {
                return;
            }
        } catch (Exception e) {
            last = e;
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("写入后 System.getenv(" + key + ") 仍为空");
    }

    @SuppressWarnings("unchecked")
    private static void mutateProcessEnvironment(String key, String value) throws Exception {
        Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
        try {
            Field env = pe.getDeclaredField("theEnvironment");
            env.setAccessible(true);
            ((Map<String, String>) env.get(null)).put(key, value);
        } catch (NoSuchFieldException ignored) {
            // Windows 只有 case-insensitive 那张表
        }
        try {
            Field ci = pe.getDeclaredField("theCaseInsensitiveEnvironment");
            ci.setAccessible(true);
            ((Map<String, String>) ci.get(null)).put(key, value);
        } catch (NoSuchFieldException ignored) {
            // Unix
        }
        try {
            Field unmod = pe.getDeclaredField("theUnmodifiableEnvironment");
            unmod.setAccessible(true);
            Map<String, String> frozen = (Map<String, String>) unmod.get(null);
            putInner(frozen, key, value);
        } catch (NoSuchFieldException ignored) {
            // 部分 JDK 没有这张表
        }
    }

    @SuppressWarnings("unchecked")
    private static void mutateGetenvView(String key, String value) throws Exception {
        putInner(System.getenv(), key, value);
    }

    @SuppressWarnings("unchecked")
    private static void putInner(Map<String, String> frozen, String key, String value) throws Exception {
        Field inner = frozen.getClass().getDeclaredField("m");
        inner.setAccessible(true);
        ((Map<String, String>) inner.get(frozen)).put(key, value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
