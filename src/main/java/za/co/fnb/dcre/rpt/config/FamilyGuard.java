package za.co.fnb.dcre.rpt.config;

import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.rpt.domain.Family;

import javax.sql.DataSource;

/**
 * Refuses to migrate when the declared family and the connected database disagree.
 *
 * <p>Without this, pointing {@code DCRE_DB_URL} at {@code dcre_pay} while the family stays
 * {@code collections} is a configuration typo that reads as a working deployment: the collections
 * read model would be created over whatever tables happen to answer to those names, and every
 * client dashboard would then report payments figures under a collections heading. That is the
 * exact defect the family split exists to remove, arriving through the back door.
 *
 * <p>It fails CLOSED and it fails on the specific mismatch, never on "something looked wrong":
 * the message names both sides, so a wrong answer cannot be mistaken for an unavailable one.
 */
public final class FamilyGuard {

    private FamilyGuard() {
    }

    /**
     * @throws IllegalStateException when {@code current_database()} is not the declared family's
     *                               own database. Thrown before any changelog runs, so a
     *                               mismatched process creates nothing at all.
     */
    public static void assertDatabaseMatches(final DataSource dataSource, final Family family) {
        final String actual = new JdbcTemplate(dataSource)
                .queryForObject("SELECT current_database()", String.class);
        if (!family.database().equals(actual)) {
            throw new IllegalStateException(
                    "dcre.rpt.family=%s owns database '%s' but this datasource is connected to '%s'; "
                            .formatted(family.token(), family.database(), actual)
                            + "refusing to create the " + family.token()
                            + " read model in another family's database");
        }
    }
}
