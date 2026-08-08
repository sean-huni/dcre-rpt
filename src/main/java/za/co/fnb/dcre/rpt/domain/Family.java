package za.co.fnb.dcre.rpt.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * The three DCRE families, each with the one database it owns.
 *
 * <p>This mapping is domain fact, not configuration. The database a family's rows live in is the
 * boundary itself: with one database per family there is no lane discriminator to filter on, which
 * is precisely why {@code tx_header.flow} is being deleted. Making the pair configurable would
 * make the boundary a setting, and a boundary that can be set wrong silently is not a boundary.
 *
 * @see <a href="https://microservices.io/patterns/data/database-per-service.html">Database per Service</a>
 */
public enum Family {

    COLLECTIONS("dcre_col"),
    PAYMENTS("dcre_pay"),
    MANDATES("dcre_man");

    private final String database;

    Family(final String database) {
        this.database = database;
    }

    /** The database this family owns, and the only one its read model may be created in. */
    public String database() {
        return database;
    }

    /** The {@code dcre.rpt.family} token, which is also the changelog master's name segment. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Fails loudly on an unknown token rather than defaulting to a lane nobody chose. */
    public static Family fromToken(final String token) {
        return Arrays.stream(values())
                .filter(f -> f.token().equalsIgnoreCase(token))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown dcre.rpt.family '%s'; expected one of %s"
                                .formatted(token, Arrays.stream(values()).map(Family::token).toList())));
    }
}
