package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListFilesToolTest {
    @TempDir
    Path root;

    /** 验证排序、文件类型标注和非递归行为。 */
    @Test
    void listsDirectChildrenInNameOrder() throws Exception {
        Files.createFile(root.resolve("b.txt"));
        Files.createDirectories(root.resolve("a-dir"));
        Files.createFile(root.resolve("a-dir/hidden.txt"));
        assertEquals("[目录] a-dir\n[文件] b.txt", new ListFilesTool(root.toString()).listFiles("."));
    }

    /** 验证空目录提示及对越界、绝对路径和普通文件路径的拒绝。 */
    @Test
    void handlesEmptyDirectoryAndRejectsInvalidTargets() throws Exception {
        ListFilesTool tool = new ListFilesTool(root.toString());
        assertEquals("目录为空", tool.listFiles("."));
        assertThrows(IllegalArgumentException.class, () -> tool.listFiles(".."));
        assertThrows(IllegalArgumentException.class, () -> tool.listFiles(root.toAbsolutePath().toString()));
        Files.createFile(root.resolve("file.txt"));
        assertThrows(IllegalArgumentException.class, () -> tool.listFiles("file.txt"));
    }

    /** 验证工具声明了 path 字符串参数，并可通过 Registry 和 Executor 执行。 */
    @Test
    void executesThroughRegistryWithPathArgument() throws Exception {
        Files.createFile(root.resolve("data.csv"));
        ListFilesTool tool = new ListFilesTool(root.toString());
        ToolDefinition definition = tool.definition();
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        Map<?, ?> pathSchema = (Map<?, ?>) properties.get("path");
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ToolExecutor executor = new ToolExecutor(registry, new ObjectMapper());

        ToolResult result = executor.execute("call_1", "list_files", "{\"path\":\".\"}");

        assertEquals("list_files", definition.name());
        assertEquals(List.of("path"), definition.parameters().get("required"));
        assertEquals("string", pathSchema.get("type"));
        assertTrue(result.success());
        assertEquals("[文件] data.csv", result.content());
    }

    /** 验证缺失、类型错误及越界路径均返回明确的参数错误。 */
    @Test
    void rejectsInvalidInvocationArguments() {
        ToolRegistry registry = new ToolRegistry(List.of(new ListFilesTool(root.toString())));
        ToolExecutor executor = new ToolExecutor(registry, new ObjectMapper());

        ToolResult missing = executor.execute("call_1", "list_files", "{}");
        ToolResult wrongType = executor.execute("call_2", "list_files", "{\"path\":1}");
        ToolResult outsideWorkspace = executor.execute("call_3", "list_files", "{\"path\":\"..\"}");

        assertFalse(missing.success());
        assertEquals("INVALID_ARGUMENTS", missing.errorCode());
        assertEquals("INVALID_ARGUMENTS", wrongType.errorCode());
        assertEquals("INVALID_ARGUMENTS", outsideWorkspace.errorCode());
    }
}
