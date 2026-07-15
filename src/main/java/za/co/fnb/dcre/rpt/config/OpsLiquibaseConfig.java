package za.co.fnb.dcre.rpt.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class OpsLiquibaseConfig {

    /** Ops views live in agt_ops; own SpringLiquibase, own rpt history tables there. */
    @Bean
    public SpringLiquibase opsLiquibase(
            @Value("${dcre.rpt.ops-db-url}") String url,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password:}") String password) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(DataSourceBuilder.create()
                .url(url).username(user).password(password).build());
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-ops-master.xml");
        liquibase.setDatabaseChangeLogTable("rpt_databasechangelog");
        liquibase.setDatabaseChangeLogLockTable("rpt_databasechangeloglock");
        return liquibase;
    }

    /**
     * Boot 4's LiquibaseAutoConfiguration backs off entirely once ANY user-defined
     * SpringLiquibase bean exists (class-level ConditionalOnMissingBean on its inner
     * LiquibaseConfiguration, verified against spring-boot-liquibase 4.1.0), so the primary
     * dcre_collections migration is declared explicitly here, wired from the same
     * spring.liquibase.* properties the auto-configuration would have used. Both beans are
     * eager singletons: each runs its changelog during context refresh, before any test or
     * runner code executes, and Boot's database-initialization detector still orders
     * JDBC-dependent beans (Batch metadata access) after every SpringLiquibase bean.
     */
    @Bean
    public SpringLiquibase liquibase(
            DataSource dataSource,
            @Value("${spring.liquibase.change-log}") String changeLog,
            @Value("${spring.liquibase.database-change-log-table}") String historyTable,
            @Value("${spring.liquibase.database-change-log-lock-table}") String lockTable) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setDatabaseChangeLogTable(historyTable);
        liquibase.setDatabaseChangeLogLockTable(lockTable);
        return liquibase;
    }
}
