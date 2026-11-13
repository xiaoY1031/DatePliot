package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 只读取配置数据源当前数据库的普通表名，不接受 SQL 或连接参数。 */
@Component
public class ListTablesTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "list_tables",
            "列出当前配置的 MySQL 数据库中的普通表，排除视图和系统表，按表名排序；无需参数",
            Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of(),
                    "additionalProperties", false));
    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public ListTablesTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException | RuntimeException e) {
            return ToolResult.failure("DATABASE_CONNECTION_ERROR",
                    "无法连接配置的数据源，请检查数据库服务、网络和本地数据源配置。");
        }

        try (connection) {
            String catalog = connection.getCatalog();
            // 空 catalog 会放宽元信息查询范围，必须在查询前拒绝。
            if (catalog == null || catalog.isBlank()) {
                return ToolResult.failure("DATABASE_NOT_SELECTED",
                        "未选择数据库，请配置 DATAPILOT_DB_NAME 后重启应用。");
            }
            if (SYSTEM_DATABASES.contains(catalog.toLowerCase(Locale.ROOT))) {
                return ToolResult.failure("SYSTEM_DATABASE_NOT_ALLOWED",
                        "不允许查询 MySQL 系统数据库，请将 DATAPILOT_DB_NAME 配置为业务数据库。");
            }

            DatabaseMetaData metadata = connection.getMetaData();
            Set<String> tableNames = new TreeSet<>();
            try (ResultSet tables = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    if (!catalog.equals(tables.getString("TABLE_CAT"))
                            || !"TABLE".equals(tables.getString("TABLE_TYPE"))) {
                        continue;
                    }
                    String name = tables.getString("TABLE_NAME");
                    if (name != null && !name.isBlank()) {
                        tableNames.add(name);
                    }
                }
            }

            var content = objectMapper.createObjectNode();
            var tables = content.putArray("tables");
            tableNames.forEach(tables::add);
            return ToolResult.success(content.toString());
        } catch (SQLException | RuntimeException e) {
            // 不将驱动异常正文回传模型，避免泄露账号、连接地址等配置。
            return ToolResult.failure("DATABASE_METADATA_ERROR",
                    "读取当前数据库的表信息失败，请检查数据库连接和账号的元信息访问权限。");
        }
    }
}
