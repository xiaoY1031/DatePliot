package com.example.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DescribeTableToolTest {

    private static final String CATALOG = "datapilot";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet tableInfo = mock(ResultSet.class);
    private final ResultSet primaryKeys = mock(ResultSet.class);
    private final ResultSet columns = mock(ResultSet.class);
    private final DescribeTableTool tool = new DescribeTableTool(dataSource, objectMapper);
    private final ToolRegistry registry = new ToolRegistry(List.of(tool));
    private final ToolExecutor executor = new ToolExecutor(registry, objectMapper);

    @Test
    void definesRequiredTableNameAndExposesToolThroughRegistry() {
        ToolDefinition definition = tool.definition();

        assertEquals("describe_table", definition.name());
        assertFalse(definition.description().isBlank());
        assertEquals("object", definition.parameters().get("type"));
        assertEquals(List.of("table_name"), definition.parameters().get("required"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
        assertEquals("string", ((Map<?, ?>) properties.get("table_name")).get("type"));
        assertEquals(List.of(definition), registry.listDefinitions());
    }

    @Test
    void returnsColumnsInOrdinalOrderWithCommentAndPrimaryKey() throws Exception {
        prepareOrdinaryTable("order_item", "订单明细");
        preparePrimaryKeys("order_item", "id");
        when(metadata.getColumns(eq(CATALOG), isNull(), eq("order\\_item"), eq("%")))
                .thenReturn(columns);
        when(columns.next()).thenReturn(true, true, true, false);
        when(columns.getString("TABLE_CAT")).thenReturn(CATALOG);
        when(columns.getString("TABLE_NAME")).thenReturn("order_item");
        when(columns.getString("COLUMN_NAME")).thenReturn("title", "id", "amount");
        when(columns.getString("TYPE_NAME")).thenReturn("VARCHAR", "BIGINT", "DECIMAL");
        when(columns.getObject("ORDINAL_POSITION")).thenReturn(2, 1, 3);
        when(columns.getObject("NULLABLE")).thenReturn(
                DatabaseMetaData.columnNullable,
                DatabaseMetaData.columnNoNulls,
                DatabaseMetaData.columnNullableUnknown);
        when(columns.getObject("COLUMN_SIZE")).thenReturn(255, 19, 10);
        when(columns.getObject("DECIMAL_DIGITS")).thenReturn(null, 0, 2);
        when(columns.getString("COLUMN_DEF")).thenReturn(null, null, "0.00");

        ToolResult result = execute("order_item");

        assertTrue(result.success());
        JsonNode content = objectMapper.readTree(result.content());
        assertEquals("order_item", content.path("table_name").asText());
        assertEquals("订单明细", content.path("table_comment").asText());
        JsonNode resultColumns = content.path("columns");
        assertEquals(List.of("id", "title", "amount"),
                List.of(
                        resultColumns.get(0).path("name").asText(),
                        resultColumns.get(1).path("name").asText(),
                        resultColumns.get(2).path("name").asText()));
        assertTrue(resultColumns.get(0).path("primary_key").asBoolean());
        assertFalse(resultColumns.get(1).path("primary_key").asBoolean());
        assertFalse(resultColumns.get(0).path("nullable").asBoolean());
        assertTrue(resultColumns.get(1).path("nullable").asBoolean());
        assertTrue(resultColumns.get(2).path("nullable").isNull());
        assertEquals(10, resultColumns.get(2).path("size").asInt());
        assertEquals(2, resultColumns.get(2).path("decimal_digits").asInt());
        assertEquals("0.00", resultColumns.get(2).path("default_value").asText());
        assertEquals(3, resultColumns.get(2).path("ordinal_position").asInt());
        verify(tableInfo).close();
        verify(primaryKeys).close();
        verify(columns).close();
        verify(connection).close();
    }

    @Test
    void recognizesCompositePrimaryKey() throws Exception {
        prepareOrdinaryTable("course_teacher", null);
        preparePrimaryKeys("course_teacher", "course_id", "teacher_id");
        prepareColumns("course_teacher", "course_id", "teacher_id", "created_at");

        ToolResult result = execute("course_teacher");

        assertTrue(result.success());
        JsonNode resultColumns = objectMapper.readTree(result.content()).path("columns");
        assertTrue(resultColumns.get(0).path("primary_key").asBoolean());
        assertTrue(resultColumns.get(1).path("primary_key").asBoolean());
        assertFalse(resultColumns.get(2).path("primary_key").asBoolean());
        assertTrue(objectMapper.readTree(result.content()).path("table_comment").isNull());
    }

    @Test
    void rejectsMissingInvalidAndUnexpectedArgumentsBeforeConnecting() {
        ToolResult missing = executor.execute("call_1", "describe_table", "{}");
        ToolResult blank = executor.execute("call_2", "describe_table", "{\"table_name\":\" \"}");
        ToolResult unsafe = executor.execute("call_3", "describe_table",
                "{\"table_name\":\"other.post\"}");
        ToolResult extra = executor.execute("call_4", "describe_table",
                "{\"table_name\":\"post\",\"database\":\"other\"}");

        assertEquals("INVALID_ARGUMENTS", missing.errorCode());
        assertEquals("INVALID_TABLE_NAME", blank.errorCode());
        assertEquals("INVALID_TABLE_NAME", unsafe.errorCode());
        assertEquals("INVALID_ARGUMENTS", extra.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void returnsNotFoundWhenTableDoesNotExistInCurrentDatabase() throws Exception {
        prepareConnection();
        when(metadata.getTables(eq(CATALOG), isNull(), eq("missing"), isNull()))
                .thenReturn(tableInfo);
        when(tableInfo.next()).thenReturn(true, false);
        when(tableInfo.getString("TABLE_CAT")).thenReturn("other_database");
        when(tableInfo.getString("TABLE_NAME")).thenReturn("missing");

        ToolResult result = execute("missing");

        assertFalse(result.success());
        assertEquals("TABLE_NOT_FOUND", result.errorCode());
        verify(metadata, never()).getPrimaryKeys(eq(CATALOG), isNull(), eq("missing"));
        verify(metadata, never()).getColumns(eq(CATALOG), isNull(), eq("missing"), eq("%"));
    }

    @Test
    void rejectsViewInsteadOfReadingItsColumns() throws Exception {
        prepareConnection();
        when(metadata.getTables(eq(CATALOG), isNull(), eq("post\\_view"), isNull()))
                .thenReturn(tableInfo);
        when(tableInfo.next()).thenReturn(true, false);
        when(tableInfo.getString("TABLE_CAT")).thenReturn(CATALOG);
        when(tableInfo.getString("TABLE_NAME")).thenReturn("post_view");
        when(tableInfo.getString("TABLE_TYPE")).thenReturn("VIEW");

        ToolResult result = execute("post_view");

        assertFalse(result.success());
        assertEquals("TABLE_TYPE_NOT_ALLOWED", result.errorCode());
        verify(metadata, never()).getPrimaryKeys(eq(CATALOG), isNull(), eq("post_view"));
    }

    @Test
    void rejectsSystemDatabase() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("mysql");

        ToolResult result = execute("user");

        assertEquals("SYSTEM_DATABASE_NOT_ALLOWED", result.errorCode());
        verify(connection, never()).getMetaData();
    }

    @Test
    void reportsConnectionFailureWithoutLeakingDriverMessage() throws Exception {
        when(dataSource.getConnection()).thenThrow(
                new SQLException("jdbc:mysql://private-host/db password=secret"));

        ToolResult result = execute("post");

        assertEquals("DATABASE_CONNECTION_ERROR", result.errorCode());
        assertFalse(result.toModelContent().contains("private"));
        assertFalse(result.toModelContent().contains("secret"));
    }

    @Test
    void reportsPrimaryKeyFailureWithoutReadingColumns() throws Exception {
        prepareOrdinaryTable("post", "文章");
        when(metadata.getPrimaryKeys(eq(CATALOG), isNull(), eq("post")))
                .thenThrow(new SQLException("password=secret"));

        ToolResult result = execute("post");

        assertEquals("PRIMARY_KEY_METADATA_ERROR", result.errorCode());
        assertFalse(result.toModelContent().contains("secret"));
        verify(metadata, never()).getColumns(eq(CATALOG), isNull(), eq("post"), eq("%"));
        verify(connection).close();
    }

    @Test
    void reportsColumnFailureAndReleasesResources() throws Exception {
        prepareOrdinaryTable("post", "文章");
        preparePrimaryKeys("post", "id");
        when(metadata.getColumns(eq(CATALOG), isNull(), eq("post"), eq("%")))
                .thenThrow(new SQLException("user=private password=secret"));

        ToolResult result = execute("post");

        assertEquals("COLUMN_METADATA_ERROR", result.errorCode());
        assertFalse(result.toModelContent().contains("private"));
        assertFalse(result.toModelContent().contains("secret"));
        verify(primaryKeys).close();
        verify(connection).close();
    }

    @Test
    void reportsEmptyColumnMetadata() throws Exception {
        prepareOrdinaryTable("post", "文章");
        preparePrimaryKeys("post");
        when(metadata.getColumns(eq(CATALOG), isNull(), eq("post"), eq("%")))
                .thenReturn(columns);
        when(columns.next()).thenReturn(false);

        ToolResult result = execute("post");

        assertEquals("TABLE_COLUMNS_EMPTY", result.errorCode());
    }

    private ToolResult execute(String tableName) {
        return executor.execute("call_describe_table", "describe_table",
                "{\"table_name\":" + objectMapper.valueToTree(tableName) + "}");
    }

    private void prepareConnection() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn(CATALOG);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
    }

    private void prepareOrdinaryTable(String tableName, String comment) throws SQLException {
        prepareConnection();
        String pattern = tableName.replace("_", "\\_");
        when(metadata.getTables(eq(CATALOG), isNull(), eq(pattern), isNull()))
                .thenReturn(tableInfo);
        when(tableInfo.next()).thenReturn(true, false);
        when(tableInfo.getString("TABLE_CAT")).thenReturn(CATALOG);
        when(tableInfo.getString("TABLE_NAME")).thenReturn(tableName);
        when(tableInfo.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(tableInfo.getString("REMARKS")).thenReturn(comment);
    }

    private void preparePrimaryKeys(String tableName, String... keyColumns) throws SQLException {
        when(metadata.getPrimaryKeys(eq(CATALOG), isNull(), eq(tableName)))
                .thenReturn(primaryKeys);
        Boolean[] rows = new Boolean[keyColumns.length + 1];
        for (int i = 0; i < keyColumns.length; i++) {
            rows[i] = true;
        }
        rows[keyColumns.length] = false;
        when(primaryKeys.next()).thenReturn(rows[0], java.util.Arrays.copyOfRange(rows, 1, rows.length));
        when(primaryKeys.getString("TABLE_CAT")).thenReturn(CATALOG);
        when(primaryKeys.getString("TABLE_NAME")).thenReturn(tableName);
        if (keyColumns.length > 0) {
            when(primaryKeys.getString("COLUMN_NAME")).thenReturn(
                    keyColumns[0], java.util.Arrays.copyOfRange(keyColumns, 1, keyColumns.length));
        }
    }

    private void prepareColumns(String tableName, String... columnNames) throws SQLException {
        String pattern = tableName.replace("_", "\\_");
        when(metadata.getColumns(eq(CATALOG), isNull(), eq(pattern), eq("%")))
                .thenReturn(columns);
        Boolean[] rows = new Boolean[columnNames.length + 1];
        for (int i = 0; i < columnNames.length; i++) {
            rows[i] = true;
        }
        rows[columnNames.length] = false;
        when(columns.next()).thenReturn(rows[0], java.util.Arrays.copyOfRange(rows, 1, rows.length));
        when(columns.getString("TABLE_CAT")).thenReturn(CATALOG);
        when(columns.getString("TABLE_NAME")).thenReturn(tableName);
        when(columns.getString("COLUMN_NAME")).thenReturn(
                columnNames[0], java.util.Arrays.copyOfRange(columnNames, 1, columnNames.length));
        when(columns.getString("TYPE_NAME")).thenReturn("BIGINT");
        Integer[] positions = new Integer[columnNames.length];
        for (int i = 0; i < columnNames.length; i++) {
            positions[i] = i + 1;
        }
        var positionsStub = when(columns.getObject("ORDINAL_POSITION"));
        for (Integer position : positions) {
            positionsStub = positionsStub.thenReturn(position);
        }
        when(columns.getObject("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls);
        when(columns.getObject("COLUMN_SIZE")).thenReturn(19);
        when(columns.getObject("DECIMAL_DIGITS")).thenReturn(0);
        when(columns.getString("COLUMN_DEF")).thenReturn((String) null);
    }
}
