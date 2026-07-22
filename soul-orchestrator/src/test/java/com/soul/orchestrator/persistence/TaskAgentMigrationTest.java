package com.soul.orchestrator.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 0 exit test (docs/task-agent.md §31): prove {@code V1__task_agent.sql} applies to a
 * real Postgres and creates the schema the Task Agent will build on.
 *
 * <p>{@code disabledWithoutDocker} keeps the promise that <b>every existing test stays green</b>
 * even where no container runtime is reachable — this is the only test that needs one, and it
 * skips rather than fails when Docker/podman is absent. On a machine with the podman socket
 * exposed as the Docker API (see {@code build.gradle}), it runs for real.
 */
@Testcontainers(disabledWithoutDocker = true)
class TaskAgentMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("soul")
            .withUsername("soul")
            .withPassword("soul");

    /** Raw JDBC — the postgres driver registers via SPI at runtime (it's runtimeOnly). */
    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Migrate once against the shared container; tests then assert the resulting state. */
    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void v1CreatesEveryTaskTable() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection c = open();
                ResultSet rs = c.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        assertThat(tables).contains(
                "task", "reminder", "recurrence", "scheduled_job", "notification", "task_audit");
    }

    @Test
    void flywayRecordedTheMigrationRatherThanItBeingHandApplied() throws SQLException {
        try (Connection c = open();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT success FROM flyway_schema_history WHERE version = '1'")) {
            assertThat(rs.next()).as("a version-1 row exists").isTrue();
            assertThat(rs.getBoolean("success")).isTrue();
        }
    }

    @Test
    void priorityCheckConstraintRejectsOutOfRangeValues() throws SQLException {
        try (Connection c = open();
                Statement s = c.createStatement()) {
            // 0..4 is the valid band (§17); 9 must be refused by chk_task_priority.
            assertThatThrownBy(() -> s.execute(
                    "INSERT INTO task (tenant_id, user_id, title, priority) "
                    + "VALUES (gen_random_uuid(), gen_random_uuid(), 'bad', 9)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void deletingATaskCascadesToItsReminders() throws SQLException {
        try (Connection c = open();
                Statement s = c.createStatement()) {
            s.execute("INSERT INTO task (id, tenant_id, user_id, title) VALUES "
                    + "('11111111-1111-1111-1111-111111111111', gen_random_uuid(), gen_random_uuid(), 'call dentist')");
            s.execute("INSERT INTO reminder (task_id, fire_at) VALUES "
                    + "('11111111-1111-1111-1111-111111111111', now())");
            s.execute("DELETE FROM task WHERE id = '11111111-1111-1111-1111-111111111111'");
            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM reminder WHERE task_id = '11111111-1111-1111-1111-111111111111'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("reminders cascade-deleted with the task").isZero();
            }
        }
    }
}
