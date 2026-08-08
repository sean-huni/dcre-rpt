package za.co.fnb.dcre.rpt.config;

import liquibase.UpdateSummaryEnum;
import liquibase.UpdateSummaryOutputEnum;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.ui.UIServiceEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import za.co.fnb.dcre.rpt.domain.Family;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(LiquibaseProperties.class)
public class OpsLiquibaseConfig {

    /**
     * Ops views live in agt_ops; own SpringLiquibase, own rpt history tables there. The migration
     * DataSource is a deliberately non-pooling {@link SimpleDriverDataSource}: a one-shot migration
     * needs no pool, and a pooled one would idle unclosed for the life of the context. Built via
     * {@link DataSourceBuilder} because the PostgreSQL driver is runtime-only.
     */
    @Bean
    public SpringLiquibase opsLiquibase(
            @Value("${dcre.rpt.ops-db-url}") final String url,
            @Value("${spring.datasource.username}") final String user,
            @Value("${spring.datasource.password:}") final String password) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url).username(user).password(password).build());
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-ops-master.xml");
        liquibase.setDatabaseChangeLogTable("rpt_databasechangelog");
        liquibase.setDatabaseChangeLogLockTable("rpt_databasechangeloglock");
        return liquibase;
    }

    /**
     * Boot 4's LiquibaseAutoConfiguration backs off entirely once ANY user-defined SpringLiquibase
     * bean exists (class-level ConditionalOnMissingBean on its inner LiquibaseConfiguration,
     * verified against spring-boot-liquibase 4.1.0), so the primary dcre_col migration is
     * declared explicitly here, wired from the bound {@link LiquibaseProperties} exactly as the
     * auto-configuration would wire it. Every spring.liquibase.* property that LiquibaseProperties
     * exposes on Boot 4.1 is honored: change-log, clear-checksums, contexts, default-schema,
     * liquibase-schema, liquibase-tablespace, database-change-log-table,
     * database-change-log-lock-table, drop-first, enabled (as shouldRun), label-filter, parameters,
     * rollback-file, test-rollback-on-update, tag, show-summary, show-summary-output, ui-service,
     * analytics-enabled and license-key. NOT reproduced from the auto-configuration:
     * SpringLiquibaseCustomizer beans, LiquibaseConnectionDetails and the url/user/password/
     * driver-class-name migration-DataSource derivation (this bean always migrates through the
     * application DataSource). Both beans are eager singletons: each runs its changelog during
     * context refresh, before any test or runner code executes, and Boot's database-initialization
     * detector still orders JDBC-dependent beans (Batch metadata access) after every
     * SpringLiquibase bean.
     */
    @Bean
    public SpringLiquibase liquibase(final DataSource dataSource, final LiquibaseProperties properties,
                                     @Value("${dcre.rpt.family}") final String family) {
        // Ordering is the whole point: the check runs inside this factory method, so it precedes
        // SpringLiquibase.afterPropertiesSet() by construction rather than by bean-order luck.
        // A mismatched process therefore creates nothing before it dies.
        FamilyGuard.assertDatabaseMatches(dataSource, Family.fromToken(family));
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(properties.getChangeLog());
        liquibase.setClearCheckSums(properties.isClearChecksums());
        if (!CollectionUtils.isEmpty(properties.getContexts())) {
            liquibase.setContexts(StringUtils.collectionToCommaDelimitedString(properties.getContexts()));
        }
        liquibase.setDefaultSchema(properties.getDefaultSchema());
        liquibase.setLiquibaseSchema(properties.getLiquibaseSchema());
        liquibase.setLiquibaseTablespace(properties.getLiquibaseTablespace());
        liquibase.setDatabaseChangeLogTable(properties.getDatabaseChangeLogTable());
        liquibase.setDatabaseChangeLogLockTable(properties.getDatabaseChangeLogLockTable());
        liquibase.setDropFirst(properties.isDropFirst());
        liquibase.setShouldRun(properties.isEnabled());
        if (!CollectionUtils.isEmpty(properties.getLabelFilter())) {
            liquibase.setLabelFilter(StringUtils.collectionToCommaDelimitedString(properties.getLabelFilter()));
        }
        liquibase.setChangeLogParameters(properties.getParameters());
        liquibase.setRollbackFile(properties.getRollbackFile());
        liquibase.setTestRollbackOnUpdate(properties.isTestRollbackOnUpdate());
        liquibase.setTag(properties.getTag());
        if (properties.getShowSummary() != null) {
            liquibase.setShowSummary(UpdateSummaryEnum.valueOf(properties.getShowSummary().name()));
        }
        if (properties.getShowSummaryOutput() != null) {
            liquibase.setShowSummaryOutput(UpdateSummaryOutputEnum.valueOf(properties.getShowSummaryOutput().name()));
        }
        if (properties.getUiService() != null) {
            liquibase.setUiService(UIServiceEnum.valueOf(properties.getUiService().name()));
        }
        if (properties.getAnalyticsEnabled() != null) {
            liquibase.setAnalyticsEnabled(properties.getAnalyticsEnabled());
        }
        if (properties.getLicenseKey() != null) {
            liquibase.setLicenseKey(properties.getLicenseKey());
        }
        return liquibase;
    }
}
