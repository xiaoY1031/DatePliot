package com.example.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.StatementFeatureVisitor;
import net.sf.jsqlparser.statement.StatementFeatures;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.StmtFeature;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 执行经过 AST 安全校验的单条只读 SELECT 查询。 */
@Component
public class ExecuteReadonlySqlTool implements AgentTool {

    static final int MAX_ROWS = 100;
    static final int QUERY_TIMEOUT_SECONDS = 10;
    static final int MAX_TEXT_LENGTH = 2_000;
    static final int MAX_SQL_LENGTH = 20_000;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "execute_readonly_sql",
            "在当前配置的 MySQL 数据库中执行一条只读 SELECT 查询；最多返回 100 行，禁止跨库、写入、锁定和多语句",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "sql", Map.of(
                                    "type", "string",
                                    "description", "需要执行的单条只读 SELECT 或最终返回 SELECT 的 WITH 查询")),
                    "required", List.of("sql"),
                    "additionalProperties", false));
    private static final Set<String> SYSTEM_DATABASES = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");
    private static final Set<Integer> NUMERIC_TYPES = Set.of(
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
            Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL);
    private static final Set<Integer> BOOLEAN_TYPES = Set.of(Types.BOOLEAN, Types.BIT);
    private static final Set<Integer> BINARY_TYPES = Set.of(
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB);
    private static final Set<Integer> TEXT_TYPES = Set.of(
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
            Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
            Types.CLOB, Types.NCLOB, Types.SQLXML);
    private static final Set<String> BLOCKED_FUNCTIONS = Set.of(
            "benchmark", "get_lock", "is_free_lock", "is_used_lock",
            "load_file", "master_pos_wait", "release_all_locks",
            "release_lock", "sleep", "sys_exec", "sys_eval");
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "abs", "acos", "adddate", "ascii", "asin", "atan", "atan2",
            "avg", "bin", "bit_and", "bit_count", "bit_or", "bit_xor",
            "cast", "ceil", "ceiling", "char_length", "character_length",
            "coalesce", "concat", "concat_ws", "convert", "cos", "cot",
            "count", "crc32", "curdate", "current_date", "current_time",
            "current_timestamp", "curtime", "date", "date_add", "date_format",
            "date_sub", "datediff", "day", "dayname", "dayofmonth",
            "dayofweek", "dayofyear", "degrees", "dense_rank", "elt", "exp",
            "extract", "field", "find_in_set", "first_value", "floor", "format",
            "from_days", "from_unixtime", "greatest", "group_concat", "hex",
            "hour", "if", "ifnull", "instr", "json_array", "json_array_length",
            "json_contains", "json_contains_path", "json_depth", "json_extract",
            "json_keys", "json_length", "json_object", "json_pretty", "json_quote",
            "json_search", "json_type", "json_unquote", "lag", "last_day",
            "last_value", "lcase", "lead", "least", "left", "length", "ln",
            "locate", "log", "log10", "log2", "lower", "lpad", "ltrim",
            "makedate", "maketime", "max", "md5", "microsecond", "mid", "min",
            "minute", "mod", "month", "monthname", "now", "nth_value",
            "ntile", "nullif", "oct", "octet_length", "percent_rank", "pi",
            "position", "pow", "power", "quarter", "radians", "rand", "rank",
            "regexp_instr", "regexp_like", "regexp_replace", "regexp_substr",
            "repeat", "replace", "reverse", "right", "round", "row_number",
            "rpad", "rtrim", "second", "sha1", "sha2", "sign", "sin",
            "space", "sqrt", "std", "stddev", "stddev_pop", "stddev_samp",
            "str_to_date", "strcmp", "substr", "substring", "substring_index",
            "sum", "tan", "time", "time_format", "timediff", "timestamp",
            "timestampadd", "timestampdiff", "to_days", "to_seconds", "trim",
            "truncate", "ucase", "unhex", "unix_timestamp", "upper", "utc_date",
            "utc_time", "utc_timestamp", "variance", "var_pop", "var_samp",
            "week", "weekday", "weekofyear", "year", "yearweek");
    private static final Pattern FORBIDDEN_MYSQL_SYNTAX = Pattern.compile(
            "(?is)(?:\\binto\\s+(?:out|dump)file\\b"
                    + "|\\binto\\s+@"
                    + "|\\block\\s+in\\s+share\\s+mode\\b"
                    + "|\\bfor\\s+(?:update|share)\\b"
                    + "|:=|@)");
    private static final Pattern EXECUTABLE_COMMENT = Pattern.compile("(?s)/\\*[!+]");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public ExecuteReadonlySqlTool(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String sql = invocation.arguments().path("sql").asText(null);
        if (sql == null || sql.isBlank()) {
            return ToolResult.failure("INVALID_SQL", "sql 不能为空。");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            return ToolResult.failure("INVALID_SQL",
                    "SQL 长度不能超过 " + MAX_SQL_LENGTH + " 个字符。");
        }

        ValidatedQuery query;
        try {
            query = validateSql(sql);
        } catch (UnsafeSqlException e) {
            return ToolResult.failure("SQL_SAFETY_REJECTED", e.getMessage());
        } catch (JSQLParserException | RuntimeException e) {
            return ToolResult.failure("SQL_PARSE_ERROR",
                    "无法解析 SQL，只有能够明确验证为安全的单条 SELECT 查询才允许执行。");
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

            String tableValidationError = validateCatalogReferences(query.tableReferences(), catalog);
            if (tableValidationError != null) {
                return ToolResult.failure("CROSS_DATABASE_ACCESS_REJECTED", tableValidationError);
            }

            connection.setReadOnly(true);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setMaxRows(MAX_ROWS + 1);
                statement.setFetchSize(MAX_ROWS + 1);
                try (ResultSet resultSet = statement.executeQuery(query.sql())) {
                    return ToolResult.success(toJson(resultSet).toString());
                }
            }
        } catch (SQLException e) {
            if (isTimeout(e)) {
                return ToolResult.failure("QUERY_TIMEOUT",
                        "查询执行超过 " + QUERY_TIMEOUT_SECONDS + " 秒，已终止。");
            }
            if (e.getSQLState() != null && e.getSQLState().startsWith("42")) {
                return ToolResult.failure("SQL_SYNTAX_ERROR",
                        "SQL 语法、表名或字段名无效，请根据数据库结构修正查询。");
            }
            // 不将 JDBC 异常正文返回模型，避免泄露连接信息、账号或数据库内部路径。
            return ToolResult.failure("SQL_EXECUTION_ERROR",
                    "只读 SQL 执行失败，请检查 SQL 语法、字段名称和数据库访问权限。");
        } catch (RuntimeException e) {
            return ToolResult.failure("SQL_EXECUTION_ERROR",
                    "只读 SQL 执行失败，请检查 SQL 语法、字段名称和数据库访问权限。");
        }
    }

    private ValidatedQuery validateSql(String sql)
            throws JSQLParserException, UnsafeSqlException {
        if (EXECUTABLE_COMMENT.matcher(sql).find()) {
            throw new UnsafeSqlException("不允许使用 MySQL 可执行注释或优化器提示。");
        }
        if (FORBIDDEN_MYSQL_SYNTAX.matcher(maskQuotedContentAndComments(sql)).find()) {
            throw new UnsafeSqlException(
                    "不允许使用 SELECT INTO、OUTFILE、DUMPFILE、变量写入或锁定查询。");
        }
        Statements statements = CCJSqlParserUtil.parseStatements(sql);
        if (statements.size() != 1) {
            throw new UnsafeSqlException("只允许执行一条 SQL 查询。");
        }
        if (!(statements.get(0) instanceof Select select)) {
            throw new UnsafeSqlException("只允许执行 SELECT 查询。");
        }

        StatementFeatures features = StatementFeatureVisitor.analyse(
                select,
                functionName -> functionName != null
                        && ALLOWED_FUNCTIONS.contains(functionName.toLowerCase(Locale.ROOT)));
        if (!features.returnsResultSet()
                || features.modifiesData()
                || features.modifiesSchema()
                || features.may(StmtFeature.MODIFIES_SESSION)
                || features.may(StmtFeature.MODIFIES_TRANSACTION)
                || features.isOpaque()
                || !features.getUnresolvedReferences().isEmpty()) {
            throw new UnsafeSqlException("SQL 包含无法确认安全或可能修改数据、Schema、会话或事务的操作。");
        }

        validateSelectTree(select);
        String normalized = select.toString();
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String function : BLOCKED_FUNCTIONS) {
            if (lower.matches("(?s).*\\b" + function + "\\s*\\(.*")) {
                throw new UnsafeSqlException("不允许调用可能访问文件、加锁或长时间阻塞的函数：" + function);
            }
        }

        Set<String> tableReferences = new TablesNamesFinder<Void>()
                .getTables((net.sf.jsqlparser.statement.Statement) select);
        return new ValidatedQuery(normalized, Set.copyOf(tableReferences));
    }

    private boolean isTimeout(SQLException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        String state = exception.getSQLState();
        return "S1T00".equalsIgnoreCase(state)
                || "HYT00".equalsIgnoreCase(state)
                || "HYT01".equalsIgnoreCase(state)
                || exception.getErrorCode() == 3024
                || exception.getErrorCode() == 1317;
    }

    /** 将引号内容和注释替换为空格，使关键字扫描不受字符串字面量干扰。 */
    private String maskQuotedContentAndComments(String sql) {
        StringBuilder masked = new StringBuilder(sql.length());
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    masked.append(current);
                } else {
                    masked.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    masked.append("  " );
                    index++;
                    blockComment = false;
                } else {
                    masked.append(' ');
                }
                continue;
            }
            if (quote != 0) {
                masked.append(' ');
                if (current == '\\' && index + 1 < sql.length()) {
                    masked.append(' ');
                    index++;
                } else if (current == quote) {
                    if (next == quote) {
                        masked.append(' ');
                        index++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (current == '-' && next == '-') {
                masked.append("  " );
                index++;
                lineComment = true;
            } else if (current == '#') {
                masked.append(' ');
                lineComment = true;
            } else if (current == '/' && next == '*') {
                masked.append("  " );
                index++;
                blockComment = true;
            } else if (current == '\'' || current == '\"' || current == '`') {
                masked.append(' ');
                quote = current;
            } else {
                masked.append(current);
            }
        }
        return masked.toString();
    }

    private void validateSelectTree(Select select) throws UnsafeSqlException {
        if (select.getForClause() != null
                || select.getForUpdate() != null
                || select.getForMode() != null
                || select.getForUpdateTable() != null
                || (select.getForUpdateTables() != null && !select.getForUpdateTables().isEmpty())
                || select.isSkipLocked()
                || select.getWait() != null) {
            throw new UnsafeSqlException("不允许执行 FOR UPDATE、LOCK IN SHARE MODE 或其他锁定查询。");
        }

        if (select.getWithItemsList() != null) {
            for (WithItem<?> withItem : select.getWithItemsList()) {
                if (withItem.getSelect() == null) {
                    throw new UnsafeSqlException("WITH 子句只能包含只读 SELECT。");
                }
                validateSelectTree(withItem.getSelect().getSelect());
            }
        }

        if (select instanceof PlainSelect plainSelect) {
            if ((plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty())
                    || plainSelect.getIntoTempTable() != null
                    || plainSelect.getMySqlSelectIntoClause() != null
                    || (plainSelect.getSettings() != null && !plainSelect.getSettings().isEmpty())) {
                throw new UnsafeSqlException("不允许使用 SELECT INTO、OUTFILE、DUMPFILE 或变量写入。");
            }
        } else if (select instanceof SetOperationList setOperationList) {
            for (Select child : setOperationList.getSelects()) {
                validateSelectTree(child);
            }
        } else if (select instanceof ParenthesedSelect parenthesedSelect) {
            validateSelectTree(parenthesedSelect.getSelect());
        } else {
            throw new UnsafeSqlException("暂不支持该 SELECT 结构，无法确认其只读安全性。");
        }
    }

    private String validateCatalogReferences(Set<String> references, String catalog) {
        String current = unquoteIdentifier(catalog);
        for (String reference : references) {
            List<String> parts = splitQualifiedName(reference);
            if (parts.size() > 2) {
                return "不允许访问当前数据库之外的对象：" + reference;
            }
            if (parts.size() == 2 && !current.equalsIgnoreCase(unquoteIdentifier(parts.get(0)))) {
                return "不允许访问其他数据库：" + reference;
            }
        }
        return null;
    }

    private List<String> splitQualifiedName(String name) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < name.length(); i++) {
            char value = name.charAt(i);
            if ((value == '`' || value == '\"') && (quote == 0 || quote == value)) {
                quote = quote == 0 ? value : 0;
                current.append(value);
            } else if (value == '.' && quote == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private String unquoteIdentifier(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("`") && trimmed.endsWith("`"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private ObjectNode toJson(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<ColumnOutput> columns = readColumns(metadata);
        ObjectNode content = objectMapper.createObjectNode();
        ArrayNode columnArray = content.putArray("columns");
        for (ColumnOutput column : columns) {
            ObjectNode item = columnArray.addObject();
            item.put("name", column.outputName());
            item.put("type", column.typeName());
        }

        ArrayNode rowArray = content.putArray("rows");
        int rowCount = 0;
        boolean truncated = false;
        while (resultSet.next()) {
            if (rowCount == MAX_ROWS) {
                truncated = true;
                break;
            }
            ObjectNode row = rowArray.addObject();
            for (ColumnOutput column : columns) {
                writeValue(row, column.outputName(), resultSet, column);
            }
            rowCount++;
        }
        content.put("rowCount", rowCount);
        content.put("truncated", truncated);
        return content;
    }

    private List<ColumnOutput> readColumns(ResultSetMetaData metadata) throws SQLException {
        List<ColumnOutput> result = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            if (label == null || label.isBlank()) {
                label = metadata.getColumnName(index);
            }
            if (label == null || label.isBlank()) {
                label = "column_" + index;
            }
            int occurrence = occurrences.merge(label, 1, Integer::sum);
            String outputName = occurrence == 1 ? label : label + "_" + occurrence;
            result.add(new ColumnOutput(
                    index,
                    outputName,
                    metadata.getColumnType(index),
                    metadata.getColumnTypeName(index)));
        }
        return List.copyOf(result);
    }

    private void writeValue(
            ObjectNode row,
            String name,
            ResultSet resultSet,
            ColumnOutput column) throws SQLException {
        if (TEXT_TYPES.contains(column.jdbcType())) {
            String value = readText(resultSet, column.index());
            if (value == null) {
                row.putNull(name);
            } else {
                row.put(name, value);
            }
            return;
        }
        if (BINARY_TYPES.contains(column.jdbcType())) {
            String value = readBinary(resultSet, column.index());
            if (value == null) {
                row.putNull(name);
            } else {
                row.put(name, value);
            }
            return;
        }

        Object raw = resultSet.getObject(column.index());
        if (raw == null) {
            row.putNull(name);
            return;
        }

        if (NUMERIC_TYPES.contains(column.jdbcType())
                && (raw instanceof Number || raw instanceof BigInteger || raw instanceof BigDecimal)) {
            row.set(name, objectMapper.valueToTree(raw));
            return;
        }
        if (BOOLEAN_TYPES.contains(column.jdbcType()) && raw instanceof Boolean value) {
            row.put(name, value);
            return;
        }
        if (raw instanceof java.sql.Date value) {
            row.put(name, value.toLocalDate().toString());
            return;
        }
        if (raw instanceof java.sql.Time value) {
            row.put(name, value.toLocalTime().toString());
            return;
        }
        if (raw instanceof java.sql.Timestamp value) {
            row.put(name, value.toLocalDateTime().toString());
            return;
        }
        if (raw instanceof TemporalAccessor) {
            row.put(name, raw.toString());
            return;
        }
        row.put(name, truncate(String.valueOf(raw)));
    }

    private String readText(ResultSet resultSet, int index) throws SQLException {
        try (Reader reader = resultSet.getCharacterStream(index)) {
            if (reader == null) {
                return null;
            }
            return truncate(readCharacters(reader));
        } catch (java.io.IOException e) {
            throw new SQLException("读取文本字段失败", e);
        }
    }

    private String readCharacters(Reader reader) throws java.io.IOException {
        int limit = MAX_TEXT_LENGTH + 1;
        StringBuilder content = new StringBuilder();
        char[] buffer = new char[Math.min(512, limit)];
        while (content.length() < limit) {
            int count = reader.read(buffer, 0, Math.min(buffer.length, limit - content.length()));
            if (count < 0) {
                break;
            }
            content.append(buffer, 0, count);
        }
        return content.toString();
    }

    private String readBinary(ResultSet resultSet, int index) throws SQLException {
        int maxBytes = MAX_TEXT_LENGTH * 3 / 4 + 1;
        try (InputStream stream = resultSet.getBinaryStream(index)) {
            if (stream == null) {
                return null;
            }
            byte[] bytes = stream.readNBytes(maxBytes);
            return truncate(Base64.getEncoder().encodeToString(bytes));
        } catch (java.io.IOException e) {
            throw new SQLException("读取二进制字段失败", e);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH) + "…[已截断]";
    }

    private record ValidatedQuery(String sql, Set<String> tableReferences) {
    }

    private record ColumnOutput(int index, String outputName, int jdbcType, String typeName) {
    }

    private static class UnsafeSqlException extends Exception {
        private UnsafeSqlException(String message) {
            super(message);
        }
    }
}
