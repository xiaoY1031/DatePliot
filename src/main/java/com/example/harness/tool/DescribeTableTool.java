package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 读取当前配置数据库中指定普通表的结构，不执行用户提供的 SQL。 */
@Component
public class DescribeTableTool implements AgentTool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "describe_table",
            "查询当前配置的 MySQL 数据库中指定普通表的字段、主键和注释信息",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "table_name", Map.of(
                                    "type", "string",
                                    "description", "需要查询结构的当前数据库普通表名称")),
                    "required", List.of("table_name"),
                    "additionalProperties", false));
    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}_$-]+");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public DescribeTableTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String tableName = invocation.arguments().path("table_name").asText(null);
        if (!isValidTableName(tableName)) {
            return ToolResult.failure("INVALID_TABLE_NAME",
                    "table_name 必须是 1 到 64 个字符的合法表名，只能包含字母、数字、下划线、美元符号或连字符。");
        }

        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException | RuntimeException e) {
            return ToolResult.failure("DATABASE_CONNECTION_ERROR",
                    "无法连接配置的数据源，请检查数据库服务、网络和本地数据源配置。");
        }

        try (connection) {
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank()) {
                return ToolResult.failure("DATABASE_NOT_SELECTED",
                        "未选择数据库，请配置 DATAPILOT_DB_NAME 后重启应用。");
            }
            if (SYSTEM_DATABASES.contains(catalog.toLowerCase(Locale.ROOT))) {
                return ToolResult.failure("SYSTEM_DATABASE_NOT_ALLOWED",
                        "不允许查询 MySQL 系统数据库，请将 DATAPILOT_DB_NAME 配置为业务数据库。");
            }

            DatabaseMetaData metadata = connection.getMetaData();
            TableMetadata table;
            try {
                table = findTable(metadata, catalog, tableName);
            } catch (SQLException | RuntimeException e) {
                return ToolResult.failure("DATABASE_METADATA_ERROR",
                        "确认目标表信息失败，请检查数据库连接和账号的元信息访问权限。");
            }
            if (table == null) {
                return ToolResult.failure("TABLE_NOT_FOUND",
                        "当前数据库中不存在指定表：" + tableName);
            }
            if (!"TABLE".equals(table.type())) {
                return ToolResult.failure("TABLE_TYPE_NOT_ALLOWED",
                        "只能查询当前数据库中的普通表，目标对象不是普通表：" + tableName);
            }

            Set<String> primaryKeys;
            try {
                primaryKeys = readPrimaryKeys(metadata, catalog, table.name());
            } catch (SQLException | RuntimeException e) {
                return ToolResult.failure("PRIMARY_KEY_METADATA_ERROR",
                        "读取目标表的主键信息失败，请检查账号的元信息访问权限。");
            }

            List<ColumnMetadata> columns;
            try {
                columns = readColumns(metadata, catalog, table.name());
            } catch (SQLException | RuntimeException e) {
                return ToolResult.failure("COLUMN_METADATA_ERROR",
                        "读取目标表的字段信息失败，请检查账号的元信息访问权限。");
            }
            if (columns.isEmpty()) {
                return ToolResult.failure("TABLE_COLUMNS_EMPTY",
                        "目标表没有可用的字段元信息：" + tableName);
            }

            return ToolResult.success(toJson(table, columns, primaryKeys).toString());
        } catch (SQLException | RuntimeException e) {
            // 不返回 JDBC 异常正文，避免泄露连接地址、账号或密码。
            return ToolResult.failure("DATABASE_METADATA_ERROR",
                    "读取目标表结构失败，请检查数据库连接和元信息访问权限。");
        }
    }

    private boolean isValidTableName(String tableName) {
        return tableName != null
                && !tableName.isBlank()
                && tableName.equals(tableName.trim())
                && tableName.codePointCount(0, tableName.length()) <= 64
                && TABLE_NAME_PATTERN.matcher(tableName).matches();
    }

    private TableMetadata findTable(DatabaseMetaData metadata, String catalog, String tableName)
            throws SQLException {
        String tablePattern = escapePattern(metadata, tableName);
        TableMetadata disallowedTable = null;
        try (ResultSet tables = metadata.getTables(catalog, null, tablePattern, null)) {
            while (tables.next()) {
                if (!catalog.equals(tables.getString("TABLE_CAT"))
                        || !tableName.equals(tables.getString("TABLE_NAME"))) {
                    continue;
                }
                TableMetadata candidate = new TableMetadata(
                        tables.getString("TABLE_NAME"),
                        tables.getString("TABLE_TYPE"),
                        tables.getString("REMARKS"));
                if ("TABLE".equals(candidate.type())) {
                    return candidate;
                }
                disallowedTable = candidate;
            }
        }
        return disallowedTable;
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData metadata, String catalog, String tableName)
            throws SQLException {
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet keys = metadata.getPrimaryKeys(catalog, null, tableName)) {
            while (keys.next()) {
                if (!catalog.equals(keys.getString("TABLE_CAT"))
                        || !tableName.equals(keys.getString("TABLE_NAME"))) {
                    continue;
                }
                String columnName = keys.getString("COLUMN_NAME");
                if (columnName == null || columnName.isBlank()) {
                    throw new SQLException("主键元信息缺少字段名称");
                }
                primaryKeys.add(columnName);
            }
        }
        return primaryKeys;
    }

    private List<ColumnMetadata> readColumns(DatabaseMetaData metadata, String catalog, String tableName)
            throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        String tablePattern = escapePattern(metadata, tableName);
        try (ResultSet result = metadata.getColumns(catalog, null, tablePattern, "%")) {
            while (result.next()) {
                if (!catalog.equals(result.getString("TABLE_CAT"))
                        || !tableName.equals(result.getString("TABLE_NAME"))) {
                    continue;
                }
                String columnName = requiredText(result, "COLUMN_NAME");
                String typeName = requiredText(result, "TYPE_NAME");
                Integer ordinalPosition = optionalInteger(result.getObject("ORDINAL_POSITION"));
                if (ordinalPosition == null || ordinalPosition <= 0) {
                    throw new SQLException("字段元信息缺少有效顺序");
                }
                Integer nullableCode = optionalInteger(result.getObject("NULLABLE"));
                columns.add(new ColumnMetadata(
                        columnName,
                        typeName,
                        optionalInteger(result.getObject("COLUMN_SIZE")),
                        optionalInteger(result.getObject("DECIMAL_DIGITS")),
                        toNullable(nullableCode),
                        result.getString("COLUMN_DEF"),
                        ordinalPosition));
            }
        }
        columns.sort(Comparator.comparingInt(ColumnMetadata::ordinalPosition));
        return List.copyOf(columns);
    }

    private String escapePattern(DatabaseMetaData metadata, String value) throws SQLException {
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        return value.replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }

    private String requiredText(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        if (value == null || value.isBlank()) {
            throw new SQLException("字段元信息缺少必要值");
        }
        return value;
    }

    private Integer optionalInteger(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new SQLException("字段元信息中的数字格式无效", e);
        }
    }

    private Boolean toNullable(Integer nullableCode) {
        if (nullableCode == null || nullableCode == DatabaseMetaData.columnNullableUnknown) {
            return null;
        }
        return nullableCode == DatabaseMetaData.columnNullable;
    }

    private ObjectNode toJson(
            TableMetadata table,
            List<ColumnMetadata> columns,
            Set<String> primaryKeys) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("table_name", table.name());
        putNullable(content, "table_comment", table.comment());
        var columnArray = content.putArray("columns");
        for (ColumnMetadata column : columns) {
            ObjectNode item = columnArray.addObject();
            item.put("name", column.name());
            item.put("type", column.type());
            putNullable(item, "size", column.size());
            putNullable(item, "decimal_digits", column.decimalDigits());
            putNullable(item, "nullable", column.nullable());
            item.put("primary_key", primaryKeys.contains(column.name()));
            putNullable(item, "default_value", column.defaultValue());
            item.put("ordinal_position", column.ordinalPosition());
        }
        return content;
    }

    private void putNullable(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private void putNullable(ObjectNode target, String field, Integer value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private void putNullable(ObjectNode target, String field, Boolean value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private record TableMetadata(String name, String type, String comment) {
    }

    private record ColumnMetadata(
            String name,
            String type,
            Integer size,
            Integer decimalDigits,
            Boolean nullable,
            String defaultValue,
            int ordinalPosition) {
    }
}
