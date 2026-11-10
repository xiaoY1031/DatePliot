package com.example.harness.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ListFilesTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "list_files",
            "列出工作区内指定相对目录的直接子项，不递归读取文件内容",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of(
                                    "type", "string",
                                    "description", "相对于工作区根目录的目录路径，例如 . 或 src/main")),
                    "required", List.of("path"),
                    "additionalProperties", false));

    private final String workspace;

    /** 由 Spring 注入允许访问的项目根目录，便于统一限制工具访问范围。 */
    public ListFilesTool(@Value("${harness.workspace}") String workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String relativePath = invocation.arguments().path("path").asText();
        try {
            return ToolResult.success(listFiles(relativePath));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("INVALID_ARGUMENTS", e.getMessage());
        } catch (IOException e) {
            return ToolResult.failure("EXECUTION_ERROR", "读取目录失败：" + e.getMessage());
        }
    }

    /**
     * 接收根目录内的相对路径，按名称排序返回直接子项，不递归读取文件内容。
     * 用 "." 查看根目录；越界或非目录路径抛出异常，读取失败抛出 IOException。
     */
    public String listFiles(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("请提供相对目录路径，例如 .");
        }

        // 1. 获取允许访问的项目根目录，并解析根目录自身的链接。
        Path root = Path.of(workspace).toAbsolutePath().normalize().toRealPath();

        // 2. 将传入的相对路径转换成实际路径。
        Path requested = Path.of(relativePath);
        if (requested.isAbsolute() || requested.getRoot() != null) {
            throw new IllegalArgumentException("请使用项目根目录内的相对路径");
        }
        Path target = root.resolve(requested).normalize();

        // 3. 防止访问项目目录之外的文件，既检查 ..，也检查符号链接或目录联接。
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("不能访问项目目录之外的路径");
        }
        target = target.toRealPath();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("不能访问项目目录之外的路径");
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("指定路径不是目录");
        }

        // 4. 获取当前目录下的文件和文件夹名称。
        try (var paths = Files.list(target)) {
            String result = paths
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> {
                        String type = Files.isDirectory(path) ? "[目录] " : "[文件] ";
                        return type + path.getFileName();
                    })
                    .collect(Collectors.joining("\n"));
            return result.isEmpty() ? "目录为空" : result;
        }
    }
}
