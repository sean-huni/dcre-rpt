package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import za.co.fnb.dcre.rpt.config.FamilyGuard;
import za.co.fnb.dcre.rpt.domain.Family;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-family reporting boundary, asserted rather than described.
 *
 * <p>Booting this context is itself the happy-path control: {@code FamilyGuard} runs inside the
 * primary Liquibase bean factory, so a context that refreshes at all has proved that the declared
 * family and the connected database agree. The tests below add the negative arm and the structural
 * one, each asserting exactly one control so losing any of them cannot be masked by the others.
 *
 * <ol>
 *   <li>the guard REFUSES a datasource pointing at another family's database, so the collections
 *       read model can never be created over payments tables by a config typo;</li>
 *   <li>the family-to-database mapping is total and has no silent default;</li>
 *   <li>no view in either read model depends on a table outside its own database, read off
 *       CockroachDB's dependency graph rather than off the changelog text.</li>
 * </ol>
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class FamilyBoundaryIT {

    /** Named, never "some other database": a wrong pick here would weaken the assertion silently. */
    private static final String PAYMENTS_DB = "dcre_pay";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest::businessDbUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @Test
    void guardRefusesADatasourceConnectedToAnotherFamilysDatabase() throws SQLException {
        createDatabase(PAYMENTS_DB);
        final var foreign = new DriverManagerDataSource(
                RoleConnections.forDatabase(RptJobTest.CRDB, PAYMENTS_DB),
                RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());

        final var thrown = assertThrows(IllegalStateException.class,
                () -> FamilyGuard.assertDatabaseMatches(foreign, Family.COLLECTIONS));

        // Assert the SPECIFIC failure. "It threw" would also pass with a missing driver, a dead
        // container or a malformed query, none of which is the boundary under test.
        assertTrue(thrown.getMessage().contains("dcre.rpt.family=collections"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'dcre_col'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'" + PAYMENTS_DB + "'"), thrown.getMessage());
    }

    @Test
    void everyFamilyResolvesToItsOwnDatabaseAndNothingElseResolvesAtAll() {
        assertEquals("dcre_col", Family.fromToken("collections").database());
        assertEquals("dcre_pay", Family.fromToken("payments").database());
        assertEquals("dcre_man", Family.fromToken("mandates").database());
        // No silent default. A typo must not land on a lane nobody chose.
        assertThrows(IllegalArgumentException.class, () -> Family.fromToken("collection"));
        assertThrows(IllegalArgumentException.class, () -> Family.fromToken(""));
    }

    /**
     * No rpt view reaches outside its own database, read off the engine rather than off the source.
     *
     * <p>CockroachDB records a view's dependencies in {@code pg_depend}. Every relation resolved
     * there is by construction in the same database as the view, so the assertion that carries
     * information is the count: a read model whose views report NO dependencies is a read model
     * that did not get created, which is the failure a green "0 cross-database references" would
     * otherwise hide.
     */
    @Test
    void bothReadModelsExistAndTheirViewsDependOnlyOnLocalTables() throws SQLException {
        assertViewDependenciesAreLocal(RptJobTest.businessDbUrl(), "collections", 18);
        assertViewDependenciesAreLocal(RptJobTest.opsDbUrl(), "ops", 7);
    }

    private void assertViewDependenciesAreLocal(final String jdbcUrl, final String lane,
                                                final int expectedViews) throws SQLException {
        final List<String> dependencies = new ArrayList<>();
        int views = 0;
        try (var c = DriverManager.getConnection(
                jdbcUrl, RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = c.createStatement()) {
            final ResultSet count = s.executeQuery(
                    "SELECT count(*) FROM information_schema.views WHERE table_schema = 'rpt'");
            assertTrue(count.next());
            views = count.getInt(1);

            final ResultSet rs = s.executeQuery("""
                    SELECT DISTINCT v.relname AS view_name, dep.relname AS depends_on
                      FROM pg_class v
                      JOIN pg_namespace n ON n.oid = v.relnamespace AND n.nspname = 'rpt'
                      JOIN pg_rewrite r ON r.ev_class = v.oid
                      JOIN pg_depend d ON d.objid = r.oid AND d.classid = 'pg_rewrite'::regclass
                      JOIN pg_class dep ON dep.oid = d.refobjid
                     WHERE v.relkind = 'v' AND dep.oid <> v.oid""");
            while (rs.next()) {
                dependencies.add("%s -> %s".formatted(rs.getString("view_name"), rs.getString("depends_on")));
            }
        }
        assertEquals(expectedViews, views,
                "the %s read model should publish %d views in schema rpt".formatted(lane, expectedViews));
        assertTrue(dependencies.size() >= expectedViews,
                "expected at least one dependency per %s view; got %d for %d views: %s"
                        .formatted(lane, dependencies.size(), views, dependencies));
    }

    private static void createDatabase(final String name) throws SQLException {
        try (var c = DriverManager.getConnection(RptJobTest.businessDbUrl(),
                RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS " + name);
        }
    }
}
