package cn.deepassistant.integration.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP Java SDK 0.17+ {@link McpSchema} 适配：从工具 inputSchema 选参数名，并把
 * {@link McpSchema.CallToolResult}（content + Object structuredContent）收成文本。
 */
final class McpToolSupport {

    private McpToolSupport() {
    }

    static String toolName(McpSchema.Tool tool) {
        return tool == null ? "" : nullToEmpty(tool.name());
    }

    static boolean matchesTool(McpSchema.Tool tool, String... tokens) {
        if (tool == null) {
            return false;
        }
        String haystack = (nullToEmpty(tool.name()) + " " + nullToEmpty(tool.title())
                + " " + nullToEmpty(tool.description())).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token != null && haystack.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 0.17 {@link McpSchema.JsonSchema} 的 required / properties 选调用参数名。
     * 先用 schema 里真实存在的首选名，没有再退回 required，再退回 properties。
     */
    static List<String> argumentKeys(McpSchema.JsonSchema schema, List<String> preferred) {
        List<String> properties = schema == null || schema.properties() == null
                ? List.of()
                : List.copyOf(schema.properties().keySet());
        List<String> required = schema == null || schema.required() == null
                ? List.of()
                : List.copyOf(schema.required());
        Set<String> known = new LinkedHashSet<>();
        known.addAll(required);
        known.addAll(properties);
        if (preferred != null) {
            List<String> matched = new ArrayList<>();
            for (String want : preferred) {
                String actual = findIgnoreCase(known, want);
                if (actual != null && !matched.contains(actual)) {
                    matched.add(actual);
                }
            }
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        if (!required.isEmpty()) {
            return required;
        }
        return new ArrayList<>(properties);
    }

    static String stringify(McpSchema.CallToolResult result) {
        if (result == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text
                        && text.text() != null
                        && !text.text().isBlank()) {
                    parts.add(text.text());
                }
            }
        }
        if (parts.isEmpty() && result.structuredContent() != null) {
            parts.add(stringifyStructured(result.structuredContent()));
        }
        return String.join("\n", parts);
    }

    private static String stringifyStructured(Object structured) {
        if (structured instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("\n"));
        }
        if (structured instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining("\n"));
        }
        return String.valueOf(structured);
    }

    private static String findIgnoreCase(Set<String> known, String want) {
        if (want == null) {
            return null;
        }
        for (String key : known) {
            if (key != null && key.equalsIgnoreCase(want)) {
                return key;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
