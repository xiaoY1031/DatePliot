package com.example.harness.tool;

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
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListTablesToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet tables = mock(ResultSet.class);
    private final ListTablesTool tool = new ListTablesTool(dataSource, objectMapper);
    private final ToolRegistry registry = new ToolRegistry(List.of(tool));
    private final ToolExecutor executor = new ToolExecutor(registry, objectMapper);

    @Test
    void definesParameterlessToolAndExposesItThroughRegistry() {
        ToolDefinition definition = tool.definition();

        assertEquals("list_tables", definition.name());
        assertFalse(definition.description().isBlank());
        assertEquals("object", definition.parameters().get("type"));
        assertEquals(Map.of(), definition.parameters().get("properties"));
        assertEquals(List.of(), definition.parameters().get("required"));
        assertEquals(false, definition.parameters().get("additionalProperties"));
        assertEquals(List.of(definition), registry.listDefinitions());
    }

    @Test
    void executesThroughRegistryAndReturnsSortedDistinctTables() throws Exception {
        prepareMetadata();
        when(tables.next()).thenReturn(true, true, true, true, false);
        when(tables.getString("TABLE_CAT")).thenReturn("datapilot");
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(tables.getString("TABLE_NAME")).thenReturn("users", "orders", "products", "orders");

        ToolResult result = execute();

        assertTrue(result.success());
        assertEquals(objectMapper.readTree("{\"tables\":[\"orders\",\"products\",\"users\"]}"),
                objectMapper.readTree(result.content()));
        verify(metadata).getTables(eq("datapilot"), isNull(), eq("%"), aryEq(new String[]{"TABLE"}));
        verify(tables).close();
        verify(connection).close();
    }

    @Test
    void filtersViewsSystemTablesOtherDatabasesAndEmptyNames() throws Exception {
        prepareMetadata();
        when(tables.next()).thenReturn(true, true, true, true, true, true, false);
        when(tables.getString("TABLE_CAT")).thenReturn(
                "datapilot", "datapilot", "other_database", "datapilot", "datapilot", "datapilot");
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE", "VIEW", "SYSTEM TABLE", "TABLE");
        when(tables.getString("TABLE_NAME")).thenReturn("orders", null, " ");

        ToolResult result = execute();

        assertTrue(result.success());
        assertEquals("{\"tables\":[\"orders\"]}", result.content());
    }

    @Test
    void returnsSuccessfulEmptyArrayWhenThereAreNoTables() throws Exception {
        prepareMetadata();
        when(tables.next()).thenReturn(false);

        ToolResult result = execute();

        assertTrue(result.success());
        assertEquals("{\"tables\":[]}", result.content());
        verify(tables).close();
        verify(connection).close();
    }

    @Test
    void serializesTableNamesWithoutBreakingJson() throws Exception {
        prepareMetadata();
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_CAT")).thenReturn("datapilot");
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(tables.getString("TABLE_NAME")).thenReturn("订单\"明细");

        ToolResult result = execute();

        assertTrue(result.success());
        assertEquals("订单\"明细", objectMapper.readTree(result.content()).path("tables").get(0).asText());
    }

    @Test
    void rejectsMissingDatabaseInsteadOfQueryingAllDatabases() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn(null, " ");

        assertEquals("DATABASE_NOT_SELECTED", execute().errorCode());
        assertEquals("DATABASE_NOT_SELECTED", execute().errorCode());
        verifyNoInteractions(metadata);
    }

    @Test
    void rejectsSystemDatabases() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("mysql", "information_schema", "performance_schema", "SYS");

        for (int i = 0; i < 4; i++) {
            assertEquals("SYSTEM_DATABASE_NOT_ALLOWED", execute().errorCode());
        }
        verifyNoInteractions(metadata);
    }

    @Test
    void rejectsArgumentsWithoutOpeningAConnection() {
        ToolResult result = executor.execute("call_1", "list_tables",
                "{\"url\":\"jdbc:mysql://untrusted/other\"}");

        assertFalse(result.success());
        assertEquals("INVALID_ARGUMENTS", result.errorCode());
        verifyNoInteractions(dataSource);
    }

    @Test
    void reportsConnectionFailureWithoutLeakingDriverMessage() throws Exception {
        when(dataSource.getConnection()).thenThrow(
                new SQLException("jdbc:mysql://private-host/db user=private-user password=secret"));

        ToolResult result = execute();

        assertFalse(result.success());
        assertEquals("DATABASE_CONNECTION_ERROR", result.errorCode());
        assertFalse(result.toModelContent().contains("private"));
        assertFalse(result.toModelContent().contains("secret"));
    }

    @Test
    void reportsMetadataFailureAndReleasesConnection() throws Exception {
        prepareMetadata();
        when(metadata.getTables(eq("datapilot"), isNull(), eq("%"), aryEq(new String[]{"TABLE"})))
                .thenThrow(new SQLException("password=secret"));

        ToolResult result = execute();

        assertFalse(result.success());
        assertEquals("DATABASE_METADATA_ERROR", result.errorCode());
        assertFalse(result.toModelContent().contains("secret"));
        verify(connection).close();
    }

    @Test
    void reportsResultReadFailureWithoutReturningPartialTables() throws Exception {
        prepareMetadata();
        when(tables.next()).thenReturn(true).thenThrow(new SQLException("password=secret"));
        when(tables.getString("TABLE_CAT")).thenReturn("datapilot");
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(tables.getString("TABLE_NAME")).thenReturn("orders");

        ToolResult result = execute();

        assertFalse(result.success());
        assertEquals("DATABASE_METADATA_ERROR", result.errorCode());
        assertFalse(result.content().contains("orders"));
        assertFalse(result.content().contains("secret"));
        verify(tables).close();
        verify(connection).close();
    }

    private ToolResult execute() {
        return executor.execute("call_list_tables", "list_tables", "{}");
    }

    private void prepareMetadata() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("datapilot");
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(eq("datapilot"), isNull(), eq("%"), aryEq(new String[]{"TABLE"})))
                .thenReturn(tables);
    }
}
