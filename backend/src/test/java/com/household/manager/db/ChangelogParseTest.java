package com.household.manager.db;

import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parst das komplette Liquibase-Changelog gegen das XSD — ohne Datenbank.
 *
 * <p>Hintergrund: Ein XSD-Verstoss in einem Changeset (z. B. {@code preConditions} nach
 * {@code comment} statt davor) faellt sonst erst beim Start gegen eine echte Datenbank auf,
 * weil Liquibase das Changelog erst nach dem Verbindungsaufbau liest. Auf Rechnern ohne
 * erreichbare Test-DB scheitert {@code contextLoads} vorher an der Verbindung und deckt den
 * Fehler nie auf. Dieser Test braucht keine Verbindung und faellt deshalb ueberall auf.
 */
class ChangelogParseTest {

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.xml";

    @Test
    void masterChangelogIstXsdKonformUndVollstaendigParsebar() throws Exception {
        ResourceAccessor resourceAccessor = new ClassLoaderResourceAccessor(getClass().getClassLoader());
        ChangeLogParser parser = ChangeLogParserFactory.getInstance()
                .getParser(MASTER_CHANGELOG, resourceAccessor);

        DatabaseChangeLog changeLog =
                parser.parse(MASTER_CHANGELOG, new ChangeLogParameters(), resourceAccessor);

        // Jedes inkludierte Changeset wurde geladen; die Untergrenze schuetzt davor, dass ein
        // leeres oder halb geparstes Changelog als "gueltig" durchgeht.
        assertThat(changeLog.getChangeSets()).hasSizeGreaterThan(30);
    }

    @Test
    void kalenderChangesetIstEnthalten() throws Exception {
        ResourceAccessor resourceAccessor = new ClassLoaderResourceAccessor(getClass().getClassLoader());
        ChangeLogParser parser = ChangeLogParserFactory.getInstance()
                .getParser(MASTER_CHANGELOG, resourceAccessor);

        DatabaseChangeLog changeLog =
                parser.parse(MASTER_CHANGELOG, new ChangeLogParameters(), resourceAccessor);

        assertThat(changeLog.getChangeSets())
                .anyMatch(changeSet -> changeSet.getId().equals("20260725-0039"));
    }
}
