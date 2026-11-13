package com.example.harness.tool;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ListTablesToolRegistrationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private DataSource dataSource;

    @Test
    void autoRegistersToolAndConfiguresDataSourceWithoutConnecting() {
        assertInstanceOf(ListTablesTool.class, toolRegistry.find("list_tables").orElseThrow());
        assertTrue(toolRegistry.listDefinitions().stream()
                .anyMatch(definition -> "list_tables".equals(definition.name())));

        HikariDataSource hikari = assertInstanceOf(HikariDataSource.class, dataSource);
        assertEquals("com.mysql.cj.jdbc.Driver", hikari.getDriverClassName());
        assertEquals(0, hikari.getMinimumIdle());
        assertEquals(-1, hikari.getInitializationFailTimeout());
        assertEquals("CATALOG", hikari.getDataSourceProperties().getProperty("databaseTerm"));
        assertFalse(hikari.isRunning());
    }
}
