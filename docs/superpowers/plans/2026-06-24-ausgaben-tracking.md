# Ausgaben-Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a finance module that imports bank statements (CAMT V8 / camt.053), stores multi-account transactions, categorizes them with learnable rules, detects recurring payments, tracks budgets, and provides an analytics overview UI with two switchable layouts.

**Architecture:** Spring Boot layered architecture (Controller → Service → Repository) with a dedicated `finance` package for CAMT parsing (JAXB from the official XSD). All JPA repositories live in `com.household.manager.repository`. Liquibase changesets for every schema change. Angular 19 standalone frontend with separate HTML/SCSS and ECharts via ngx-echarts. Signed `BigDecimal` amounts (negative = expense). Idempotent imports via a unique `dedupHash`.

**Tech Stack:** Java 21, Spring Boot 3.4.1, MariaDB, Liquibase, Lombok, JAXB (`jaxb-runtime` + `jaxb2-maven-plugin`), JUnit 5; Angular 19, TypeScript, SCSS, ngx-echarts.

**Conventions (from the existing codebase — follow exactly):**
- Entities: Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`, `@PrePersist`/`@PreUpdate` timestamps, `BigDecimal` for money, `@Enumerated(EnumType.STRING)` for enums.
- Repositories: `@Repository interface … extends JpaRepository<T, Long>` in `com.household.manager.repository`.
- Services: `@Service @RequiredArgsConstructor @Slf4j`, `@Transactional` on writes, `@Transactional(readOnly = true)` on reads.
- Controllers: `@RestController @RequestMapping("/v1/...") @RequiredArgsConstructor @Slf4j`. Note the context path adds `/api` (existing controllers map `/v1/...`, the public URL is `/api/v1/...`).
- DTOs: `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, Jakarta validation annotations.
- Tests: JUnit 5, AAA pattern, descriptive names. Build with JDK 21 (set `JAVA_HOME` to jdk-21; default is JDK 17). DB-backed integration tests may fail locally without a DB — prefer pure unit tests for logic.

**Build/run commands:**
- Backend build: `cd backend && mvn clean install`
- Backend single test: `cd backend && mvn test -Dtest=ClassName`
- Frontend test: `cd frontend && ng test --watch=false --browsers=ChromeHeadless`

---

## Module 1 — Data Model & Liquibase

### Task 1: BankAccount entity, changeset, repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/BankAccount.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260624-0017-create-bank-accounts-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/repository/BankAccountRepository.java`

- [ ] **Step 1: Create the `BankAccount` entity**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A bank account (one IBAN) whose transactions are tracked.
 */
@Entity
@Table(name = "bank_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable label, e.g. "Girokonto" or "Kreditkarte". */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** IBAN of the account; used to match imported statements. */
    @Column(name = "iban", length = 34)
    private String iban;

    /** ISO 4217 currency code, e.g. "EUR". */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Create the Liquibase changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260624-0017" author="household-manager">
        <comment>Create bank_accounts table</comment>

        <createTable tableName="bank_accounts">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="iban" type="VARCHAR(34)"/>
            <column name="currency" type="VARCHAR(3)">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_bank_accounts_iban" tableName="bank_accounts">
            <column name="iban"/>
        </createIndex>

        <rollback>
            <dropTable tableName="bank_accounts"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Register the changeset in the master changelog**

In `db.changelog-master.xml`, add before the closing `</databaseChangeLog>`:

```xml
    <!-- Finance: Expense Tracking Feature -->
    <include file="db/changelog/changes/20260624-0017-create-bank-accounts-table.xml"/>
```

- [ ] **Step 4: Create the repository**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    Optional<BankAccount> findByIban(String iban);

    boolean existsByIban(String iban);
}
```

- [ ] **Step 5: Compile to verify**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/model/entity/BankAccount.java \
        backend/src/main/resources/db/changelog/changes/20260624-0017-create-bank-accounts-table.xml \
        backend/src/main/resources/db/changelog/db.changelog-master.xml \
        backend/src/main/java/com/household/manager/repository/BankAccountRepository.java
git commit -m "feat(finance): add BankAccount entity, schema and repository"
```

### Task 2: Category entity, enum, changeset, seed, repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/CategoryKind.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/Category.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260624-0018-create-categories-table.xml`
- Create: `backend/src/main/resources/db/changelog/changes/20260624-0019-seed-default-categories.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/repository/CategoryRepository.java`

- [ ] **Step 1: Create the `CategoryKind` enum**

```java
package com.household.manager.model.entity;

/**
 * Whether a category represents money going out, coming in, or an internal transfer.
 */
public enum CategoryKind {
    EXPENSE,
    INCOME,
    TRANSFER
}
```

- [ ] **Step 2: Create the `Category` entity**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A spending/income category. Categories may be system-provided (seeded) or
 * user-defined, and may have a parent to form a shallow hierarchy.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CategoryKind kind;

    /** Hex color for charts, e.g. "#4caf50". */
    @Column(name = "color", length = 7)
    private String color;

    /** True for seeded categories that the app ships with. */
    @Column(name = "is_system", nullable = false)
    private boolean system;

    /** Optional parent category id for sub-categories. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Create the table changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260624-0018" author="household-manager">
        <comment>Create categories table</comment>

        <createTable tableName="categories">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="kind" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="color" type="VARCHAR(7)"/>
            <column name="is_system" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
            <column name="parent_id" type="BIGINT"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <sql>
            ALTER TABLE categories
            ADD CONSTRAINT chk_category_kind
            CHECK (kind IN ('EXPENSE', 'INCOME', 'TRANSFER'))
        </sql>

        <rollback>
            <dropTable tableName="categories"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 4: Create the seed changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260624-0019" author="household-manager">
        <comment>Seed default system categories</comment>

        <insert tableName="categories"><column name="name" value="Lebensmittel"/><column name="kind" value="EXPENSE"/><column name="color" value="#66bb6a"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Wohnen/Miete"/><column name="kind" value="EXPENSE"/><column name="color" value="#8d6e63"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Energie"/><column name="kind" value="EXPENSE"/><column name="color" value="#ffa726"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Mobilität"/><column name="kind" value="EXPENSE"/><column name="color" value="#42a5f5"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Versicherungen"/><column name="kind" value="EXPENSE"/><column name="color" value="#7e57c2"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Abos &amp; Medien"/><column name="kind" value="EXPENSE"/><column name="color" value="#ec407a"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Gesundheit"/><column name="kind" value="EXPENSE"/><column name="color" value="#26a69a"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Freizeit"/><column name="kind" value="EXPENSE"/><column name="color" value="#26c6da"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Shopping"/><column name="kind" value="EXPENSE"/><column name="color" value="#ab47bc"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Restaurant"/><column name="kind" value="EXPENSE"/><column name="color" value="#ff7043"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Bargeld"/><column name="kind" value="EXPENSE"/><column name="color" value="#bdbdbd"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Sonstiges"/><column name="kind" value="EXPENSE"/><column name="color" value="#90a4ae"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Gehalt"/><column name="kind" value="INCOME"/><column name="color" value="#9ccc65"/><column name="is_system" valueBoolean="true"/></insert>
        <insert tableName="categories"><column name="name" value="Erstattung"/><column name="kind" value="INCOME"/><column name="color" value="#d4e157"/><column name="is_system" valueBoolean="true"/></insert>

        <rollback>
            <delete tableName="categories"><where>is_system = true</where></delete>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 5: Register both changesets in the master changelog**

Add after the bank-accounts include:

```xml
    <include file="db/changelog/changes/20260624-0018-create-categories-table.xml"/>
    <include file="db/changelog/changes/20260624-0019-seed-default-categories.xml"/>
```

- [ ] **Step 6: Create the repository**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.Category;
import com.household.manager.model.entity.CategoryKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByKind(CategoryKind kind);

    Optional<Category> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
```

- [ ] **Step 7: Compile to verify**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/model/entity/CategoryKind.java \
        backend/src/main/java/com/household/manager/model/entity/Category.java \
        backend/src/main/resources/db/changelog/changes/20260624-0018-create-categories-table.xml \
        backend/src/main/resources/db/changelog/changes/20260624-0019-seed-default-categories.xml \
        backend/src/main/resources/db/changelog/db.changelog-master.xml \
        backend/src/main/java/com/household/manager/repository/CategoryRepository.java
git commit -m "feat(finance): add Category entity, schema, seed and repository"
```

### Task 3: Transaction entity, changeset, repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/Transaction.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260624-0020-create-transactions-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/repository/TransactionRepository.java`

- [ ] **Step 1: Create the `Transaction` entity**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single booked transaction imported from a bank statement.
 * Amount is signed: negative = expense (debit), positive = income (credit).
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    /** Signed amount: negative = expense, positive = income. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "counterparty_name", length = 255)
    private String counterpartyName;

    @Column(name = "counterparty_iban", length = 34)
    private String counterpartyIban;

    @Column(name = "purpose", columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "end_to_end_id", length = 255)
    private String endToEndId;

    @Column(name = "bank_tx_code", length = 100)
    private String bankTxCode;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "recurring_id")
    private Long recurringId;

    /** True if a user set the category by hand; protects it from auto-rules. */
    @Column(name = "manually_categorized", nullable = false)
    private boolean manuallyCategorized;

    @Column(name = "import_batch_id")
    private Long importBatchId;

    /** Unique fingerprint used to skip duplicate imports. */
    @Column(name = "dedup_hash", nullable = false, length = 64, unique = true)
    private String dedupHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Create the changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260624-0020" author="household-manager">
        <comment>Create transactions table</comment>

        <createTable tableName="transactions">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="account_id" type="BIGINT"><constraints nullable="false"/></column>
            <column name="booking_date" type="DATE"><constraints nullable="false"/></column>
            <column name="value_date" type="DATE"/>
            <column name="amount" type="DECIMAL(15,2)"><constraints nullable="false"/></column>
            <column name="currency" type="VARCHAR(3)"><constraints nullable="false"/></column>
            <column name="counterparty_name" type="VARCHAR(255)"/>
            <column name="counterparty_iban" type="VARCHAR(34)"/>
            <column name="purpose" type="TEXT"/>
            <column name="end_to_end_id" type="VARCHAR(255)"/>
            <column name="bank_tx_code" type="VARCHAR(100)"/>
            <column name="category_id" type="BIGINT"/>
            <column name="recurring_id" type="BIGINT"/>
            <column name="manually_categorized" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
            <column name="import_batch_id" type="BIGINT"/>
            <column name="dedup_hash" type="VARCHAR(64)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_transactions_account_booking" tableName="transactions">
            <column name="account_id"/>
            <column name="booking_date" descending="true"/>
        </createIndex>
        <createIndex indexName="idx_transactions_category" tableName="transactions">
            <column name="category_id"/>
        </createIndex>

        <rollback>
            <dropTable tableName="transactions"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Register the changeset**

```xml
    <include file="db/changelog/changes/20260624-0020-create-transactions-table.xml"/>
```

- [ ] **Step 4: Create the repository**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    boolean existsByDedupHash(String dedupHash);

    List<Transaction> findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(
            Long accountId, LocalDate from, LocalDate to);

    List<Transaction> findByBookingDateBetweenOrderByBookingDateDesc(LocalDate from, LocalDate to);

    List<Transaction> findByCategoryIdIsNull();

    List<Transaction> findByCategoryIdIsNullAndManuallyCategorizedFalse();

    Optional<Transaction> findByDedupHash(String dedupHash);

    /** Sum of expenses (negative amounts) per category in a date range, returned as [categoryId, sum]. */
    @Query("""
            SELECT t.categoryId, SUM(t.amount)
            FROM Transaction t
            WHERE t.bookingDate BETWEEN :from AND :to
              AND (:accountId IS NULL OR t.accountId = :accountId)
            GROUP BY t.categoryId
            """)
    List<Object[]> sumAmountByCategory(@Param("from") LocalDate from,
                                       @Param("to") LocalDate to,
                                       @Param("accountId") Long accountId);

    /** Total of negative amounts (expenses) in range. Returns null if none. */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.amount < 0
              AND t.bookingDate BETWEEN :from AND :to
              AND (:accountId IS NULL OR t.accountId = :accountId)
            """)
    BigDecimal sumExpenses(@Param("from") LocalDate from,
                           @Param("to") LocalDate to,
                           @Param("accountId") Long accountId);

    /** Total of positive amounts (income) in range. Returns 0 if none. */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.amount > 0
              AND t.bookingDate BETWEEN :from AND :to
              AND (:accountId IS NULL OR t.accountId = :accountId)
            """)
    BigDecimal sumIncome(@Param("from") LocalDate from,
                         @Param("to") LocalDate to,
                         @Param("accountId") Long accountId);

    /** Sum of expenses for one category in range (negative number). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.categoryId = :categoryId
              AND t.bookingDate BETWEEN :from AND :to
            """)
    BigDecimal sumByCategory(@Param("categoryId") Long categoryId,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to);
}
```

- [ ] **Step 5: Compile to verify**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/model/entity/Transaction.java \
        backend/src/main/resources/db/changelog/changes/20260624-0020-create-transactions-table.xml \
        backend/src/main/resources/db/changelog/db.changelog-master.xml \
        backend/src/main/java/com/household/manager/repository/TransactionRepository.java
git commit -m "feat(finance): add Transaction entity, schema and repository"
```

### Task 4: CategorizationRule entity, enums, changeset, repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/RuleMatchField.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/RuleMatchType.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/CategorizationRule.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260624-0021-create-categorization-rules-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/repository/CategorizationRuleRepository.java`

- [ ] **Step 1: Create the enums**

```java
package com.household.manager.model.entity;

/** Which transaction field a categorization rule matches against. */
public enum RuleMatchField {
    COUNTERPARTY_NAME,
    COUNTERPARTY_IBAN,
    PURPOSE
}
```

```java
package com.household.manager.model.entity;

/** How a categorization rule compares its pattern to the field value. */
public enum RuleMatchType {
    CONTAINS,
    EQUALS,
    REGEX
}
```

- [ ] **Step 2: Create the `CategorizationRule` entity**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A rule that auto-assigns a category when a transaction field matches a pattern.
 */
@Entity
@Table(name = "categorization_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategorizationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_field", nullable = false, length = 30)
    private RuleMatchField matchField;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private RuleMatchType matchType;

    @Column(name = "pattern", nullable = false, length = 255)
    private String pattern;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** Lower number = evaluated first. */
    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Create the changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260624-0021" author="household-manager">
        <comment>Create categorization_rules table</comment>

        <createTable tableName="categorization_rules">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="match_field" type="VARCHAR(30)"><constraints nullable="false"/></column>
            <column name="match_type" type="VARCHAR(20)"><constraints nullable="false"/></column>
            <column name="pattern" type="VARCHAR(255)"><constraints nullable="false"/></column>
            <column name="category_id" type="BIGINT"><constraints nullable="false"/></column>
            <column name="priority" type="INT" defaultValueNumeric="100">
                <constraints nullable="false"/>
            </column>
            <column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <rollback>
            <dropTable tableName="categorization_rules"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 4: Register the changeset**

```xml
    <include file="db/changelog/changes/20260624-0021-create-categorization-rules-table.xml"/>
```

- [ ] **Step 5: Create the repository**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.CategorizationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, Long> {

    List<CategorizationRule> findByEnabledTrueOrderByPriorityAsc();
}
```

- [ ] **Step 6: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/model/entity/RuleMatchField.java \
        backend/src/main/java/com/household/manager/model/entity/RuleMatchType.java \
        backend/src/main/java/com/household/manager/model/entity/CategorizationRule.java \
        backend/src/main/resources/db/changelog/changes/20260624-0021-create-categorization-rules-table.xml \
        backend/src/main/resources/db/changelog/db.changelog-master.xml \
        backend/src/main/java/com/household/manager/repository/CategorizationRuleRepository.java
git commit -m "feat(finance): add CategorizationRule entity, schema and repository"
```

### Task 5: ImportBatch entity, changeset, repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/ImportBatch.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260624-0022-create-import-batches-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/repository/ImportBatchRepository.java`

- [ ] **Step 1: Create the `ImportBatch` entity**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Record of a single statement import run.
 */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "filename", length = 255)
    private String filename;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @Column(name = "imported_count", nullable = false)
    private int importedCount;

    @Column(name = "skipped_duplicates", nullable = false)
    private int skippedDuplicates;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @PrePersist
    protected void onCreate() {
        if (importedAt == null) {
            importedAt = LocalDateTime.now();
        }
    }
}
```

- [ ] **Step 2: Create the changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260624-0022" author="household-manager">
        <comment>Create import_batches table</comment>

        <createTable tableName="import_batches">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="account_id" type="BIGINT"><constraints nullable="false"/></column>
            <column name="filename" type="VARCHAR(255)"/>
            <column name="imported_at" type="TIMESTAMP"><constraints nullable="false"/></column>
            <column name="imported_count" type="INT" defaultValueNumeric="0"><constraints nullable="false"/></column>
            <column name="skipped_duplicates" type="INT" defaultValueNumeric="0"><constraints nullable="false"/></column>
            <column name="failed_count" type="INT" defaultValueNumeric="0"><constraints nullable="false"/></column>
            <column name="date_from" type="DATE"/>
            <column name="date_to" type="DATE"/>
        </createTable>

        <rollback>
            <dropTable tableName="import_batches"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Register the changeset**

```xml
    <include file="db/changelog/changes/20260624-0022-create-import-batches-table.xml"/>
```

- [ ] **Step 4: Create the repository**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    List<ImportBatch> findByAccountIdOrderByImportedAtDesc(Long accountId);
}
```

- [ ] **Step 5: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/model/entity/ImportBatch.java \
        backend/src/main/resources/db/changelog/changes/20260624-0022-create-import-batches-table.xml \
        backend/src/main/resources/db/changelog/db.changelog-master.xml \
        backend/src/main/java/com/household/manager/repository/ImportBatchRepository.java
git commit -m "feat(finance): add ImportBatch entity, schema and repository"
```

### Task 6: RecurringPayment entity, enum, changeset, repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/RecurrenceInterval.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/RecurringPayment.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260624-0023-create-recurring-payments-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/repository/RecurringPaymentRepository.java`

- [ ] **Step 1: Create the enum**

```java
package com.household.manager.model.entity;

/** Cadence of a recurring payment. */
public enum RecurrenceInterval {
    MONTHLY,
    QUARTERLY,
    YEARLY
}
```

- [ ] **Step 2: Create the `RecurringPayment` entity**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A detected (or confirmed) recurring payment such as rent or a subscription.
 */
@Entity
@Table(name = "recurring_payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Normalized counterparty name the recurrence is grouped by. */
    @Column(name = "counterparty_pattern", nullable = false, length = 255)
    private String counterpartyPattern;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "expected_amount", precision = 15, scale = 2)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "interval_kind", nullable = false, length = 20)
    private RecurrenceInterval interval;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(name = "confirmed", nullable = false)
    private boolean confirmed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Create the changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260624-0023" author="household-manager">
        <comment>Create recurring_payments table</comment>

        <createTable tableName="recurring_payments">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="account_id" type="BIGINT"><constraints nullable="false"/></column>
            <column name="counterparty_pattern" type="VARCHAR(255)"><constraints nullable="false"/></column>
            <column name="category_id" type="BIGINT"/>
            <column name="expected_amount" type="DECIMAL(15,2)"/>
            <column name="interval_kind" type="VARCHAR(20)"><constraints nullable="false"/></column>
            <column name="next_due_date" type="DATE"/>
            <column name="confirmed" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <rollback>
            <dropTable tableName="recurring_payments"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 4: Register the changeset**

```xml
    <include file="db/changelog/changes/20260624-0023-create-recurring-payments-table.xml"/>
```

- [ ] **Step 5: Create the repository**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.RecurringPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RecurringPaymentRepository extends JpaRepository<RecurringPayment, Long> {

    List<RecurringPayment> findByConfirmed(boolean confirmed);

    Optional<RecurringPayment> findByAccountIdAndCounterpartyPatternAndInterval(
            Long accountId, String counterpartyPattern,
            com.household.manager.model.entity.RecurrenceInterval interval);
}
```

- [ ] **Step 6: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/model/entity/RecurrenceInterval.java \
        backend/src/main/java/com/household/manager/model/entity/RecurringPayment.java \
        backend/src/main/resources/db/changelog/changes/20260624-0023-create-recurring-payments-table.xml \
        backend/src/main/resources/db/changelog/db.changelog-master.xml \
        backend/src/main/java/com/household/manager/repository/RecurringPaymentRepository.java
git commit -m "feat(finance): add RecurringPayment entity, schema and repository"
```

### Task 7: Budget entity, changeset, repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/Budget.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260624-0024-create-budgets-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/repository/BudgetRepository.java`

- [ ] **Step 1: Create the `Budget` entity**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A monthly budget. categoryId == null means an overall (all-categories) budget.
 */
@Entity
@Table(name = "budgets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null = overall monthly budget; otherwise the category it limits. */
    @Column(name = "category_id")
    private Long categoryId;

    /** Currently only "MONTHLY" is supported. */
    @Column(name = "period", nullable = false, length = 20)
    private String period;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Create the changeset**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260624-0024" author="household-manager">
        <comment>Create budgets table</comment>

        <createTable tableName="budgets">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="category_id" type="BIGINT"/>
            <column name="period" type="VARCHAR(20)" defaultValue="MONTHLY">
                <constraints nullable="false"/>
            </column>
            <column name="amount" type="DECIMAL(15,2)"><constraints nullable="false"/></column>
            <column name="valid_from" type="DATE"><constraints nullable="false"/></column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <rollback>
            <dropTable tableName="budgets"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Register the changeset**

```xml
    <include file="db/changelog/changes/20260624-0024-create-budgets-table.xml"/>
```

- [ ] **Step 4: Create the repository**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findByCategoryIdNotNull();

    Optional<Budget> findByCategoryIdIsNull();

    Optional<Budget> findByCategoryId(Long categoryId);
}
```

- [ ] **Step 5: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/model/entity/Budget.java \
        backend/src/main/resources/db/changelog/changes/20260624-0024-create-budgets-table.xml \
        backend/src/main/resources/db/changelog/db.changelog-master.xml \
        backend/src/main/java/com/household/manager/repository/BudgetRepository.java
git commit -m "feat(finance): add Budget entity, schema and repository"
```

---

## Module 2 — CAMT Parsing & Import

> **Refinement (surfaced):** The spec said "JAXB from the official XSD". Generating the full
> camt.053.001.08 XSD produces hundreds of classes. Instead we use **JAXB with a focused,
> hand-written model** annotated for exactly the elements we consume. This is still JAXB
> (not DOM/StAX hand-parsing), but far more maintainable and unit-testable. If full
> standards coverage is later required, the model can be swapped for generated classes
> behind the same `CamtStatementParser` interface.

### Task 8: Add JAXB dependencies

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: Add JAXB API + runtime dependencies**

In `backend/pom.xml`, add inside `<dependencies>` (e.g. after the commons-csv dependency):

```xml
        <!-- JAXB for CAMT (camt.053) XML parsing -->
        <dependency>
            <groupId>jakarta.xml.bind</groupId>
            <artifactId>jakarta.xml.bind-api</artifactId>
            <version>4.0.2</version>
        </dependency>
        <dependency>
            <groupId>org.glassfish.jaxb</groupId>
            <artifactId>jaxb-runtime</artifactId>
            <version>4.0.5</version>
        </dependency>
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (dependencies downloaded).

- [ ] **Step 3: Commit**

```bash
git add backend/pom.xml
git commit -m "build(finance): add JAXB dependencies for CAMT parsing"
```

### Task 9: JAXB model for camt.053.001.08 (focused subset)

All classes go in package `com.household.manager.finance.camt`. The namespace is applied
once via `package-info.java`.

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/camt/package-info.java`
- Create: `backend/src/main/java/com/household/manager/finance/camt/CamtDocument.java`
- Create: `backend/src/main/java/com/household/manager/finance/camt/CamtModel.java`

- [ ] **Step 1: Create `package-info.java` (sets the XML namespace for all classes)**

```java
@jakarta.xml.bind.annotation.XmlSchema(
        namespace = "urn:iso:std:iso:20022:tech:xsd:camt.053.001.08",
        elementFormDefault = jakarta.xml.bind.annotation.XmlNsForm.QUALIFIED)
package com.household.manager.finance.camt;
```

- [ ] **Step 2: Create the root document class**

```java
package com.household.manager.finance.camt;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Root &lt;Document&gt; of a camt.053.001.08 bank-to-customer statement.
 */
@XmlRootElement(name = "Document")
@XmlAccessorType(XmlAccessType.FIELD)
public class CamtDocument {

    @XmlElement(name = "BkToCstmrStmt")
    private BankToCustomerStatement bkToCstmrStmt;

    public BankToCustomerStatement getBkToCstmrStmt() {
        return bkToCstmrStmt;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class BankToCustomerStatement {
        @XmlElement(name = "Stmt")
        private List<CamtModel.Statement> statements = new ArrayList<>();

        public List<CamtModel.Statement> getStatements() {
            return statements;
        }
    }
}
```

- [ ] **Step 3: Create the nested model classes**

```java
package com.household.manager.finance.camt;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Focused JAXB model for the camt.053 elements this application consumes.
 * Anything not listed here is simply ignored during unmarshalling.
 */
public final class CamtModel {

    private CamtModel() {
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Statement {
        @XmlElement(name = "Acct")
        private Account account;
        @XmlElement(name = "Ntry")
        private List<Entry> entries = new ArrayList<>();

        public Account getAccount() {
            return account;
        }

        public List<Entry> getEntries() {
            return entries;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Account {
        @XmlElement(name = "Id")
        private AccountId id;
        @XmlElement(name = "Ccy")
        private String currency;

        public String getIban() {
            return id != null ? id.iban : null;
        }

        public String getCurrency() {
            return currency;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class AccountId {
        @XmlElement(name = "IBAN")
        private String iban;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Entry {
        @XmlElement(name = "Amt")
        private Amount amount;
        @XmlElement(name = "CdtDbtInd")
        private String creditDebitIndicator; // "CRDT" or "DBIT"
        @XmlElement(name = "BookgDt")
        private DateChoice bookingDate;
        @XmlElement(name = "ValDt")
        private DateChoice valueDate;
        @XmlElement(name = "AcctSvcrRef")
        private String accountServicerReference;
        @XmlElement(name = "BkTxCd")
        private BankTransactionCode bankTransactionCode;
        @XmlElement(name = "NtryDtls")
        private EntryDetails entryDetails;

        public Amount getAmount() {
            return amount;
        }

        public boolean isDebit() {
            return "DBIT".equalsIgnoreCase(creditDebitIndicator);
        }

        public DateChoice getBookingDate() {
            return bookingDate;
        }

        public DateChoice getValueDate() {
            return valueDate;
        }

        public String getAccountServicerReference() {
            return accountServicerReference;
        }

        public BankTransactionCode getBankTransactionCode() {
            return bankTransactionCode;
        }

        public EntryDetails getEntryDetails() {
            return entryDetails;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Amount {
        @XmlValue
        private BigDecimal value;
        @XmlAttribute(name = "Ccy")
        private String currency;

        public BigDecimal getValue() {
            return value;
        }

        public String getCurrency() {
            return currency;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class DateChoice {
        @XmlElement(name = "Dt")
        private String date;      // yyyy-MM-dd
        @XmlElement(name = "DtTm")
        private String dateTime;  // ISO date-time

        /** Returns the date part, preferring Dt, falling back to the date portion of DtTm. */
        public String resolveDate() {
            if (date != null && !date.isBlank()) {
                return date;
            }
            if (dateTime != null && dateTime.length() >= 10) {
                return dateTime.substring(0, 10);
            }
            return null;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class BankTransactionCode {
        @XmlElement(name = "Domn")
        private Domain domain;
        @XmlElement(name = "Prtry")
        private Proprietary proprietary;

        public String resolveCode() {
            if (domain != null && domain.code != null) {
                return domain.code;
            }
            return proprietary != null ? proprietary.code : null;
        }

        @XmlAccessorType(XmlAccessType.FIELD)
        public static class Domain {
            @XmlElement(name = "Cd")
            private String code;
        }

        @XmlAccessorType(XmlAccessType.FIELD)
        public static class Proprietary {
            @XmlElement(name = "Cd")
            private String code;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class EntryDetails {
        @XmlElement(name = "TxDtls")
        private List<TransactionDetails> transactionDetails = new ArrayList<>();

        public List<TransactionDetails> getTransactionDetails() {
            return transactionDetails;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class TransactionDetails {
        @XmlElement(name = "Refs")
        private References references;
        @XmlElement(name = "RltdPties")
        private RelatedParties relatedParties;
        @XmlElement(name = "RmtInf")
        private RemittanceInfo remittanceInfo;

        public String getEndToEndId() {
            return references != null ? references.endToEndId : null;
        }

        public RelatedParties getRelatedParties() {
            return relatedParties;
        }

        public String getRemittanceText() {
            return remittanceInfo != null ? remittanceInfo.joined() : null;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class References {
        @XmlElement(name = "EndToEndId")
        private String endToEndId;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class RelatedParties {
        @XmlElement(name = "Cdtr")
        private Party creditor;
        @XmlElement(name = "CdtrAcct")
        private PartyAccount creditorAccount;
        @XmlElement(name = "Dbtr")
        private Party debtor;
        @XmlElement(name = "DbtrAcct")
        private PartyAccount debtorAccount;

        /** The counterparty name depends on direction: creditor for debits, debtor for credits. */
        public String counterpartyName(boolean debit) {
            Party p = debit ? creditor : debtor;
            return p != null ? p.name : null;
        }

        public String counterpartyIban(boolean debit) {
            PartyAccount a = debit ? creditorAccount : debtorAccount;
            return a != null ? a.iban() : null;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Party {
        @XmlElement(name = "Nm")
        private String name;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class PartyAccount {
        @XmlElement(name = "Id")
        private AccountId id;

        public String iban() {
            return id != null ? id.iban : null;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class RemittanceInfo {
        @XmlElement(name = "Ustrd")
        private List<String> unstructured = new ArrayList<>();

        public String joined() {
            return unstructured.isEmpty() ? null : String.join(" ", unstructured);
        }
    }
}
```

- [ ] **Step 4: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/finance/camt/
git commit -m "feat(finance): add focused JAXB model for camt.053 statements"
```

### Task 10: ParsedTransaction DTOs + CamtStatementParser (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/ParsedStatement.java`
- Create: `backend/src/main/java/com/household/manager/finance/ParsedTransaction.java`
- Create: `backend/src/main/java/com/household/manager/finance/CamtParseException.java`
- Create: `backend/src/main/java/com/household/manager/finance/CamtStatementParser.java`
- Create: `backend/src/test/resources/camt/sample-camt053.xml`
- Create: `backend/src/test/java/com/household/manager/finance/CamtStatementParserTest.java`

- [ ] **Step 1: Create the parsed-result DTOs and exception**

```java
package com.household.manager.finance;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Result of parsing one camt.053 statement: account IBAN/currency plus its transactions. */
@Data
@Builder
public class ParsedStatement {
    private final String accountIban;
    private final String currency;
    private final List<ParsedTransaction> transactions;
}
```

```java
package com.household.manager.finance;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A single transaction extracted from a camt.053 entry. Amount is signed (negative = debit). */
@Data
@Builder
public class ParsedTransaction {
    private final LocalDate bookingDate;
    private final LocalDate valueDate;
    private final BigDecimal amount;
    private final String currency;
    private final String counterpartyName;
    private final String counterpartyIban;
    private final String purpose;
    private final String endToEndId;
    private final String accountServicerReference;
    private final String bankTxCode;
}
```

```java
package com.household.manager.finance;

/** Thrown when a CAMT document cannot be parsed (not CAMT, malformed XML, etc.). */
public class CamtParseException extends RuntimeException {
    public CamtParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public CamtParseException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Write the failing parser test with a sample CAMT file**

Create `backend/src/test/resources/camt/sample-camt053.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
  <BkToCstmrStmt>
    <Stmt>
      <Acct>
        <Id><IBAN>DE00111122223333444455</IBAN></Id>
        <Ccy>EUR</Ccy>
      </Acct>
      <Ntry>
        <Amt Ccy="EUR">29.99</Amt>
        <CdtDbtInd>DBIT</CdtDbtInd>
        <BookgDt><Dt>2026-06-01</Dt></BookgDt>
        <ValDt><Dt>2026-06-02</Dt></ValDt>
        <AcctSvcrRef>REF-0001</AcctSvcrRef>
        <BkTxCd><Domn><Cd>PMNT</Cd></Domn></BkTxCd>
        <NtryDtls>
          <TxDtls>
            <Refs><EndToEndId>E2E-NETFLIX-06</EndToEndId></Refs>
            <RltdPties>
              <Cdtr><Nm>NETFLIX INTERNATIONAL</Nm></Cdtr>
              <CdtrAcct><Id><IBAN>NL00NETFLIX0000001</IBAN></Id></CdtrAcct>
            </RltdPties>
            <RmtInf><Ustrd>Netflix Abo Juni</Ustrd></RmtInf>
          </TxDtls>
        </NtryDtls>
      </Ntry>
      <Ntry>
        <Amt Ccy="EUR">2500.00</Amt>
        <CdtDbtInd>CRDT</CdtDbtInd>
        <BookgDt><Dt>2026-06-01</Dt></BookgDt>
        <NtryDtls>
          <TxDtls>
            <RltdPties>
              <Dbtr><Nm>ARBEITGEBER GMBH</Nm></Dbtr>
              <DbtrAcct><Id><IBAN>DE00EMPLOYER000001</IBAN></Id></DbtrAcct>
            </RltdPties>
            <RmtInf><Ustrd>Gehalt Juni 2026</Ustrd></RmtInf>
          </TxDtls>
        </NtryDtls>
      </Ntry>
    </Stmt>
  </BkToCstmrStmt>
</Document>
```

Create `backend/src/test/java/com/household/manager/finance/CamtStatementParserTest.java`:

```java
package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CamtStatementParserTest {

    private final CamtStatementParser parser = new CamtStatementParser();

    private ParsedStatement parseSample() {
        InputStream in = getClass().getResourceAsStream("/camt/sample-camt053.xml");
        assertNotNull(in, "sample file must be on the test classpath");
        return parser.parse(in);
    }

    @Test
    void parsesAccountIbanAndCurrency() {
        ParsedStatement stmt = parseSample();
        assertEquals("DE00111122223333444455", stmt.getAccountIban());
        assertEquals("EUR", stmt.getCurrency());
    }

    @Test
    void debitEntryBecomesNegativeAmountWithCreditorAsCounterparty() {
        List<ParsedTransaction> tx = parseSample().getTransactions();
        ParsedTransaction netflix = tx.get(0);
        assertEquals(0, new BigDecimal("-29.99").compareTo(netflix.getAmount()));
        assertEquals("NETFLIX INTERNATIONAL", netflix.getCounterpartyName());
        assertEquals("NL00NETFLIX0000001", netflix.getCounterpartyIban());
        assertEquals(LocalDate.of(2026, 6, 1), netflix.getBookingDate());
        assertEquals(LocalDate.of(2026, 6, 2), netflix.getValueDate());
        assertEquals("Netflix Abo Juni", netflix.getPurpose());
        assertEquals("E2E-NETFLIX-06", netflix.getEndToEndId());
        assertEquals("REF-0001", netflix.getAccountServicerReference());
        assertEquals("PMNT", netflix.getBankTxCode());
    }

    @Test
    void creditEntryBecomesPositiveAmountWithDebtorAsCounterparty() {
        ParsedTransaction salary = parseSample().getTransactions().get(1);
        assertEquals(0, new BigDecimal("2500.00").compareTo(salary.getAmount()));
        assertEquals("ARBEITGEBER GMBH", salary.getCounterpartyName());
    }

    @Test
    void invalidXmlThrowsCamtParseException() {
        String notCamt = "<foo>bar</foo>";
        assertThrows(CamtParseException.class,
                () -> parser.parse(new java.io.ByteArrayInputStream(notCamt.getBytes())));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && mvn test -Dtest=CamtStatementParserTest`
Expected: COMPILE FAILURE / FAIL — `CamtStatementParser` does not exist yet.

- [ ] **Step 4: Implement `CamtStatementParser`**

```java
package com.household.manager.finance;

import com.household.manager.finance.camt.CamtDocument;
import com.household.manager.finance.camt.CamtModel;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses camt.053.001.08 statements into plain {@link ParsedStatement} DTOs.
 * Pure (no DB); accepts a stream so it is trivially unit-testable.
 */
@Component
@Slf4j
public class CamtStatementParser {

    public ParsedStatement parse(InputStream xml) {
        CamtDocument document = unmarshal(xml);

        if (document.getBkToCstmrStmt() == null
                || document.getBkToCstmrStmt().getStatements().isEmpty()) {
            throw new CamtParseException("No statement (Stmt) found — not a camt.053 document");
        }

        // We support single-statement files (the common bank export). Merge entries if multiple.
        String accountIban = null;
        String currency = null;
        List<ParsedTransaction> transactions = new ArrayList<>();

        for (CamtModel.Statement stmt : document.getBkToCstmrStmt().getStatements()) {
            if (stmt.getAccount() != null) {
                if (accountIban == null) {
                    accountIban = stmt.getAccount().getIban();
                }
                if (currency == null) {
                    currency = stmt.getAccount().getCurrency();
                }
            }
            for (CamtModel.Entry entry : stmt.getEntries()) {
                ParsedTransaction tx = toTransaction(entry, currency);
                if (tx != null) {
                    transactions.add(tx);
                }
            }
        }

        return ParsedStatement.builder()
                .accountIban(accountIban)
                .currency(currency)
                .transactions(transactions)
                .build();
    }

    private CamtDocument unmarshal(InputStream xml) {
        try {
            JAXBContext context = JAXBContext.newInstance(CamtDocument.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            Object result = unmarshaller.unmarshal(xml);
            if (!(result instanceof CamtDocument doc)) {
                throw new CamtParseException("Root element is not a camt.053 Document");
            }
            return doc;
        } catch (JAXBException e) {
            throw new CamtParseException("Failed to parse CAMT XML", e);
        }
    }

    /** Maps one entry to a ParsedTransaction; returns null if essential data is missing. */
    private ParsedTransaction toTransaction(CamtModel.Entry entry, String stmtCurrency) {
        if (entry.getAmount() == null || entry.getAmount().getValue() == null) {
            log.warn("Skipping CAMT entry without amount");
            return null;
        }
        boolean debit = entry.isDebit();
        BigDecimal magnitude = entry.getAmount().getValue().abs();
        BigDecimal signed = debit ? magnitude.negate() : magnitude;

        String currency = entry.getAmount().getCurrency() != null
                ? entry.getAmount().getCurrency() : stmtCurrency;

        LocalDate bookingDate = parseDate(entry.getBookingDate() != null
                ? entry.getBookingDate().resolveDate() : null);
        LocalDate valueDate = parseDate(entry.getValueDate() != null
                ? entry.getValueDate().resolveDate() : null);

        if (bookingDate == null) {
            log.warn("Skipping CAMT entry without booking date");
            return null;
        }

        String counterpartyName = null;
        String counterpartyIban = null;
        String purpose = null;
        String endToEndId = null;

        CamtModel.EntryDetails details = entry.getEntryDetails();
        if (details != null && !details.getTransactionDetails().isEmpty()) {
            CamtModel.TransactionDetails td = details.getTransactionDetails().get(0);
            if (td.getRelatedParties() != null) {
                counterpartyName = td.getRelatedParties().counterpartyName(debit);
                counterpartyIban = td.getRelatedParties().counterpartyIban(debit);
            }
            purpose = td.getRemittanceText();
            endToEndId = td.getEndToEndId();
        }

        String bankTxCode = entry.getBankTransactionCode() != null
                ? entry.getBankTransactionCode().resolveCode() : null;

        return ParsedTransaction.builder()
                .bookingDate(bookingDate)
                .valueDate(valueDate)
                .amount(signed)
                .currency(currency)
                .counterpartyName(counterpartyName)
                .counterpartyIban(counterpartyIban)
                .purpose(purpose)
                .endToEndId(endToEndId)
                .accountServicerReference(entry.getAccountServicerReference())
                .bankTxCode(bankTxCode)
                .build();
    }

    private LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return LocalDate.parse(iso);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && mvn test -Dtest=CamtStatementParserTest`
Expected: PASS (4 tests green).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/finance/ParsedStatement.java \
        backend/src/main/java/com/household/manager/finance/ParsedTransaction.java \
        backend/src/main/java/com/household/manager/finance/CamtParseException.java \
        backend/src/main/java/com/household/manager/finance/CamtStatementParser.java \
        backend/src/test/resources/camt/sample-camt053.xml \
        backend/src/test/java/com/household/manager/finance/CamtStatementParserTest.java
git commit -m "feat(finance): add CAMT statement parser with tests"
```

### Task 11: Dedup hashing (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/DedupHasher.java`
- Create: `backend/src/test/java/com/household/manager/finance/DedupHasherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DedupHasherTest {

    private final DedupHasher hasher = new DedupHasher();

    private ParsedTransaction tx(String e2e, String ref) {
        return ParsedTransaction.builder()
                .bookingDate(LocalDate.of(2026, 6, 1))
                .amount(new BigDecimal("-29.99"))
                .currency("EUR")
                .counterpartyIban("NL00NETFLIX0000001")
                .purpose("Netflix Abo Juni")
                .endToEndId(e2e)
                .accountServicerReference(ref)
                .build();
    }

    @Test
    void usesAccountServicerReferenceWhenPresent() {
        String h = hasher.hash(1L, tx(null, "REF-0001"));
        assertEquals(hasher.hash(1L, tx(null, "REF-0001")), h);
        assertNotEquals(hasher.hash(2L, tx(null, "REF-0001")), h, "different account => different hash");
    }

    @Test
    void fallsBackToCompositeWhenNoReference() {
        String h1 = hasher.hash(1L, tx(null, null));
        String h2 = hasher.hash(1L, tx(null, null));
        assertEquals(h1, h2, "same data must hash identically");
    }

    @Test
    void differentAmountProducesDifferentHash() {
        ParsedTransaction a = tx(null, null);
        ParsedTransaction b = ParsedTransaction.builder()
                .bookingDate(a.getBookingDate()).amount(new BigDecimal("-30.00"))
                .currency("EUR").counterpartyIban(a.getCounterpartyIban())
                .purpose(a.getPurpose()).build();
        assertNotEquals(hasher.hash(1L, a), hasher.hash(1L, b));
    }

    @Test
    void hashIsSha256Hex64Chars() {
        assertEquals(64, hasher.hash(1L, tx(null, "REF-0001")).length());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn test -Dtest=DedupHasherTest`
Expected: COMPILE FAILURE — `DedupHasher` does not exist.

- [ ] **Step 3: Implement `DedupHasher`**

```java
package com.household.manager.finance;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds a stable per-account fingerprint for a transaction so re-imports are idempotent.
 * Prefers the bank's AcctSvcrRef / EndToEndId; otherwise a composite of the core fields.
 */
@Component
public class DedupHasher {

    public String hash(Long accountId, ParsedTransaction tx) {
        String reference = firstNonBlank(tx.getAccountServicerReference(), tx.getEndToEndId());
        String basis = (reference != null)
                ? accountId + "|REF|" + reference
                : String.join("|",
                        String.valueOf(accountId),
                        String.valueOf(tx.getBookingDate()),
                        tx.getAmount().toPlainString(),
                        nullSafe(tx.getCounterpartyIban()),
                        nullSafe(tx.getPurpose()));
        return sha256Hex(basis);
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && mvn test -Dtest=DedupHasherTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/finance/DedupHasher.java \
        backend/src/test/java/com/household/manager/finance/DedupHasherTest.java
git commit -m "feat(finance): add idempotent dedup hashing with tests"
```

---

## Module 3 — Categorization & Import

### Task 12: CounterpartyNameNormalizer (TDD)

Used both for building rule-suggestion patterns and for grouping recurring payments.

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/CounterpartyNameNormalizer.java`
- Create: `backend/src/test/java/com/household/manager/finance/CounterpartyNameNormalizerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterpartyNameNormalizerTest {

    private final CounterpartyNameNormalizer normalizer = new CounterpartyNameNormalizer();

    @Test
    void upperCasesAndTrims() {
        assertEquals("NETFLIX", normalizer.normalize("  Netflix  "));
    }

    @Test
    void collapsesWhitespace() {
        assertEquals("REWE MARKT", normalizer.normalize("REWE    Markt"));
    }

    @Test
    void stripsTrailingDigitGroupsAndDates() {
        assertEquals("REWE SAGT DANKE", normalizer.normalize("REWE SAGT DANKE 1234567 01.06.2026"));
    }

    @Test
    void returnsEmptyForNull() {
        assertEquals("", normalizer.normalize(null));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn test -Dtest=CounterpartyNameNormalizerTest`
Expected: COMPILE FAILURE — class missing.

- [ ] **Step 3: Implement the normalizer**

```java
package com.household.manager.finance;

import org.springframework.stereotype.Component;

/**
 * Normalizes counterparty names so the same merchant maps to a stable token:
 * upper-cases, collapses whitespace, and strips trailing reference/date noise.
 */
@Component
public class CounterpartyNameNormalizer {

    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toUpperCase();
        value = value.replaceAll("\\s+", " ");
        // Remove trailing groups of long digit runs and dotted dates (booking noise).
        value = value.replaceAll("(\\s+(\\d{2}\\.\\d{2}\\.\\d{2,4}|\\d{5,}))+$", "");
        return value.trim();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn test -Dtest=CounterpartyNameNormalizerTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/finance/CounterpartyNameNormalizer.java \
        backend/src/test/java/com/household/manager/finance/CounterpartyNameNormalizerTest.java
git commit -m "feat(finance): add counterparty name normalizer with tests"
```

### Task 13: RuleMatcher (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/RuleMatcher.java`
- Create: `backend/src/test/java/com/household/manager/finance/RuleMatcherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.household.manager.finance;

import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import com.household.manager.model.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleMatcherTest {

    private final RuleMatcher matcher = new RuleMatcher();

    private Transaction tx(String name, String iban, String purpose) {
        return Transaction.builder()
                .counterpartyName(name).counterpartyIban(iban).purpose(purpose)
                .build();
    }

    private CategorizationRule rule(RuleMatchField field, RuleMatchType type, String pattern) {
        return CategorizationRule.builder()
                .matchField(field).matchType(type).pattern(pattern)
                .categoryId(5L).enabled(true).priority(100).build();
    }

    @Test
    void containsIsCaseInsensitiveOnName() {
        assertTrue(matcher.matches(
                rule(RuleMatchField.COUNTERPARTY_NAME, RuleMatchType.CONTAINS, "netflix"),
                tx("NETFLIX INTERNATIONAL", null, null)));
    }

    @Test
    void equalsRequiresFullMatchIgnoringCase() {
        assertTrue(matcher.matches(
                rule(RuleMatchField.COUNTERPARTY_IBAN, RuleMatchType.EQUALS, "de123"),
                tx(null, "DE123", null)));
        assertFalse(matcher.matches(
                rule(RuleMatchField.COUNTERPARTY_IBAN, RuleMatchType.EQUALS, "de123"),
                tx(null, "DE1234", null)));
    }

    @Test
    void regexMatchesPurpose() {
        assertTrue(matcher.matches(
                rule(RuleMatchField.PURPOSE, RuleMatchType.REGEX, ".*Abo.*"),
                tx(null, null, "Netflix Abo Juni")));
    }

    @Test
    void nullFieldValueNeverMatches() {
        assertFalse(matcher.matches(
                rule(RuleMatchField.COUNTERPARTY_NAME, RuleMatchType.CONTAINS, "x"),
                tx(null, null, null)));
    }

    @Test
    void invalidRegexDoesNotThrowAndReturnsFalse() {
        assertFalse(matcher.matches(
                rule(RuleMatchField.PURPOSE, RuleMatchType.REGEX, "["),
                tx(null, null, "anything")));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn test -Dtest=RuleMatcherTest`
Expected: COMPILE FAILURE — class missing.

- [ ] **Step 3: Implement `RuleMatcher`**

```java
package com.household.manager.finance;

import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Decides whether a categorization rule applies to a transaction. */
@Component
@Slf4j
public class RuleMatcher {

    public boolean matches(CategorizationRule rule, Transaction tx) {
        String value = fieldValue(rule, tx);
        if (value == null) {
            return false;
        }
        String haystack = value.toLowerCase();
        String needle = rule.getPattern() == null ? "" : rule.getPattern().toLowerCase();

        return switch (rule.getMatchType()) {
            case CONTAINS -> haystack.contains(needle);
            case EQUALS -> haystack.equals(needle);
            case REGEX -> regexMatches(rule.getPattern(), value);
        };
    }

    private String fieldValue(CategorizationRule rule, Transaction tx) {
        return switch (rule.getMatchField()) {
            case COUNTERPARTY_NAME -> tx.getCounterpartyName();
            case COUNTERPARTY_IBAN -> tx.getCounterpartyIban();
            case PURPOSE -> tx.getPurpose();
        };
    }

    private boolean regexMatches(String pattern, String value) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(value).matches();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex in categorization rule: {}", pattern);
            return false;
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && mvn test -Dtest=RuleMatcherTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/finance/RuleMatcher.java \
        backend/src/test/java/com/household/manager/finance/RuleMatcherTest.java
git commit -m "feat(finance): add rule matcher with tests"
```

### Task 14: CategorizationService (TDD)

Applies rules during import and on demand, and builds a rule suggestion after manual edits.

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/RuleSuggestion.java`
- Create: `backend/src/main/java/com/household/manager/service/CategorizationService.java`
- Create: `backend/src/test/java/com/household/manager/service/CategorizationServiceTest.java`

- [ ] **Step 1: Create the `RuleSuggestion` DTO**

```java
package com.household.manager.dto;

import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import lombok.Builder;
import lombok.Data;

/** A proposed categorization rule derived from a manual category change. */
@Data
@Builder
public class RuleSuggestion {
    private final RuleMatchField field;
    private final RuleMatchType matchType;
    private final String pattern;
    private final Long categoryId;
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.household.manager.service;

import com.household.manager.dto.RuleSuggestion;
import com.household.manager.finance.CounterpartyNameNormalizer;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.model.entity.*;
import com.household.manager.repository.CategorizationRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

class CategorizationServiceTest {

    private CategorizationRuleRepository ruleRepository;
    private CategorizationService service;

    @BeforeEach
    void setUp() {
        ruleRepository = Mockito.mock(CategorizationRuleRepository.class);
        service = new CategorizationService(ruleRepository, new RuleMatcher(), new CounterpartyNameNormalizer());
    }

    private CategorizationRule rule(int priority, String pattern, long categoryId) {
        return CategorizationRule.builder()
                .matchField(RuleMatchField.COUNTERPARTY_NAME).matchType(RuleMatchType.CONTAINS)
                .pattern(pattern).categoryId(categoryId).priority(priority).enabled(true).build();
    }

    @Test
    void firstMatchingRuleByPriorityWins() {
        List<CategorizationRule> rules = List.of(rule(10, "netflix", 5L), rule(20, "net", 9L));
        Transaction tx = Transaction.builder().counterpartyName("NETFLIX").build();
        assertEquals(5L, service.findCategory(tx, rules));
    }

    @Test
    void returnsNullWhenNoRuleMatches() {
        Transaction tx = Transaction.builder().counterpartyName("ALDI").build();
        assertNull(service.findCategory(tx, List.of(rule(10, "netflix", 5L))));
    }

    @Test
    void suggestRuleUsesNormalizedCounterpartyName() {
        Transaction tx = Transaction.builder().counterpartyName("Netflix 12345 01.06.2026").build();
        RuleSuggestion suggestion = service.suggestRule(tx, 5L);
        assertEquals(RuleMatchField.COUNTERPARTY_NAME, suggestion.getField());
        assertEquals(RuleMatchType.CONTAINS, suggestion.getMatchType());
        assertEquals("NETFLIX", suggestion.getPattern());
        assertEquals(5L, suggestion.getCategoryId());
    }

    @Test
    void suggestRuleReturnsNullWhenAlreadyCoveredByEnabledRule() {
        when(ruleRepository.findByEnabledTrueOrderByPriorityAsc())
                .thenReturn(List.of(rule(10, "netflix", 5L)));
        Transaction tx = Transaction.builder().counterpartyName("NETFLIX").build();
        assertNull(service.suggestRule(tx, 5L),
                "no suggestion when an existing rule already assigns this category");
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd backend && mvn test -Dtest=CategorizationServiceTest`
Expected: COMPILE FAILURE — `CategorizationService` missing.

- [ ] **Step 4: Implement `CategorizationService`**

```java
package com.household.manager.service;

import com.household.manager.dto.RuleSuggestion;
import com.household.manager.finance.CounterpartyNameNormalizer;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.CategorizationRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Applies categorization rules and proposes new rules after manual corrections.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategorizationService {

    private final CategorizationRuleRepository ruleRepository;
    private final RuleMatcher ruleMatcher;
    private final CounterpartyNameNormalizer normalizer;

    /** Returns the category id of the first matching rule (by priority), or null. */
    public Long findCategory(Transaction tx, List<CategorizationRule> rulesByPriority) {
        for (CategorizationRule rule : rulesByPriority) {
            if (ruleMatcher.matches(rule, tx)) {
                return rule.getCategoryId();
            }
        }
        return null;
    }

    /** Loads enabled rules once (callers use this before a batch). */
    @Transactional(readOnly = true)
    public List<CategorizationRule> loadActiveRules() {
        return ruleRepository.findByEnabledTrueOrderByPriorityAsc();
    }

    /**
     * Build a rule suggestion from a just-corrected transaction, or null if an existing
     * enabled rule would already assign this same category to it.
     */
    @Transactional(readOnly = true)
    public RuleSuggestion suggestRule(Transaction tx, Long categoryId) {
        List<CategorizationRule> active = ruleRepository.findByEnabledTrueOrderByPriorityAsc();
        Long alreadyAssigned = findCategory(tx, active);
        if (alreadyAssigned != null && alreadyAssigned.equals(categoryId)) {
            return null;
        }
        String pattern = normalizer.normalize(tx.getCounterpartyName());
        if (pattern.isBlank()) {
            return null;
        }
        return RuleSuggestion.builder()
                .field(RuleMatchField.COUNTERPARTY_NAME)
                .matchType(RuleMatchType.CONTAINS)
                .pattern(pattern)
                .categoryId(categoryId)
                .build();
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd backend && mvn test -Dtest=CategorizationServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/RuleSuggestion.java \
        backend/src/main/java/com/household/manager/service/CategorizationService.java \
        backend/src/test/java/com/household/manager/service/CategorizationServiceTest.java
git commit -m "feat(finance): add categorization service with rule suggestions and tests"
```

### Task 15: Import DTOs, StatementImportService, controller

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/ImportSummaryResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/StatementImportService.java`
- Create: `backend/src/main/java/com/household/manager/controller/StatementImportController.java`

- [ ] **Step 1: Create the `ImportSummaryResponse` DTO**

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/** Result returned to the client after importing a statement file. */
@Data
@Builder
public class ImportSummaryResponse {
    private final long batchId;
    private final int importedCount;
    private final int skippedDuplicates;
    private final int failedCount;
    private final int uncategorizedCount;
    private final LocalDate dateFrom;
    private final LocalDate dateTo;
}
```

- [ ] **Step 2: Implement `StatementImportService`**

```java
package com.household.manager.service;

import com.household.manager.dto.ImportSummaryResponse;
import com.household.manager.finance.CamtStatementParser;
import com.household.manager.finance.DedupHasher;
import com.household.manager.finance.ParsedStatement;
import com.household.manager.finance.ParsedTransaction;
import com.household.manager.model.entity.BankAccount;
import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.ImportBatch;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.BankAccountRepository;
import com.household.manager.repository.ImportBatchRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Orchestrates a statement import: parse -> dedup -> auto-categorize -> persist + batch log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementImportService {

    private final CamtStatementParser parser;
    private final DedupHasher dedupHasher;
    private final CategorizationService categorizationService;
    private final TransactionRepository transactionRepository;
    private final ImportBatchRepository importBatchRepository;
    private final BankAccountRepository bankAccountRepository;

    @Transactional
    public ImportSummaryResponse importStatement(Long accountId, String filename, InputStream xml) {
        BankAccount account = bankAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account id: " + accountId));

        ParsedStatement statement = parser.parse(xml);
        warnIfIbanMismatch(account, statement);

        List<CategorizationRule> rules = categorizationService.loadActiveRules();

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        int uncategorized = 0;
        LocalDate from = null;
        LocalDate to = null;

        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .accountId(accountId).filename(filename)
                .importedCount(0).skippedDuplicates(0).failedCount(0)
                .build());

        for (ParsedTransaction parsed : statement.getTransactions()) {
            try {
                String hash = dedupHasher.hash(accountId, parsed);
                if (transactionRepository.existsByDedupHash(hash)) {
                    skipped++;
                    continue;
                }
                Transaction tx = toEntity(accountId, parsed, hash, batch.getId());
                Long categoryId = categorizationService.findCategory(tx, rules);
                tx.setCategoryId(categoryId);
                if (categoryId == null) {
                    uncategorized++;
                }
                transactionRepository.save(tx);
                imported++;

                from = min(from, parsed.getBookingDate());
                to = max(to, parsed.getBookingDate());
            } catch (Exception ex) {
                failed++;
                log.warn("Failed to import a transaction entry, skipping it", ex);
            }
        }

        batch.setImportedCount(imported);
        batch.setSkippedDuplicates(skipped);
        batch.setFailedCount(failed);
        batch.setDateFrom(from);
        batch.setDateTo(to);
        importBatchRepository.save(batch);

        log.info("Import finished: {} imported, {} duplicates, {} failed", imported, skipped, failed);

        return ImportSummaryResponse.builder()
                .batchId(batch.getId())
                .importedCount(imported)
                .skippedDuplicates(skipped)
                .failedCount(failed)
                .uncategorizedCount(uncategorized)
                .dateFrom(from)
                .dateTo(to)
                .build();
    }

    private Transaction toEntity(Long accountId, ParsedTransaction p, String hash, Long batchId) {
        return Transaction.builder()
                .accountId(accountId)
                .bookingDate(p.getBookingDate())
                .valueDate(p.getValueDate())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .counterpartyName(p.getCounterpartyName())
                .counterpartyIban(p.getCounterpartyIban())
                .purpose(p.getPurpose())
                .endToEndId(p.getEndToEndId())
                .bankTxCode(p.getBankTxCode())
                .manuallyCategorized(false)
                .importBatchId(batchId)
                .dedupHash(hash)
                .build();
    }

    private void warnIfIbanMismatch(BankAccount account, ParsedStatement statement) {
        if (account.getIban() != null && statement.getAccountIban() != null
                && !account.getIban().equalsIgnoreCase(statement.getAccountIban())) {
            log.warn("Statement IBAN {} differs from account IBAN {}",
                    statement.getAccountIban(), account.getIban());
        }
    }

    private LocalDate min(LocalDate current, LocalDate candidate) {
        if (candidate == null) return current;
        return (current == null || candidate.isBefore(current)) ? candidate : current;
    }

    private LocalDate max(LocalDate current, LocalDate candidate) {
        if (candidate == null) return current;
        return (current == null || candidate.isAfter(current)) ? candidate : current;
    }
}
```

- [ ] **Step 3: Implement the controller**

```java
package com.household.manager.controller;

import com.household.manager.dto.ImportSummaryResponse;
import com.household.manager.finance.CamtParseException;
import com.household.manager.service.StatementImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for importing bank statement files (camt.053).
 * Base URL: /api/v1/finance
 */
@RestController
@RequestMapping("/v1/finance")
@RequiredArgsConstructor
@Slf4j
public class StatementImportController {

    private final StatementImportService importService;

    @PostMapping("/import")
    public ResponseEntity<?> importStatement(
            @RequestParam("accountId") Long accountId,
            @RequestParam("file") MultipartFile file) {
        log.info("CAMT import request for account {}: {}", accountId, file.getOriginalFilename());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Die Datei ist leer.");
        }
        try {
            ImportSummaryResponse summary = importService.importStatement(
                    accountId, file.getOriginalFilename(), file.getInputStream());
            return ResponseEntity.ok(summary);
        } catch (CamtParseException ex) {
            log.warn("CAMT parse failed: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body("Die Datei konnte nicht als CAMT (camt.053) gelesen werden.");
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            log.error("Statement import failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Import fehlgeschlagen.");
        }
    }
}
```

- [ ] **Step 4: Compile and verify the full test suite still builds**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/ImportSummaryResponse.java \
        backend/src/main/java/com/household/manager/service/StatementImportService.java \
        backend/src/main/java/com/household/manager/controller/StatementImportController.java
git commit -m "feat(finance): add statement import service and endpoint"
```

---

## Module 4 — CRUD & Transaction APIs

### Task 16: BankAccount API (DTOs, service, controller)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/BankAccountRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/BankAccountResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/BankAccountService.java`
- Create: `backend/src/main/java/com/household/manager/controller/BankAccountController.java`

- [ ] **Step 1: Create the DTOs**

```java
package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankAccountRequest {
    @NotBlank(message = "Name is required")
    private String name;
    private String iban;
    @NotBlank(message = "Currency is required")
    private String currency;
}
```

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BankAccountResponse {
    private final Long id;
    private final String name;
    private final String iban;
    private final String currency;
}
```

- [ ] **Step 2: Implement `BankAccountService`**

```java
package com.household.manager.service;

import com.household.manager.dto.BankAccountRequest;
import com.household.manager.dto.BankAccountResponse;
import com.household.manager.model.entity.BankAccount;
import com.household.manager.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BankAccountService {

    private final BankAccountRepository repository;

    @Transactional(readOnly = true)
    public List<BankAccountResponse> getAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public BankAccountResponse create(BankAccountRequest request) {
        BankAccount account = repository.save(BankAccount.builder()
                .name(request.getName())
                .iban(request.getIban())
                .currency(request.getCurrency())
                .build());
        log.info("Created bank account {}", account.getId());
        return toResponse(account);
    }

    @Transactional
    public BankAccountResponse update(Long id, BankAccountRequest request) {
        BankAccount account = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account id: " + id));
        account.setName(request.getName());
        account.setIban(request.getIban());
        account.setCurrency(request.getCurrency());
        return toResponse(repository.save(account));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    private BankAccountResponse toResponse(BankAccount a) {
        return BankAccountResponse.builder()
                .id(a.getId()).name(a.getName()).iban(a.getIban()).currency(a.getCurrency())
                .build();
    }
}
```

- [ ] **Step 3: Implement `BankAccountController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.BankAccountRequest;
import com.household.manager.dto.BankAccountResponse;
import com.household.manager.service.BankAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/finance/accounts")
@RequiredArgsConstructor
@Slf4j
public class BankAccountController {

    private final BankAccountService service;

    @GetMapping
    public ResponseEntity<List<BankAccountResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PostMapping
    public ResponseEntity<BankAccountResponse> create(@Valid @RequestBody BankAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BankAccountResponse> update(
            @PathVariable Long id, @Valid @RequestBody BankAccountRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
```

- [ ] **Step 4: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/dto/BankAccountRequest.java \
        backend/src/main/java/com/household/manager/dto/BankAccountResponse.java \
        backend/src/main/java/com/household/manager/service/BankAccountService.java \
        backend/src/main/java/com/household/manager/controller/BankAccountController.java
git commit -m "feat(finance): add bank account CRUD API"
```

### Task 17: Category API (DTOs, service, controller)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/CategoryRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/CategoryResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/CategoryService.java`
- Create: `backend/src/main/java/com/household/manager/controller/CategoryController.java`

- [ ] **Step 1: Create the DTOs**

```java
package com.household.manager.dto;

import com.household.manager.model.entity.CategoryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryRequest {
    @NotBlank(message = "Name is required")
    private String name;
    @NotNull(message = "Kind is required")
    private CategoryKind kind;
    private String color;
    private Long parentId;
}
```

```java
package com.household.manager.dto;

import com.household.manager.model.entity.CategoryKind;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryResponse {
    private final Long id;
    private final String name;
    private final CategoryKind kind;
    private final String color;
    private final boolean system;
    private final Long parentId;
}
```

- [ ] **Step 2: Implement `CategoryService`**

```java
package com.household.manager.service;

import com.household.manager.dto.CategoryRequest;
import com.household.manager.dto.CategoryResponse;
import com.household.manager.model.entity.Category;
import com.household.manager.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

    private final CategoryRepository repository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        Category category = repository.save(Category.builder()
                .name(request.getName())
                .kind(request.getKind())
                .color(request.getColor())
                .parentId(request.getParentId())
                .system(false)
                .build());
        return toResponse(category);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category id: " + id));
        category.setName(request.getName());
        category.setKind(request.getKind());
        category.setColor(request.getColor());
        category.setParentId(request.getParentId());
        return toResponse(repository.save(category));
    }

    @Transactional
    public void delete(Long id) {
        Category category = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category id: " + id));
        if (category.isSystem()) {
            throw new IllegalArgumentException("System-Kategorien können nicht gelöscht werden.");
        }
        repository.deleteById(id);
    }

    private CategoryResponse toResponse(Category c) {
        return CategoryResponse.builder()
                .id(c.getId()).name(c.getName()).kind(c.getKind())
                .color(c.getColor()).system(c.isSystem()).parentId(c.getParentId())
                .build();
    }
}
```

- [ ] **Step 3: Implement `CategoryController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.CategoryRequest;
import com.household.manager.dto.CategoryResponse;
import com.household.manager.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/finance/categories")
@RequiredArgsConstructor
@Slf4j
public class CategoryController {

    private final CategoryService service;

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> update(
            @PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
```

- [ ] **Step 4: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/dto/CategoryRequest.java \
        backend/src/main/java/com/household/manager/dto/CategoryResponse.java \
        backend/src/main/java/com/household/manager/service/CategoryService.java \
        backend/src/main/java/com/household/manager/controller/CategoryController.java
git commit -m "feat(finance): add category CRUD API"
```

### Task 18: Categorization rule API + backfill (DTOs, service, controller)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/CategorizationRuleRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/CategorizationRuleResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/CategorizationRuleService.java`
- Create: `backend/src/main/java/com/household/manager/controller/CategorizationRuleController.java`

- [ ] **Step 1: Create the DTOs**

```java
package com.household.manager.dto;

import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategorizationRuleRequest {
    @NotNull private RuleMatchField field;
    @NotNull private RuleMatchType matchType;
    @NotBlank private String pattern;
    @NotNull private Long categoryId;
    private Integer priority;
    private Boolean enabled;
    /** When true, apply this rule to existing uncategorized transactions immediately. */
    private boolean applyToExisting;
}
```

```java
package com.household.manager.dto;

import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategorizationRuleResponse {
    private final Long id;
    private final RuleMatchField field;
    private final RuleMatchType matchType;
    private final String pattern;
    private final Long categoryId;
    private final int priority;
    private final boolean enabled;
    private final int appliedToExistingCount;
}
```

- [ ] **Step 2: Implement `CategorizationRuleService`**

```java
package com.household.manager.service;

import com.household.manager.dto.CategorizationRuleRequest;
import com.household.manager.dto.CategorizationRuleResponse;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.CategorizationRuleRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategorizationRuleService {

    private final CategorizationRuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final RuleMatcher ruleMatcher;

    @Transactional(readOnly = true)
    public List<CategorizationRuleResponse> getAll() {
        return ruleRepository.findAll().stream().map(r -> toResponse(r, 0)).toList();
    }

    @Transactional
    public CategorizationRuleResponse create(CategorizationRuleRequest request) {
        CategorizationRule rule = ruleRepository.save(CategorizationRule.builder()
                .matchField(request.getField())
                .matchType(request.getMatchType())
                .pattern(request.getPattern())
                .categoryId(request.getCategoryId())
                .priority(request.getPriority() != null ? request.getPriority() : 100)
                .enabled(request.getEnabled() == null || request.getEnabled())
                .build());

        int applied = 0;
        if (request.isApplyToExisting()) {
            applied = applyRuleToUncategorized(rule);
        }
        return toResponse(rule, applied);
    }

    @Transactional
    public CategorizationRuleResponse update(Long id, CategorizationRuleRequest request) {
        CategorizationRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rule id: " + id));
        rule.setMatchField(request.getField());
        rule.setMatchType(request.getMatchType());
        rule.setPattern(request.getPattern());
        rule.setCategoryId(request.getCategoryId());
        if (request.getPriority() != null) rule.setPriority(request.getPriority());
        if (request.getEnabled() != null) rule.setEnabled(request.getEnabled());
        return toResponse(ruleRepository.save(rule), 0);
    }

    @Transactional
    public void delete(Long id) {
        ruleRepository.deleteById(id);
    }

    /** Apply all enabled rules to transactions that have no category and were not set by hand. */
    @Transactional
    public int applyAllToUncategorized() {
        List<CategorizationRule> rules = ruleRepository.findByEnabledTrueOrderByPriorityAsc();
        List<Transaction> targets =
                transactionRepository.findByCategoryIdIsNullAndManuallyCategorizedFalse();
        int count = 0;
        for (Transaction tx : targets) {
            for (CategorizationRule rule : rules) {
                if (ruleMatcher.matches(rule, tx)) {
                    tx.setCategoryId(rule.getCategoryId());
                    transactionRepository.save(tx);
                    count++;
                    break;
                }
            }
        }
        log.info("Applied rules to {} previously uncategorized transactions", count);
        return count;
    }

    private int applyRuleToUncategorized(CategorizationRule rule) {
        List<Transaction> targets =
                transactionRepository.findByCategoryIdIsNullAndManuallyCategorizedFalse();
        int count = 0;
        for (Transaction tx : targets) {
            if (ruleMatcher.matches(rule, tx)) {
                tx.setCategoryId(rule.getCategoryId());
                transactionRepository.save(tx);
                count++;
            }
        }
        return count;
    }

    private CategorizationRuleResponse toResponse(CategorizationRule r, int applied) {
        return CategorizationRuleResponse.builder()
                .id(r.getId()).field(r.getMatchField()).matchType(r.getMatchType())
                .pattern(r.getPattern()).categoryId(r.getCategoryId())
                .priority(r.getPriority()).enabled(r.isEnabled())
                .appliedToExistingCount(applied)
                .build();
    }
}
```

- [ ] **Step 3: Implement `CategorizationRuleController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.CategorizationRuleRequest;
import com.household.manager.dto.CategorizationRuleResponse;
import com.household.manager.service.CategorizationRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/finance/rules")
@RequiredArgsConstructor
@Slf4j
public class CategorizationRuleController {

    private final CategorizationRuleService service;

    @GetMapping
    public ResponseEntity<List<CategorizationRuleResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PostMapping
    public ResponseEntity<CategorizationRuleResponse> create(
            @Valid @RequestBody CategorizationRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategorizationRuleResponse> update(
            @PathVariable Long id, @Valid @RequestBody CategorizationRuleRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Integer>> applyAll() {
        return ResponseEntity.ok(Map.of("applied", service.applyAllToUncategorized()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
```

- [ ] **Step 4: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/dto/CategorizationRuleRequest.java \
        backend/src/main/java/com/household/manager/dto/CategorizationRuleResponse.java \
        backend/src/main/java/com/household/manager/service/CategorizationRuleService.java \
        backend/src/main/java/com/household/manager/controller/CategorizationRuleController.java
git commit -m "feat(finance): add categorization rule API with backfill"
```

### Task 19: Transaction API — list/filter + manual categorization with suggestion

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/TransactionResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/CategorizeRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/CategorizeResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/TransactionService.java`
- Create: `backend/src/main/java/com/household/manager/controller/TransactionController.java`

- [ ] **Step 1: Create the DTOs**

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class TransactionResponse {
    private final Long id;
    private final Long accountId;
    private final LocalDate bookingDate;
    private final LocalDate valueDate;
    private final BigDecimal amount;
    private final String currency;
    private final String counterpartyName;
    private final String counterpartyIban;
    private final String purpose;
    private final Long categoryId;
    private final boolean manuallyCategorized;
}
```

```java
package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for assigning a category to a transaction by hand. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategorizeRequest {
    @NotNull
    private Long categoryId;
}
```

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

/** Result of a manual categorization: the updated transaction plus an optional rule suggestion. */
@Data
@Builder
public class CategorizeResponse {
    private final TransactionResponse transaction;
    private final RuleSuggestion ruleSuggestion; // null if none is worth suggesting
}
```

- [ ] **Step 2: Implement `TransactionService`**

```java
package com.household.manager.service;

import com.household.manager.dto.CategorizeResponse;
import com.household.manager.dto.RuleSuggestion;
import com.household.manager.dto.TransactionResponse;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;

    /** List transactions in a date range, optionally filtered by account, ordered newest first. */
    @Transactional(readOnly = true)
    public List<TransactionResponse> list(Long accountId, LocalDate from, LocalDate to) {
        List<Transaction> txs = (accountId == null)
                ? transactionRepository.findByBookingDateBetweenOrderByBookingDateDesc(from, to)
                : transactionRepository.findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(
                        accountId, from, to);
        return txs.stream().map(this::toResponse).toList();
    }

    /** Set a category manually and return an optional rule suggestion. */
    @Transactional
    public CategorizeResponse categorize(Long transactionId, Long categoryId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown transaction id: " + transactionId));
        tx.setCategoryId(categoryId);
        tx.setManuallyCategorized(true);
        Transaction saved = transactionRepository.save(tx);

        RuleSuggestion suggestion = categorizationService.suggestRule(saved, categoryId);
        return CategorizeResponse.builder()
                .transaction(toResponse(saved))
                .ruleSuggestion(suggestion)
                .build();
    }

    private TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId()).accountId(t.getAccountId())
                .bookingDate(t.getBookingDate()).valueDate(t.getValueDate())
                .amount(t.getAmount()).currency(t.getCurrency())
                .counterpartyName(t.getCounterpartyName()).counterpartyIban(t.getCounterpartyIban())
                .purpose(t.getPurpose()).categoryId(t.getCategoryId())
                .manuallyCategorized(t.isManuallyCategorized())
                .build();
    }
}
```

- [ ] **Step 3: Implement `TransactionController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.CategorizeRequest;
import com.household.manager.dto.CategorizeResponse;
import com.household.manager.dto.TransactionResponse;
import com.household.manager.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/v1/finance/transactions")
@RequiredArgsConstructor
@Slf4j
public class TransactionController {

    private final TransactionService service;

    @GetMapping
    public ResponseEntity<List<TransactionResponse>> list(
            @RequestParam(required = false) Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.list(accountId, from, to));
    }

    @PatchMapping("/{id}/category")
    public ResponseEntity<CategorizeResponse> categorize(
            @PathVariable Long id, @Valid @RequestBody CategorizeRequest request) {
        return ResponseEntity.ok(service.categorize(id, request.getCategoryId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
```

- [ ] **Step 4: Add a service test for the suggestion flow**

Create `backend/src/test/java/com/household/manager/service/TransactionServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.CategorizeResponse;
import com.household.manager.dto.RuleSuggestion;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TransactionServiceTest {

    private TransactionRepository transactionRepository;
    private CategorizationService categorizationService;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionRepository = Mockito.mock(TransactionRepository.class);
        categorizationService = Mockito.mock(CategorizationService.class);
        service = new TransactionService(transactionRepository, categorizationService);
    }

    @Test
    void categorizeMarksManualAndReturnsSuggestion() {
        Transaction tx = Transaction.builder().id(1L).counterpartyName("NETFLIX").build();
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(categorizationService.suggestRule(any(Transaction.class), Mockito.eq(5L)))
                .thenReturn(RuleSuggestion.builder()
                        .field(RuleMatchField.COUNTERPARTY_NAME).pattern("NETFLIX").categoryId(5L).build());

        CategorizeResponse response = service.categorize(1L, 5L);

        assertTrue(response.getTransaction().isManuallyCategorized());
        assertEquals(5L, response.getTransaction().getCategoryId());
        assertNotNull(response.getRuleSuggestion());
        assertEquals("NETFLIX", response.getRuleSuggestion().getPattern());
    }
}
```

- [ ] **Step 5: Run the test, compile, commit**

Run: `cd backend && mvn test -Dtest=TransactionServiceTest` (expect PASS), then:

```bash
git add backend/src/main/java/com/household/manager/dto/TransactionResponse.java \
        backend/src/main/java/com/household/manager/dto/CategorizeRequest.java \
        backend/src/main/java/com/household/manager/dto/CategorizeResponse.java \
        backend/src/main/java/com/household/manager/service/TransactionService.java \
        backend/src/main/java/com/household/manager/controller/TransactionController.java \
        backend/src/test/java/com/household/manager/service/TransactionServiceTest.java
git commit -m "feat(finance): add transaction listing and manual categorization API"
```

---

## Module 5 — Budgets

### Task 20: BudgetEvaluator (pure status logic, TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/BudgetStatus.java`
- Create: `backend/src/main/java/com/household/manager/finance/BudgetEvaluation.java`
- Create: `backend/src/main/java/com/household/manager/finance/BudgetEvaluator.java`
- Create: `backend/src/test/java/com/household/manager/finance/BudgetEvaluatorTest.java`

- [ ] **Step 1: Create the status enum and evaluation record**

```java
package com.household.manager.finance;

/** Traffic-light state of a budget for a period. */
public enum BudgetStatus {
    GREEN,
    YELLOW,
    RED
}
```

```java
package com.household.manager.finance;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** Outcome of comparing spending against a budget limit. */
@Data
@Builder
public class BudgetEvaluation {
    private final BigDecimal limit;
    private final BigDecimal spent;
    private final int percent;
    private final BudgetStatus status;
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetEvaluatorTest {

    private final BudgetEvaluator evaluator = new BudgetEvaluator();

    @Test
    void belowEightyPercentIsGreen() {
        BudgetEvaluation e = evaluator.evaluate(new BigDecimal("100"), new BigDecimal("50"));
        assertEquals(50, e.getPercent());
        assertEquals(BudgetStatus.GREEN, e.getStatus());
    }

    @Test
    void betweenEightyAndHundredIsYellow() {
        assertEquals(BudgetStatus.YELLOW,
                evaluator.evaluate(new BigDecimal("100"), new BigDecimal("90")).getStatus());
        assertEquals(BudgetStatus.YELLOW,
                evaluator.evaluate(new BigDecimal("100"), new BigDecimal("100")).getStatus());
    }

    @Test
    void aboveHundredIsRed() {
        assertEquals(BudgetStatus.RED,
                evaluator.evaluate(new BigDecimal("100"), new BigDecimal("120")).getStatus());
    }

    @Test
    void zeroLimitWithSpendingIsRed() {
        assertEquals(BudgetStatus.RED,
                evaluator.evaluate(BigDecimal.ZERO, new BigDecimal("1")).getStatus());
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd backend && mvn test -Dtest=BudgetEvaluatorTest`
Expected: COMPILE FAILURE — `BudgetEvaluator` missing.

- [ ] **Step 4: Implement `BudgetEvaluator`**

```java
package com.household.manager.finance;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Computes spend-vs-limit percentage and traffic-light status. */
@Component
public class BudgetEvaluator {

    public BudgetEvaluation evaluate(BigDecimal limit, BigDecimal spent) {
        BigDecimal safeLimit = limit == null ? BigDecimal.ZERO : limit;
        BigDecimal safeSpent = spent == null ? BigDecimal.ZERO : spent;

        int percent;
        if (safeLimit.compareTo(BigDecimal.ZERO) <= 0) {
            percent = safeSpent.compareTo(BigDecimal.ZERO) > 0 ? 999 : 0;
        } else {
            percent = safeSpent.multiply(BigDecimal.valueOf(100))
                    .divide(safeLimit, 0, RoundingMode.HALF_UP).intValue();
        }

        BudgetStatus status;
        if (percent > 100) {
            status = BudgetStatus.RED;
        } else if (percent >= 80) {
            status = BudgetStatus.YELLOW;
        } else {
            status = BudgetStatus.GREEN;
        }

        return BudgetEvaluation.builder()
                .limit(safeLimit).spent(safeSpent).percent(percent).status(status)
                .build();
    }
}
```

- [ ] **Step 5: Run to verify it passes, then commit**

Run: `cd backend && mvn test -Dtest=BudgetEvaluatorTest` (expect PASS), then:

```bash
git add backend/src/main/java/com/household/manager/finance/BudgetStatus.java \
        backend/src/main/java/com/household/manager/finance/BudgetEvaluation.java \
        backend/src/main/java/com/household/manager/finance/BudgetEvaluator.java \
        backend/src/test/java/com/household/manager/finance/BudgetEvaluatorTest.java
git commit -m "feat(finance): add budget evaluator with traffic-light status and tests"
```

### Task 21: Budget API (DTOs, service, controller)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/BudgetRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/BudgetResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/BudgetStatusItem.java`
- Create: `backend/src/main/java/com/household/manager/dto/BudgetStatusResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/BudgetService.java`
- Create: `backend/src/main/java/com/household/manager/controller/BudgetController.java`

- [ ] **Step 1: Create the DTOs**

```java
package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** categoryId null = overall budget. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetRequest {
    private Long categoryId;
    @NotNull
    private BigDecimal amount;
}
```

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BudgetResponse {
    private final Long id;
    private final Long categoryId;
    private final String period;
    private final BigDecimal amount;
}
```

```java
package com.household.manager.dto;

import com.household.manager.finance.BudgetStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BudgetStatusItem {
    private final Long categoryId;     // null = overall
    private final String categoryName; // "Gesamt" for overall
    private final BigDecimal limit;
    private final BigDecimal spent;
    private final int percent;
    private final BudgetStatus status;
}
```

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BudgetStatusResponse {
    private final BudgetStatusItem overall;     // null if no overall budget set
    private final List<BudgetStatusItem> categories;
}
```

- [ ] **Step 2: Implement `BudgetService`**

```java
package com.household.manager.service;

import com.household.manager.dto.BudgetRequest;
import com.household.manager.dto.BudgetResponse;
import com.household.manager.dto.BudgetStatusItem;
import com.household.manager.dto.BudgetStatusResponse;
import com.household.manager.finance.BudgetEvaluation;
import com.household.manager.finance.BudgetEvaluator;
import com.household.manager.model.entity.Budget;
import com.household.manager.model.entity.Category;
import com.household.manager.repository.BudgetRepository;
import com.household.manager.repository.CategoryRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages monthly budgets (overall and per-category) and computes their status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetEvaluator evaluator;

    @Transactional(readOnly = true)
    public List<BudgetResponse> getAll() {
        return budgetRepository.findAll().stream().map(this::toResponse).toList();
    }

    /** Upsert by scope: one overall budget, one per category. */
    @Transactional
    public BudgetResponse save(BudgetRequest request) {
        Optional<Budget> existing = (request.getCategoryId() == null)
                ? budgetRepository.findByCategoryIdIsNull()
                : budgetRepository.findByCategoryId(request.getCategoryId());

        Budget budget = existing.orElseGet(() -> Budget.builder()
                .categoryId(request.getCategoryId())
                .period("MONTHLY")
                .validFrom(LocalDate.now())
                .build());
        budget.setAmount(request.getAmount());
        return toResponse(budgetRepository.save(budget));
    }

    @Transactional
    public void delete(Long id) {
        budgetRepository.deleteById(id);
    }

    /** Compute the status of all budgets for the given month. */
    @Transactional(readOnly = true)
    public BudgetStatusResponse getStatus(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        BudgetStatusItem overall = budgetRepository.findByCategoryIdIsNull()
                .map(b -> {
                    BigDecimal spent = transactionRepository.sumExpenses(from, to, null).abs();
                    BudgetEvaluation e = evaluator.evaluate(b.getAmount(), spent);
                    return item(null, "Gesamt", e);
                })
                .orElse(null);

        List<BudgetStatusItem> categories = new ArrayList<>();
        for (Budget b : budgetRepository.findByCategoryIdNotNull()) {
            BigDecimal spent = transactionRepository
                    .sumByCategory(b.getCategoryId(), from, to).abs();
            BudgetEvaluation e = evaluator.evaluate(b.getAmount(), spent);
            String name = categoryRepository.findById(b.getCategoryId())
                    .map(Category::getName).orElse("?");
            categories.add(item(b.getCategoryId(), name, e));
        }

        return BudgetStatusResponse.builder().overall(overall).categories(categories).build();
    }

    private BudgetStatusItem item(Long categoryId, String name, BudgetEvaluation e) {
        return BudgetStatusItem.builder()
                .categoryId(categoryId).categoryName(name)
                .limit(e.getLimit()).spent(e.getSpent())
                .percent(e.getPercent()).status(e.getStatus())
                .build();
    }

    private BudgetResponse toResponse(Budget b) {
        return BudgetResponse.builder()
                .id(b.getId()).categoryId(b.getCategoryId())
                .period(b.getPeriod()).amount(b.getAmount())
                .build();
    }
}
```

- [ ] **Step 3: Implement `BudgetController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.BudgetRequest;
import com.household.manager.dto.BudgetResponse;
import com.household.manager.dto.BudgetStatusResponse;
import com.household.manager.service.BudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/v1/finance/budgets")
@RequiredArgsConstructor
@Slf4j
public class BudgetController {

    private final BudgetService service;

    @GetMapping
    public ResponseEntity<List<BudgetResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PostMapping
    public ResponseEntity<BudgetResponse> save(@Valid @RequestBody BudgetRequest request) {
        return ResponseEntity.ok(service.save(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** month format: yyyy-MM, e.g. 2026-06 */
    @GetMapping("/status")
    public ResponseEntity<BudgetStatusResponse> status(@RequestParam String month) {
        return ResponseEntity.ok(service.getStatus(YearMonth.parse(month)));
    }
}
```

- [ ] **Step 4: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/dto/BudgetRequest.java \
        backend/src/main/java/com/household/manager/dto/BudgetResponse.java \
        backend/src/main/java/com/household/manager/dto/BudgetStatusItem.java \
        backend/src/main/java/com/household/manager/dto/BudgetStatusResponse.java \
        backend/src/main/java/com/household/manager/service/BudgetService.java \
        backend/src/main/java/com/household/manager/controller/BudgetController.java
git commit -m "feat(finance): add budget API with monthly status"
```

---

## Module 6 — Analytics

### Task 22: Analytics API (DTOs, service, controller)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/CategorySpendItem.java`
- Create: `backend/src/main/java/com/household/manager/dto/OverviewResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/TrendPoint.java`
- Create: `backend/src/main/java/com/household/manager/service/AnalyticsService.java`
- Create: `backend/src/main/java/com/household/manager/controller/AnalyticsController.java`

- [ ] **Step 1: Create the DTOs**

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CategorySpendItem {
    private final Long categoryId;   // null = uncategorized
    private final String categoryName;
    private final String color;
    private final BigDecimal amount; // positive magnitude of expenses
}
```

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OverviewResponse {
    private final String month;            // yyyy-MM
    private final BigDecimal totalExpenses; // positive magnitude
    private final BigDecimal totalIncome;
    private final BigDecimal balance;       // income - expenses
    private final BudgetStatusResponse budget;
    private final List<CategorySpendItem> categories;
}
```

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class TrendPoint {
    private final String month;     // yyyy-MM
    private final BigDecimal expenses; // positive magnitude
    private final BigDecimal income;
}
```

- [ ] **Step 2: Implement `AnalyticsService`**

```java
package com.household.manager.service;

import com.household.manager.dto.CategorySpendItem;
import com.household.manager.dto.OverviewResponse;
import com.household.manager.dto.TrendPoint;
import com.household.manager.model.entity.Category;
import com.household.manager.repository.CategoryRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only aggregation for the overview KPIs, category breakdown and trends.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetService budgetService;

    @Transactional(readOnly = true)
    public OverviewResponse overview(YearMonth month, Long accountId) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        BigDecimal expenses = transactionRepository.sumExpenses(from, to, accountId).abs();
        BigDecimal income = transactionRepository.sumIncome(from, to, accountId);

        Map<Long, String> categoryNames = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> categoryColors = categoryRepository.findAll().stream()
                .filter(c -> c.getColor() != null)
                .collect(Collectors.toMap(Category::getId, Category::getColor));

        List<CategorySpendItem> categories = new ArrayList<>();
        for (Object[] row : transactionRepository.sumAmountByCategory(from, to, accountId)) {
            Long categoryId = (Long) row[0];
            BigDecimal sum = (BigDecimal) row[1];
            if (sum == null || sum.compareTo(BigDecimal.ZERO) >= 0) {
                continue; // only expenses (negative sums) appear in the breakdown
            }
            categories.add(CategorySpendItem.builder()
                    .categoryId(categoryId)
                    .categoryName(categoryId == null ? "Unkategorisiert"
                            : categoryNames.getOrDefault(categoryId, "?"))
                    .color(categoryId == null ? "#cfd8dc" : categoryColors.get(categoryId))
                    .amount(sum.abs())
                    .build());
        }
        categories.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        return OverviewResponse.builder()
                .month(month.toString())
                .totalExpenses(expenses)
                .totalIncome(income)
                .balance(income.subtract(expenses))
                .budget(budgetService.getStatus(month))
                .categories(categories)
                .build();
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(YearMonth fromMonth, YearMonth toMonth, Long accountId) {
        List<TrendPoint> points = new ArrayList<>();
        YearMonth cursor = fromMonth;
        while (!cursor.isAfter(toMonth)) {
            LocalDate from = cursor.atDay(1);
            LocalDate to = cursor.atEndOfMonth();
            points.add(TrendPoint.builder()
                    .month(cursor.toString())
                    .expenses(transactionRepository.sumExpenses(from, to, accountId).abs())
                    .income(transactionRepository.sumIncome(from, to, accountId))
                    .build());
            cursor = cursor.plusMonths(1);
        }
        return points;
    }
}
```

- [ ] **Step 3: Implement `AnalyticsController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.OverviewResponse;
import com.household.manager.dto.TrendPoint;
import com.household.manager.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/v1/finance/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

    private final AnalyticsService service;

    /** month format: yyyy-MM */
    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> overview(
            @RequestParam String month,
            @RequestParam(required = false) Long accountId) {
        return ResponseEntity.ok(service.overview(YearMonth.parse(month), accountId));
    }

    /** from/to format: yyyy-MM */
    @GetMapping("/trend")
    public ResponseEntity<List<TrendPoint>> trend(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) Long accountId) {
        return ResponseEntity.ok(service.trend(YearMonth.parse(from), YearMonth.parse(to), accountId));
    }
}
```

- [ ] **Step 4: Compile and commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS), then:

```bash
git add backend/src/main/java/com/household/manager/dto/CategorySpendItem.java \
        backend/src/main/java/com/household/manager/dto/OverviewResponse.java \
        backend/src/main/java/com/household/manager/dto/TrendPoint.java \
        backend/src/main/java/com/household/manager/service/AnalyticsService.java \
        backend/src/main/java/com/household/manager/controller/AnalyticsController.java
git commit -m "feat(finance): add analytics overview and trend API"
```

---

## Module 7 — Recurring Payment Detection

### Task 23: RecurrenceAnalyzer (pure heuristic, TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/RecurrenceResult.java`
- Create: `backend/src/main/java/com/household/manager/finance/RecurrenceAnalyzer.java`
- Create: `backend/src/test/java/com/household/manager/finance/RecurrenceAnalyzerTest.java`

- [ ] **Step 1: Create the result type**

```java
package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A detected recurrence: cadence, typical amount, and the next expected date. */
@Data
@Builder
public class RecurrenceResult {
    private final RecurrenceInterval interval;
    private final BigDecimal expectedAmount;
    private final LocalDate nextDueDate;
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecurrenceAnalyzerTest {

    private final RecurrenceAnalyzer analyzer = new RecurrenceAnalyzer();

    @Test
    void detectsMonthlyRecurrenceWithNextDueDate() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 2),
                LocalDate.of(2026, 3, 2), LocalDate.of(2026, 4, 2));
        List<BigDecimal> amounts = List.of(
                new BigDecimal("-9.99"), new BigDecimal("-9.99"),
                new BigDecimal("-9.99"), new BigDecimal("-9.99"));

        Optional<RecurrenceResult> result = analyzer.analyze(dates, amounts);

        assertTrue(result.isPresent());
        assertEquals(RecurrenceInterval.MONTHLY, result.get().getInterval());
        assertEquals(0, new BigDecimal("-9.99").compareTo(result.get().getExpectedAmount()));
        assertEquals(LocalDate.of(2026, 5, 2), result.get().getNextDueDate());
    }

    @Test
    void rejectsTooFewOccurrences() {
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 2));
        List<BigDecimal> amounts = List.of(new BigDecimal("-9.99"), new BigDecimal("-9.99"));
        assertTrue(analyzer.analyze(dates, amounts).isEmpty());
    }

    @Test
    void rejectsIrregularGaps() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 9),
                LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 1));
        List<BigDecimal> amounts = List.of(
                new BigDecimal("-5"), new BigDecimal("-5"),
                new BigDecimal("-5"), new BigDecimal("-5"));
        assertTrue(analyzer.analyze(dates, amounts).isEmpty());
    }

    @Test
    void detectsYearlyRecurrence() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 6, 1), LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 1));
        List<BigDecimal> amounts = List.of(
                new BigDecimal("-120"), new BigDecimal("-120"), new BigDecimal("-120"));
        Optional<RecurrenceResult> result = analyzer.analyze(dates, amounts);
        assertTrue(result.isPresent());
        assertEquals(RecurrenceInterval.YEARLY, result.get().getInterval());
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd backend && mvn test -Dtest=RecurrenceAnalyzerTest`
Expected: COMPILE FAILURE — `RecurrenceAnalyzer` missing.

- [ ] **Step 4: Implement `RecurrenceAnalyzer`**

```java
package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detects whether a series of dated amounts forms a regular monthly/quarterly/yearly
 * pattern. Requires at least 3 occurrences with consistent gaps (within tolerance).
 */
@Component
public class RecurrenceAnalyzer {

    private static final int MIN_OCCURRENCES = 3;

    public Optional<RecurrenceResult> analyze(List<LocalDate> dates, List<BigDecimal> amounts) {
        if (dates == null || dates.size() < MIN_OCCURRENCES) {
            return Optional.empty();
        }
        List<LocalDate> sorted = new ArrayList<>(dates);
        sorted.sort(LocalDate::compareTo);

        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(sorted.get(i - 1), sorted.get(i)));
        }
        double avgGap = gaps.stream().mapToLong(Long::longValue).average().orElse(0);

        RecurrenceInterval interval = classify(avgGap);
        if (interval == null || !gapsConsistent(gaps, avgGap)) {
            return Optional.empty();
        }

        BigDecimal expected = averageAmount(amounts);
        LocalDate last = sorted.get(sorted.size() - 1);
        LocalDate nextDue = switch (interval) {
            case MONTHLY -> last.plusMonths(1);
            case QUARTERLY -> last.plusMonths(3);
            case YEARLY -> last.plusYears(1);
        };

        return Optional.of(RecurrenceResult.builder()
                .interval(interval).expectedAmount(expected).nextDueDate(nextDue)
                .build());
    }

    private RecurrenceInterval classify(double avgGap) {
        if (avgGap >= 26 && avgGap <= 35) {
            return RecurrenceInterval.MONTHLY;
        }
        if (avgGap >= 82 && avgGap <= 98) {
            return RecurrenceInterval.QUARTERLY;
        }
        if (avgGap >= 350 && avgGap <= 380) {
            return RecurrenceInterval.YEARLY;
        }
        return null;
    }

    /** Every gap must be within 25% of the average gap. */
    private boolean gapsConsistent(List<Long> gaps, double avgGap) {
        double tolerance = avgGap * 0.25;
        return gaps.stream().allMatch(g -> Math.abs(g - avgGap) <= tolerance);
    }

    private BigDecimal averageAmount(List<BigDecimal> amounts) {
        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 5: Run to verify it passes, then commit**

Run: `cd backend && mvn test -Dtest=RecurrenceAnalyzerTest` (expect PASS), then:

```bash
git add backend/src/main/java/com/household/manager/finance/RecurrenceResult.java \
        backend/src/main/java/com/household/manager/finance/RecurrenceAnalyzer.java \
        backend/src/test/java/com/household/manager/finance/RecurrenceAnalyzerTest.java
git commit -m "feat(finance): add recurrence analyzer heuristic with tests"
```

### Task 24: Recurring payment API (detection, DTOs, service, controller)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/RecurringPaymentResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/RecurringDetectionService.java`
- Create: `backend/src/main/java/com/household/manager/controller/RecurringPaymentController.java`

- [ ] **Step 1: Create the response DTO**

```java
package com.household.manager.dto;

import com.household.manager.model.entity.RecurrenceInterval;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class RecurringPaymentResponse {
    private final Long id;
    private final Long accountId;
    private final String counterpartyPattern;
    private final Long categoryId;
    private final BigDecimal expectedAmount;
    private final RecurrenceInterval interval;
    private final LocalDate nextDueDate;
    private final boolean confirmed;
}
```

- [ ] **Step 2: Implement `RecurringDetectionService`**

```java
package com.household.manager.service;

import com.household.manager.dto.RecurringPaymentResponse;
import com.household.manager.finance.CounterpartyNameNormalizer;
import com.household.manager.finance.RecurrenceAnalyzer;
import com.household.manager.finance.RecurrenceResult;
import com.household.manager.model.entity.RecurringPayment;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.RecurringPaymentRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scans an account's expense transactions for regular patterns and stores unconfirmed
 * recurring-payment candidates for the user to confirm.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringDetectionService {

    private final TransactionRepository transactionRepository;
    private final RecurringPaymentRepository recurringRepository;
    private final CounterpartyNameNormalizer normalizer;
    private final RecurrenceAnalyzer analyzer;

    /** Detect recurring candidates for an account; returns the newly created candidates. */
    @Transactional
    public List<RecurringPaymentResponse> detect(Long accountId) {
        // Look back two years for enough history.
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusYears(2);
        List<Transaction> txs = transactionRepository
                .findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(accountId, from, to)
                .stream()
                .filter(t -> t.getAmount().signum() < 0) // expenses only
                .toList();

        Map<String, List<Transaction>> grouped = txs.stream()
                .collect(Collectors.groupingBy(t -> normalizer.normalize(t.getCounterpartyName())));

        List<RecurringPaymentResponse> created = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            String pattern = entry.getKey();
            if (pattern.isBlank()) {
                continue;
            }
            List<Transaction> group = entry.getValue();
            List<LocalDate> dates = group.stream().map(Transaction::getBookingDate).toList();
            List<java.math.BigDecimal> amounts = group.stream().map(Transaction::getAmount).toList();

            analyzer.analyze(dates, amounts).ifPresent(result -> {
                if (alreadyKnown(accountId, pattern, result)) {
                    return;
                }
                RecurringPayment saved = recurringRepository.save(RecurringPayment.builder()
                        .accountId(accountId)
                        .counterpartyPattern(pattern)
                        .categoryId(group.get(0).getCategoryId())
                        .expectedAmount(result.getExpectedAmount())
                        .interval(result.getInterval())
                        .nextDueDate(result.getNextDueDate())
                        .confirmed(false)
                        .build());
                created.add(toResponse(saved));
            });
        }
        log.info("Detected {} new recurring candidates for account {}", created.size(), accountId);
        return created;
    }

    @Transactional(readOnly = true)
    public List<RecurringPaymentResponse> list(Boolean confirmed) {
        List<RecurringPayment> items = (confirmed == null)
                ? recurringRepository.findAll()
                : recurringRepository.findByConfirmed(confirmed);
        return items.stream().map(this::toResponse).toList();
    }

    @Transactional
    public RecurringPaymentResponse confirm(Long id) {
        RecurringPayment rp = recurringRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown recurring id: " + id));
        rp.setConfirmed(true);
        return toResponse(recurringRepository.save(rp));
    }

    @Transactional
    public void delete(Long id) {
        recurringRepository.deleteById(id);
    }

    private boolean alreadyKnown(Long accountId, String pattern, RecurrenceResult result) {
        return recurringRepository
                .findByAccountIdAndCounterpartyPatternAndInterval(accountId, pattern, result.getInterval())
                .isPresent();
    }

    private RecurringPaymentResponse toResponse(RecurringPayment rp) {
        return RecurringPaymentResponse.builder()
                .id(rp.getId()).accountId(rp.getAccountId())
                .counterpartyPattern(rp.getCounterpartyPattern())
                .categoryId(rp.getCategoryId()).expectedAmount(rp.getExpectedAmount())
                .interval(rp.getInterval()).nextDueDate(rp.getNextDueDate())
                .confirmed(rp.isConfirmed())
                .build();
    }
}
```

- [ ] **Step 3: Implement `RecurringPaymentController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.RecurringPaymentResponse;
import com.household.manager.service.RecurringDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/finance/recurring")
@RequiredArgsConstructor
@Slf4j
public class RecurringPaymentController {

    private final RecurringDetectionService service;

    @GetMapping
    public ResponseEntity<List<RecurringPaymentResponse>> list(
            @RequestParam(required = false) Boolean confirmed) {
        return ResponseEntity.ok(service.list(confirmed));
    }

    @PostMapping("/detect")
    public ResponseEntity<List<RecurringPaymentResponse>> detect(@RequestParam Long accountId) {
        return ResponseEntity.ok(service.detect(accountId));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<RecurringPaymentResponse> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(service.confirm(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
```

- [ ] **Step 4: Compile, run full backend test suite, commit**

Run: `cd backend && mvn -q -DskipTests compile` (expect BUILD SUCCESS).
Run: `cd backend && mvn test -Dtest=Camt*Test,Dedup*Test,Rule*Test,Categorization*Test,Budget*Test,Recurrence*Test,Transaction*Test`
Expected: all green.

```bash
git add backend/src/main/java/com/household/manager/dto/RecurringPaymentResponse.java \
        backend/src/main/java/com/household/manager/service/RecurringDetectionService.java \
        backend/src/main/java/com/household/manager/controller/RecurringPaymentController.java
git commit -m "feat(finance): add recurring payment detection and API"
```

---

## Module 8 — Frontend

All API calls go through `/api/...` (the dev proxy forwards to the backend on 8080).
Dates are exchanged as ISO strings (`yyyy-MM-dd`); months as `yyyy-MM`.

### Task 25: Finance models and service

**Files:**
- Create: `frontend/src/app/models/finance.model.ts`
- Create: `frontend/src/app/services/finance.service.ts`

- [ ] **Step 1: Create the models**

```typescript
export type CategoryKind = 'EXPENSE' | 'INCOME' | 'TRANSFER';
export type RuleMatchField = 'COUNTERPARTY_NAME' | 'COUNTERPARTY_IBAN' | 'PURPOSE';
export type RuleMatchType = 'CONTAINS' | 'EQUALS' | 'REGEX';
export type RecurrenceInterval = 'MONTHLY' | 'QUARTERLY' | 'YEARLY';
export type BudgetStatusLevel = 'GREEN' | 'YELLOW' | 'RED';

export interface BankAccount {
  id: number;
  name: string;
  iban?: string;
  currency: string;
}

export interface BankAccountRequest {
  name: string;
  iban?: string;
  currency: string;
}

export interface Category {
  id: number;
  name: string;
  kind: CategoryKind;
  color?: string;
  system: boolean;
  parentId?: number;
}

export interface CategoryRequest {
  name: string;
  kind: CategoryKind;
  color?: string;
  parentId?: number;
}

export interface TransactionDto {
  id: number;
  accountId: number;
  bookingDate: string;
  valueDate?: string;
  amount: number;
  currency: string;
  counterpartyName?: string;
  counterpartyIban?: string;
  purpose?: string;
  categoryId?: number;
  manuallyCategorized: boolean;
}

export interface RuleSuggestion {
  field: RuleMatchField;
  matchType: RuleMatchType;
  pattern: string;
  categoryId: number;
}

export interface CategorizeResponse {
  transaction: TransactionDto;
  ruleSuggestion?: RuleSuggestion;
}

export interface CategorizationRule {
  id: number;
  field: RuleMatchField;
  matchType: RuleMatchType;
  pattern: string;
  categoryId: number;
  priority: number;
  enabled: boolean;
  appliedToExistingCount: number;
}

export interface CategorizationRuleRequest {
  field: RuleMatchField;
  matchType: RuleMatchType;
  pattern: string;
  categoryId: number;
  priority?: number;
  enabled?: boolean;
  applyToExisting?: boolean;
}

export interface ImportSummary {
  batchId: number;
  importedCount: number;
  skippedDuplicates: number;
  failedCount: number;
  uncategorizedCount: number;
  dateFrom?: string;
  dateTo?: string;
}

export interface CategorySpendItem {
  categoryId?: number;
  categoryName: string;
  color?: string;
  amount: number;
}

export interface BudgetStatusItem {
  categoryId?: number;
  categoryName: string;
  limit: number;
  spent: number;
  percent: number;
  status: BudgetStatusLevel;
}

export interface BudgetStatusResponse {
  overall?: BudgetStatusItem;
  categories: BudgetStatusItem[];
}

export interface OverviewResponse {
  month: string;
  totalExpenses: number;
  totalIncome: number;
  balance: number;
  budget: BudgetStatusResponse;
  categories: CategorySpendItem[];
}

export interface TrendPoint {
  month: string;
  expenses: number;
  income: number;
}

export interface Budget {
  id: number;
  categoryId?: number;
  period: string;
  amount: number;
}

export interface BudgetRequest {
  categoryId?: number;
  amount: number;
}

export interface RecurringPayment {
  id: number;
  accountId: number;
  counterpartyPattern: string;
  categoryId?: number;
  expectedAmount: number;
  interval: RecurrenceInterval;
  nextDueDate?: string;
  confirmed: boolean;
}
```

- [ ] **Step 2: Create the service**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  BankAccount, BankAccountRequest, Budget, BudgetRequest, BudgetStatusResponse,
  Category, CategoryRequest, CategorizationRule, CategorizationRuleRequest,
  CategorizeResponse, ImportSummary, OverviewResponse, RecurringPayment,
  TransactionDto, TrendPoint
} from '../models/finance.model';

/**
 * Service für das Ausgaben-Tracking (Finance). Kapselt alle API-Calls.
 */
@Injectable({ providedIn: 'root' })
export class FinanceService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/finance';

  // --- Accounts ---
  getAccounts(): Observable<BankAccount[]> {
    return this.http.get<BankAccount[]>(`${this.baseUrl}/accounts`).pipe(catchError(this.handleError));
  }
  createAccount(req: BankAccountRequest): Observable<BankAccount> {
    return this.http.post<BankAccount>(`${this.baseUrl}/accounts`, req).pipe(catchError(this.handleError));
  }
  updateAccount(id: number, req: BankAccountRequest): Observable<BankAccount> {
    return this.http.put<BankAccount>(`${this.baseUrl}/accounts/${id}`, req).pipe(catchError(this.handleError));
  }
  deleteAccount(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/accounts/${id}`).pipe(catchError(this.handleError));
  }

  // --- Import ---
  importStatement(accountId: number, file: File): Observable<ImportSummary> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    const params = new HttpParams().set('accountId', accountId);
    return this.http.post<ImportSummary>(`${this.baseUrl}/import`, formData, { params })
      .pipe(catchError(this.handleError));
  }

  // --- Categories ---
  getCategories(): Observable<Category[]> {
    return this.http.get<Category[]>(`${this.baseUrl}/categories`).pipe(catchError(this.handleError));
  }
  createCategory(req: CategoryRequest): Observable<Category> {
    return this.http.post<Category>(`${this.baseUrl}/categories`, req).pipe(catchError(this.handleError));
  }
  updateCategory(id: number, req: CategoryRequest): Observable<Category> {
    return this.http.put<Category>(`${this.baseUrl}/categories/${id}`, req).pipe(catchError(this.handleError));
  }
  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/categories/${id}`).pipe(catchError(this.handleError));
  }

  // --- Transactions ---
  getTransactions(from: string, to: string, accountId?: number): Observable<TransactionDto[]> {
    let params = new HttpParams().set('from', from).set('to', to);
    if (accountId != null) {
      params = params.set('accountId', accountId);
    }
    return this.http.get<TransactionDto[]>(`${this.baseUrl}/transactions`, { params })
      .pipe(catchError(this.handleError));
  }
  categorize(transactionId: number, categoryId: number): Observable<CategorizeResponse> {
    return this.http.patch<CategorizeResponse>(
      `${this.baseUrl}/transactions/${transactionId}/category`, { categoryId })
      .pipe(catchError(this.handleError));
  }

  // --- Rules ---
  getRules(): Observable<CategorizationRule[]> {
    return this.http.get<CategorizationRule[]>(`${this.baseUrl}/rules`).pipe(catchError(this.handleError));
  }
  createRule(req: CategorizationRuleRequest): Observable<CategorizationRule> {
    return this.http.post<CategorizationRule>(`${this.baseUrl}/rules`, req).pipe(catchError(this.handleError));
  }
  updateRule(id: number, req: CategorizationRuleRequest): Observable<CategorizationRule> {
    return this.http.put<CategorizationRule>(`${this.baseUrl}/rules/${id}`, req).pipe(catchError(this.handleError));
  }
  deleteRule(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/rules/${id}`).pipe(catchError(this.handleError));
  }
  applyRules(): Observable<{ applied: number }> {
    return this.http.post<{ applied: number }>(`${this.baseUrl}/rules/apply`, {}).pipe(catchError(this.handleError));
  }

  // --- Analytics ---
  getOverview(month: string, accountId?: number): Observable<OverviewResponse> {
    let params = new HttpParams().set('month', month);
    if (accountId != null) {
      params = params.set('accountId', accountId);
    }
    return this.http.get<OverviewResponse>(`${this.baseUrl}/analytics/overview`, { params })
      .pipe(catchError(this.handleError));
  }
  getTrend(from: string, to: string, accountId?: number): Observable<TrendPoint[]> {
    let params = new HttpParams().set('from', from).set('to', to);
    if (accountId != null) {
      params = params.set('accountId', accountId);
    }
    return this.http.get<TrendPoint[]>(`${this.baseUrl}/analytics/trend`, { params })
      .pipe(catchError(this.handleError));
  }

  // --- Budgets ---
  getBudgets(): Observable<Budget[]> {
    return this.http.get<Budget[]>(`${this.baseUrl}/budgets`).pipe(catchError(this.handleError));
  }
  saveBudget(req: BudgetRequest): Observable<Budget> {
    return this.http.post<Budget>(`${this.baseUrl}/budgets`, req).pipe(catchError(this.handleError));
  }
  deleteBudget(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/budgets/${id}`).pipe(catchError(this.handleError));
  }
  getBudgetStatus(month: string): Observable<BudgetStatusResponse> {
    const params = new HttpParams().set('month', month);
    return this.http.get<BudgetStatusResponse>(`${this.baseUrl}/budgets/status`, { params })
      .pipe(catchError(this.handleError));
  }

  // --- Recurring ---
  getRecurring(confirmed?: boolean): Observable<RecurringPayment[]> {
    let params = new HttpParams();
    if (confirmed != null) {
      params = params.set('confirmed', confirmed);
    }
    return this.http.get<RecurringPayment[]>(`${this.baseUrl}/recurring`, { params })
      .pipe(catchError(this.handleError));
  }
  detectRecurring(accountId: number): Observable<RecurringPayment[]> {
    const params = new HttpParams().set('accountId', accountId);
    return this.http.post<RecurringPayment[]>(`${this.baseUrl}/recurring/detect`, {}, { params })
      .pipe(catchError(this.handleError));
  }
  confirmRecurring(id: number): Observable<RecurringPayment> {
    return this.http.post<RecurringPayment>(`${this.baseUrl}/recurring/${id}/confirm`, {})
      .pipe(catchError(this.handleError));
  }
  deleteRecurring(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/recurring/${id}`).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let message = 'Ein unbekannter Fehler ist aufgetreten';
    if (error.error instanceof ErrorEvent) {
      message = `Fehler: ${error.error.message}`;
    } else if (typeof error.error === 'string' && error.error.length > 0) {
      message = error.error;
    } else {
      switch (error.status) {
        case 400: message = 'Ungültige Daten oder Datei.'; break;
        case 404: message = 'Nicht gefunden.'; break;
        case 500: message = 'Serverfehler. Bitte später erneut versuchen.'; break;
        default: message = `Server-Fehler: ${error.status}`;
      }
    }
    console.error('Finance API-Fehler:', error);
    return throwError(() => new Error(message));
  }
}
```

- [ ] **Step 3: Verify the frontend builds**

Run: `cd frontend && npm run build` (or `ng build`)
Expected: build succeeds (no type errors).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/finance.model.ts frontend/src/app/services/finance.service.ts
git commit -m "feat(finance): add frontend finance models and API service"
```

### Task 26: Statement import component

**Files:**
- Create: `frontend/src/app/components/statement-import/statement-import.component.ts`
- Create: `frontend/src/app/components/statement-import/statement-import.component.html`
- Create: `frontend/src/app/components/statement-import/statement-import.component.scss`

- [ ] **Step 1: Create the component TypeScript**

```typescript
import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { BankAccount, ImportSummary } from '../../models/finance.model';

/**
 * Uploads a CAMT (camt.053) statement file for a selected account.
 */
@Component({
  selector: 'app-statement-import',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './statement-import.component.html',
  styleUrl: './statement-import.component.scss'
})
export class StatementImportComponent {
  private readonly financeService = inject(FinanceService);

  @Input() accounts: BankAccount[] = [];
  @Input() selectedAccountId: number | null = null;
  @Output() importCompleted = new EventEmitter<ImportSummary>();

  selectedFile: File | null = null;
  isUploading = false;
  summary: ImportSummary | null = null;
  errorMessage: string | null = null;

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files && input.files.length > 0 ? input.files[0] : null;
    this.summary = null;
    this.errorMessage = null;
  }

  upload(): void {
    if (!this.selectedFile || this.selectedAccountId == null || this.isUploading) {
      return;
    }
    this.isUploading = true;
    this.summary = null;
    this.errorMessage = null;

    this.financeService.importStatement(this.selectedAccountId, this.selectedFile).subscribe({
      next: (result) => {
        this.isUploading = false;
        this.summary = result;
        this.importCompleted.emit(result);
      },
      error: (error: Error) => {
        this.isUploading = false;
        this.errorMessage = error.message;
      }
    });
  }
}
```

- [ ] **Step 2: Create the template**

```html
<div class="import-box">
  <h3>Kontoauszug importieren (CAMT / camt.053)</h3>

  <label class="field">
    <span>Konto</span>
    <select [(ngModel)]="selectedAccountId">
      <option [ngValue]="null" disabled>Konto wählen…</option>
      <option *ngFor="let account of accounts" [ngValue]="account.id">
        {{ account.name }}<ng-container *ngIf="account.iban"> ({{ account.iban }})</ng-container>
      </option>
    </select>
  </label>

  <input type="file" accept=".xml" (change)="onFileSelected($event)" />

  <button type="button"
          [disabled]="!selectedFile || selectedAccountId == null || isUploading"
          (click)="upload()">
    {{ isUploading ? 'Importiere…' : 'Importieren' }}
  </button>

  <p class="success" *ngIf="summary">
    Import abgeschlossen: {{ summary.importedCount }} neu,
    {{ summary.skippedDuplicates }} Dubletten übersprungen,
    {{ summary.failedCount }} fehlerhaft,
    {{ summary.uncategorizedCount }} unkategorisiert.
  </p>
  <p class="error" *ngIf="errorMessage">{{ errorMessage }}</p>
</div>
```

- [ ] **Step 3: Create minimal SCSS**

```scss
.import-box {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1rem;
  border: 1px solid #e0e0e0;
  border-radius: 8px;

  .field {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
  }

  .success { color: #2e7d32; }
  .error { color: #c62828; }
}
```

- [ ] **Step 4: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/components/statement-import/
git commit -m "feat(finance): add statement import component"
```

### Task 27: Expenses overview page (KPIs, donut, trend, layout toggle)

> **Refinement (surfaced):** The spec said store the layout choice in `ApplicationSetting`.
> There is no generic settings REST endpoint, so we persist the A/B toggle in `localStorage`
> (key `finance.overviewLayout`). Equivalent UX, no extra backend surface.

**Files:**
- Create: `frontend/src/app/pages/finance-overview/finance-overview.component.ts`
- Create: `frontend/src/app/pages/finance-overview/finance-overview.component.html`
- Create: `frontend/src/app/pages/finance-overview/finance-overview.component.scss`

- [ ] **Step 1: Create the component TypeScript**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { PieChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { FinanceService } from '../../services/finance.service';
import { StatementImportComponent } from '../../components/statement-import/statement-import.component';
import {
  BankAccount, BudgetStatusItem, OverviewResponse, TrendPoint
} from '../../models/finance.model';
import type { EChartsCoreOption } from 'echarts/core';

echarts.use([PieChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

type Layout = 'A' | 'B';

@Component({
  selector: 'app-finance-overview',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective, StatementImportComponent],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './finance-overview.component.html',
  styleUrl: './finance-overview.component.scss'
})
export class FinanceOverviewComponent implements OnInit {
  private readonly financeService = inject(FinanceService);
  private static readonly LAYOUT_KEY = 'finance.overviewLayout';

  accounts: BankAccount[] = [];
  selectedAccountId: number | null = null;
  month = this.currentMonth();
  layout: Layout = 'A';

  overview: OverviewResponse | null = null;
  trend: TrendPoint[] = [];
  donutOption: EChartsCoreOption = {};
  trendOption: EChartsCoreOption = {};
  loading = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    const stored = localStorage.getItem(FinanceOverviewComponent.LAYOUT_KEY);
    this.layout = stored === 'B' ? 'B' : 'A';
    this.financeService.getAccounts().subscribe({
      next: (accounts) => {
        this.accounts = accounts;
        this.load();
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  setLayout(layout: Layout): void {
    this.layout = layout;
    localStorage.setItem(FinanceOverviewComponent.LAYOUT_KEY, layout);
  }

  onFilterChange(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.errorMessage = null;
    const accountId = this.selectedAccountId ?? undefined;

    this.financeService.getOverview(this.month, accountId).subscribe({
      next: (overview) => {
        this.overview = overview;
        this.donutOption = this.buildDonut(overview.categories);
        this.loading = false;
      },
      error: (e: Error) => { this.errorMessage = e.message; this.loading = false; }
    });

    const from = this.monthsAgo(this.month, 5);
    this.financeService.getTrend(from, this.month, accountId).subscribe({
      next: (trend) => {
        this.trend = trend;
        this.trendOption = this.buildTrend(trend);
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  get categoryBudgets(): BudgetStatusItem[] {
    return this.overview?.budget?.categories ?? [];
  }

  statusColor(level: string): string {
    switch (level) {
      case 'RED': return '#c62828';
      case 'YELLOW': return '#f9a825';
      default: return '#2e7d32';
    }
  }

  private buildDonut(items: { categoryName: string; amount: number; color?: string }[]): EChartsCoreOption {
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} € ({d}%)' },
      legend: { type: 'scroll', orient: 'vertical', right: 0, top: 'center' },
      series: [{
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['40%', '50%'],
        data: items.map(i => ({
          name: i.categoryName, value: i.amount,
          itemStyle: i.color ? { color: i.color } : undefined
        }))
      }]
    };
  }

  private buildTrend(points: TrendPoint[]): EChartsCoreOption {
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['Ausgaben', 'Einnahmen'] },
      grid: { left: 50, right: 20, top: 30, bottom: 30 },
      xAxis: { type: 'category', data: points.map(p => p.month) },
      yAxis: { type: 'value' },
      series: [
        { name: 'Ausgaben', type: 'line', data: points.map(p => p.expenses), itemStyle: { color: '#ef5350' } },
        { name: 'Einnahmen', type: 'line', data: points.map(p => p.income), itemStyle: { color: '#66bb6a' } }
      ]
    };
  }

  private currentMonth(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }

  private monthsAgo(month: string, count: number): string {
    const [year, m] = month.split('-').map(Number);
    const date = new Date(year, m - 1 - count, 1);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
  }
}
```

- [ ] **Step 2: Create the template (both layouts via a CSS class)**

```html
<section class="finance-overview" [class.layout-b]="layout === 'B'">
  <header class="toolbar">
    <h1>Ausgaben-Übersicht</h1>
    <div class="filters">
      <label>
        Konto
        <select [(ngModel)]="selectedAccountId" (ngModelChange)="onFilterChange()">
          <option [ngValue]="null">Alle</option>
          <option *ngFor="let account of accounts" [ngValue]="account.id">{{ account.name }}</option>
        </select>
      </label>
      <label>
        Monat
        <input type="month" [(ngModel)]="month" (ngModelChange)="onFilterChange()" />
      </label>
      <div class="layout-switch">
        <button type="button" [class.active]="layout === 'A'" (click)="setLayout('A')">Layout A</button>
        <button type="button" [class.active]="layout === 'B'" (click)="setLayout('B')">Layout B</button>
      </div>
    </div>
  </header>

  <p class="error" *ngIf="errorMessage">{{ errorMessage }}</p>

  <div class="grid" *ngIf="overview as o">
    <div class="kpis">
      <div class="kpi"><span class="label">Ausgaben</span><strong>{{ o.totalExpenses | number:'1.2-2' }} €</strong></div>
      <div class="kpi"><span class="label">Einnahmen</span><strong>{{ o.totalIncome | number:'1.2-2' }} €</strong></div>
      <div class="kpi"><span class="label">Saldo</span><strong>{{ o.balance | number:'1.2-2' }} €</strong></div>
      <div class="kpi" *ngIf="o.budget?.overall as ov">
        <span class="label">Budget</span>
        <strong [style.color]="statusColor(ov.status)">{{ ov.percent }} %</strong>
      </div>
    </div>

    <div class="card categories-card">
      <h2>Kategorien</h2>
      <div echarts [options]="donutOption" class="chart"></div>
    </div>

    <div class="card budgets-card">
      <h2>Budgets</h2>
      <div class="budget-bar" *ngFor="let b of categoryBudgets">
        <span class="name">{{ b.categoryName }}</span>
        <div class="bar"><div class="fill" [style.width.%]="b.percent > 100 ? 100 : b.percent"
                              [style.background]="statusColor(b.status)"></div></div>
        <span class="pct">{{ b.percent }} %</span>
      </div>
      <p *ngIf="categoryBudgets.length === 0" class="muted">Noch keine Kategorie-Budgets gesetzt.</p>
    </div>

    <div class="card trend-card">
      <h2>Trend (6 Monate)</h2>
      <div echarts [options]="trendOption" class="chart"></div>
    </div>

    <div class="card import-card">
      <app-statement-import [accounts]="accounts" [selectedAccountId]="selectedAccountId"
                            (importCompleted)="load()"></app-statement-import>
    </div>
  </div>
</section>
```

- [ ] **Step 3: Create SCSS (grid areas drive the A/B layouts)**

```scss
.finance-overview {
  padding: 1rem;

  .toolbar {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    align-items: center;
    gap: 1rem;

    .filters { display: flex; gap: 1rem; align-items: center; flex-wrap: wrap; }
    .layout-switch button {
      padding: 0.3rem 0.6rem;
      &.active { font-weight: 700; text-decoration: underline; }
    }
  }

  .error { color: #c62828; }
  .muted { color: #888; }

  .kpis {
    grid-area: kpis;
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 0.75rem;
    .kpi {
      display: flex; flex-direction: column;
      padding: 0.75rem; border: 1px solid #e0e0e0; border-radius: 8px;
      .label { font-size: 0.8rem; color: #666; }
    }
  }

  .card {
    border: 1px solid #e0e0e0; border-radius: 8px; padding: 1rem;
    .chart { width: 100%; height: 260px; }
  }
  .categories-card { grid-area: categories; }
  .budgets-card { grid-area: budgets; }
  .trend-card { grid-area: trend; }
  .import-card { grid-area: import; }

  .budget-bar {
    display: grid; grid-template-columns: 1fr 3fr auto; gap: 0.5rem; align-items: center;
    margin-bottom: 0.4rem;
    .bar { background: #eee; border-radius: 4px; height: 10px; overflow: hidden; }
    .fill { height: 100%; }
  }

  /* Layout A: KPIs on top, donut + trend side by side, budgets + import below */
  .grid {
    display: grid;
    gap: 1rem;
    grid-template-columns: 1fr 1fr;
    grid-template-areas:
      "kpis kpis"
      "categories trend"
      "budgets import";
  }

  /* Layout B: budgets sidebar left, content right */
  &.layout-b .grid {
    grid-template-columns: 1fr 2fr;
    grid-template-areas:
      "kpis kpis"
      "budgets trend"
      "budgets categories"
      "import import";
  }

  @media (max-width: 800px) {
    .grid, &.layout-b .grid {
      grid-template-columns: 1fr;
      grid-template-areas: "kpis" "categories" "trend" "budgets" "import";
    }
    .kpis { grid-template-columns: repeat(2, 1fr); }
  }
}
```

- [ ] **Step 4: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/pages/finance-overview/
git commit -m "feat(finance): add expenses overview page with A/B layout toggle"
```

### Task 28: Transactions page with manual categorization + rule-suggestion dialog

**Files:**
- Create: `frontend/src/app/pages/finance-transactions/finance-transactions.component.ts`
- Create: `frontend/src/app/pages/finance-transactions/finance-transactions.component.html`
- Create: `frontend/src/app/pages/finance-transactions/finance-transactions.component.scss`

- [ ] **Step 1: Create the component TypeScript**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import {
  BankAccount, Category, RuleSuggestion, TransactionDto
} from '../../models/finance.model';

@Component({
  selector: 'app-finance-transactions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-transactions.component.html',
  styleUrl: './finance-transactions.component.scss'
})
export class FinanceTransactionsComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  accounts: BankAccount[] = [];
  categories: Category[] = [];
  transactions: TransactionDto[] = [];

  selectedAccountId: number | null = null;
  month = this.currentMonth();
  search = '';
  onlyUncategorized = false;

  loading = false;
  errorMessage: string | null = null;

  pendingSuggestion: RuleSuggestion | null = null;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe(c => this.categories = c);
    this.financeService.getAccounts().subscribe({
      next: (a) => { this.accounts = a; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  load(): void {
    this.loading = true;
    this.errorMessage = null;
    const [from, to] = this.monthRange(this.month);
    this.financeService.getTransactions(from, to, this.selectedAccountId ?? undefined).subscribe({
      next: (txs) => { this.transactions = txs; this.loading = false; },
      error: (e: Error) => { this.errorMessage = e.message; this.loading = false; }
    });
  }

  get visibleTransactions(): TransactionDto[] {
    const term = this.search.trim().toLowerCase();
    return this.transactions.filter(t => {
      if (this.onlyUncategorized && t.categoryId != null) {
        return false;
      }
      if (!term) {
        return true;
      }
      return (t.counterpartyName ?? '').toLowerCase().includes(term)
          || (t.purpose ?? '').toLowerCase().includes(term);
    });
  }

  categoryName(id?: number): string {
    if (id == null) {
      return 'Unkategorisiert';
    }
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }

  onCategoryChange(tx: TransactionDto, categoryId: number): void {
    this.financeService.categorize(tx.id, categoryId).subscribe({
      next: (response) => {
        tx.categoryId = response.transaction.categoryId;
        tx.manuallyCategorized = response.transaction.manuallyCategorized;
        this.pendingSuggestion = response.ruleSuggestion ?? null;
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  acceptSuggestion(): void {
    if (!this.pendingSuggestion) {
      return;
    }
    const s = this.pendingSuggestion;
    this.financeService.createRule({
      field: s.field, matchType: s.matchType, pattern: s.pattern,
      categoryId: s.categoryId, applyToExisting: true
    }).subscribe({
      next: () => { this.pendingSuggestion = null; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  dismissSuggestion(): void {
    this.pendingSuggestion = null;
  }

  suggestedCategoryName(): string {
    return this.categoryName(this.pendingSuggestion?.categoryId);
  }

  private currentMonth(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }

  private monthRange(month: string): [string, string] {
    const [year, m] = month.split('-').map(Number);
    const from = `${year}-${String(m).padStart(2, '0')}-01`;
    const lastDay = new Date(year, m, 0).getDate();
    const to = `${year}-${String(m).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;
    return [from, to];
  }
}
```

- [ ] **Step 2: Create the template**

```html
<section class="transactions">
  <header class="toolbar">
    <h1>Transaktionen</h1>
    <div class="filters">
      <label>Konto
        <select [(ngModel)]="selectedAccountId" (ngModelChange)="load()">
          <option [ngValue]="null">Alle</option>
          <option *ngFor="let a of accounts" [ngValue]="a.id">{{ a.name }}</option>
        </select>
      </label>
      <label>Monat <input type="month" [(ngModel)]="month" (ngModelChange)="load()" /></label>
      <label>Suche <input type="text" [(ngModel)]="search" placeholder="Empfänger/Zweck" /></label>
      <label class="check"><input type="checkbox" [(ngModel)]="onlyUncategorized" /> Nur unkategorisiert</label>
    </div>
  </header>

  <p class="error" *ngIf="errorMessage">{{ errorMessage }}</p>
  <p *ngIf="loading">Lade…</p>

  <div class="suggestion" *ngIf="pendingSuggestion as s">
    <span>Künftig „{{ s.pattern }}" immer als <strong>{{ suggestedCategoryName() }}</strong>?</span>
    <button type="button" (click)="acceptSuggestion()">Regel anlegen</button>
    <button type="button" class="ghost" (click)="dismissSuggestion()">Nein danke</button>
  </div>

  <table class="tx-table" *ngIf="!loading">
    <thead>
      <tr>
        <th>Datum</th><th>Empfänger</th><th>Zweck</th><th class="num">Betrag</th><th>Kategorie</th>
      </tr>
    </thead>
    <tbody>
      <tr *ngFor="let tx of visibleTransactions">
        <td>{{ tx.bookingDate }}</td>
        <td>{{ tx.counterpartyName }}</td>
        <td class="purpose">{{ tx.purpose }}</td>
        <td class="num" [class.negative]="tx.amount < 0">{{ tx.amount | number:'1.2-2' }} €</td>
        <td>
          <select [ngModel]="tx.categoryId ?? null"
                  (ngModelChange)="onCategoryChange(tx, $event)">
            <option [ngValue]="null" disabled>Unkategorisiert</option>
            <option *ngFor="let c of categories" [ngValue]="c.id">{{ c.name }}</option>
          </select>
          <span class="manual" *ngIf="tx.manuallyCategorized" title="Manuell gesetzt">✓</span>
        </td>
      </tr>
      <tr *ngIf="visibleTransactions.length === 0">
        <td colspan="5" class="muted">Keine Transaktionen.</td>
      </tr>
    </tbody>
  </table>
</section>
```

- [ ] **Step 3: Create minimal SCSS**

```scss
.transactions {
  padding: 1rem;

  .toolbar { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 1rem; align-items: center; }
  .filters { display: flex; gap: 1rem; flex-wrap: wrap; align-items: center; }
  .check { display: flex; align-items: center; gap: 0.3rem; }
  .error { color: #c62828; }
  .muted { color: #888; text-align: center; }

  .suggestion {
    display: flex; align-items: center; gap: 0.75rem;
    background: #e3f2fd; border: 1px solid #90caf9; border-radius: 8px;
    padding: 0.6rem 1rem; margin: 0.75rem 0;
    .ghost { background: transparent; }
  }

  .tx-table {
    width: 100%; border-collapse: collapse;
    th, td { padding: 0.5rem; border-bottom: 1px solid #eee; text-align: left; }
    .num { text-align: right; }
    .negative { color: #c62828; }
    .purpose { max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .manual { color: #2e7d32; margin-left: 0.3rem; }
  }
}
```

- [ ] **Step 4: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/pages/finance-transactions/
git commit -m "feat(finance): add transactions page with categorization and rule suggestions"
```

> **Shared SCSS note for Tasks 29–33:** these management pages use the same simple
> table/form styling. Each `.scss` below is complete on its own; the repetition is
> intentional so tasks can be implemented independently.

### Task 29: Accounts management page

**Files:**
- Create: `frontend/src/app/pages/finance-accounts/finance-accounts.component.ts`
- Create: `frontend/src/app/pages/finance-accounts/finance-accounts.component.html`
- Create: `frontend/src/app/pages/finance-accounts/finance-accounts.component.scss`

- [ ] **Step 1: Component TypeScript**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { BankAccount, BankAccountRequest } from '../../models/finance.model';

@Component({
  selector: 'app-finance-accounts',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-accounts.component.html',
  styleUrl: './finance-accounts.component.scss'
})
export class FinanceAccountsComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  accounts: BankAccount[] = [];
  form: BankAccountRequest = { name: '', iban: '', currency: 'EUR' };
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.financeService.getAccounts().subscribe({
      next: (a) => this.accounts = a,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  create(): void {
    if (!this.form.name || !this.form.currency) {
      return;
    }
    this.financeService.createAccount(this.form).subscribe({
      next: () => { this.form = { name: '', iban: '', currency: 'EUR' }; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  remove(account: BankAccount): void {
    this.financeService.deleteAccount(account.id).subscribe({
      next: () => this.load(),
      error: (e: Error) => this.errorMessage = e.message
    });
  }
}
```

- [ ] **Step 2: Template**

```html
<section class="manage">
  <h1>Konten</h1>
  <p class="error" *ngIf="errorMessage">{{ errorMessage }}</p>

  <form class="row-form" (ngSubmit)="create()">
    <input type="text" [(ngModel)]="form.name" name="name" placeholder="Name" required />
    <input type="text" [(ngModel)]="form.iban" name="iban" placeholder="IBAN (optional)" />
    <input type="text" [(ngModel)]="form.currency" name="currency" placeholder="Währung" required />
    <button type="submit">Hinzufügen</button>
  </form>

  <table>
    <thead><tr><th>Name</th><th>IBAN</th><th>Währung</th><th></th></tr></thead>
    <tbody>
      <tr *ngFor="let a of accounts">
        <td>{{ a.name }}</td><td>{{ a.iban }}</td><td>{{ a.currency }}</td>
        <td><button type="button" class="danger" (click)="remove(a)">Löschen</button></td>
      </tr>
      <tr *ngIf="accounts.length === 0"><td colspan="4" class="muted">Noch keine Konten.</td></tr>
    </tbody>
  </table>
</section>
```

- [ ] **Step 3: SCSS**

```scss
.manage {
  padding: 1rem;
  .error { color: #c62828; }
  .muted { color: #888; text-align: center; }
  .row-form { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 1rem; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 0.5rem; border-bottom: 1px solid #eee; text-align: left; }
  .danger { color: #c62828; }
}
```

- [ ] **Step 4: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/pages/finance-accounts/
git commit -m "feat(finance): add accounts management page"
```

### Task 30: Categories management page

**Files:**
- Create: `frontend/src/app/pages/finance-categories/finance-categories.component.ts`
- Create: `frontend/src/app/pages/finance-categories/finance-categories.component.html`
- Create: `frontend/src/app/pages/finance-categories/finance-categories.component.scss`

- [ ] **Step 1: Component TypeScript**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { Category, CategoryKind, CategoryRequest } from '../../models/finance.model';

@Component({
  selector: 'app-finance-categories',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-categories.component.html',
  styleUrl: './finance-categories.component.scss'
})
export class FinanceCategoriesComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  categories: Category[] = [];
  readonly kinds: CategoryKind[] = ['EXPENSE', 'INCOME', 'TRANSFER'];
  form: CategoryRequest = { name: '', kind: 'EXPENSE', color: '#90a4ae' };
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.financeService.getCategories().subscribe({
      next: (c) => this.categories = c,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  create(): void {
    if (!this.form.name) {
      return;
    }
    this.financeService.createCategory(this.form).subscribe({
      next: () => { this.form = { name: '', kind: 'EXPENSE', color: '#90a4ae' }; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  remove(category: Category): void {
    this.financeService.deleteCategory(category.id).subscribe({
      next: () => this.load(),
      error: (e: Error) => this.errorMessage = e.message
    });
  }
}
```

- [ ] **Step 2: Template**

```html
<section class="manage">
  <h1>Kategorien</h1>
  <p class="error" *ngIf="errorMessage">{{ errorMessage }}</p>

  <form class="row-form" (ngSubmit)="create()">
    <input type="text" [(ngModel)]="form.name" name="name" placeholder="Name" required />
    <select [(ngModel)]="form.kind" name="kind">
      <option *ngFor="let k of kinds" [ngValue]="k">{{ k }}</option>
    </select>
    <input type="color" [(ngModel)]="form.color" name="color" />
    <button type="submit">Hinzufügen</button>
  </form>

  <table>
    <thead><tr><th>Farbe</th><th>Name</th><th>Typ</th><th>System</th><th></th></tr></thead>
    <tbody>
      <tr *ngFor="let c of categories">
        <td><span class="swatch" [style.background]="c.color"></span></td>
        <td>{{ c.name }}</td>
        <td>{{ c.kind }}</td>
        <td>{{ c.system ? 'ja' : 'nein' }}</td>
        <td><button type="button" class="danger" *ngIf="!c.system" (click)="remove(c)">Löschen</button></td>
      </tr>
    </tbody>
  </table>
</section>
```

- [ ] **Step 3: SCSS**

```scss
.manage {
  padding: 1rem;
  .error { color: #c62828; }
  .row-form { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 1rem; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 0.5rem; border-bottom: 1px solid #eee; text-align: left; }
  .swatch { display: inline-block; width: 16px; height: 16px; border-radius: 4px; }
  .danger { color: #c62828; }
}
```

- [ ] **Step 4: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/pages/finance-categories/
git commit -m "feat(finance): add categories management page"
```

### Task 31: Rules management page

**Files:**
- Create: `frontend/src/app/pages/finance-rules/finance-rules.component.ts`
- Create: `frontend/src/app/pages/finance-rules/finance-rules.component.html`
- Create: `frontend/src/app/pages/finance-rules/finance-rules.component.scss`

- [ ] **Step 1: Component TypeScript**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import {
  Category, CategorizationRule, CategorizationRuleRequest, RuleMatchField, RuleMatchType
} from '../../models/finance.model';

@Component({
  selector: 'app-finance-rules',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-rules.component.html',
  styleUrl: './finance-rules.component.scss'
})
export class FinanceRulesComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  rules: CategorizationRule[] = [];
  categories: Category[] = [];
  readonly fields: RuleMatchField[] = ['COUNTERPARTY_NAME', 'COUNTERPARTY_IBAN', 'PURPOSE'];
  readonly matchTypes: RuleMatchType[] = ['CONTAINS', 'EQUALS', 'REGEX'];

  form: CategorizationRuleRequest = {
    field: 'COUNTERPARTY_NAME', matchType: 'CONTAINS', pattern: '',
    categoryId: 0, priority: 100, enabled: true, applyToExisting: true
  };
  errorMessage: string | null = null;
  infoMessage: string | null = null;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe(c => {
      this.categories = c;
      if (c.length > 0) {
        this.form.categoryId = c[0].id;
      }
    });
    this.load();
  }

  load(): void {
    this.financeService.getRules().subscribe({
      next: (r) => this.rules = r,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  create(): void {
    if (!this.form.pattern || !this.form.categoryId) {
      return;
    }
    this.financeService.createRule(this.form).subscribe({
      next: (rule) => {
        this.infoMessage = `Regel angelegt, ${rule.appliedToExistingCount} bestehende Buchungen zugeordnet.`;
        this.form.pattern = '';
        this.load();
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  toggle(rule: CategorizationRule): void {
    this.financeService.updateRule(rule.id, {
      field: rule.field, matchType: rule.matchType, pattern: rule.pattern,
      categoryId: rule.categoryId, priority: rule.priority, enabled: !rule.enabled
    }).subscribe({ next: () => this.load(), error: (e: Error) => this.errorMessage = e.message });
  }

  remove(rule: CategorizationRule): void {
    this.financeService.deleteRule(rule.id).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  applyAll(): void {
    this.financeService.applyRules().subscribe({
      next: (r) => this.infoMessage = `${r.applied} Buchungen neu zugeordnet.`,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  categoryName(id: number): string {
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }
}
```

- [ ] **Step 2: Template**

```html
<section class="manage">
  <h1>Kategorisierungs-Regeln</h1>
  <p class="error" *ngIf="errorMessage">{{ errorMessage }}</p>
  <p class="info" *ngIf="infoMessage">{{ infoMessage }}</p>

  <form class="row-form" (ngSubmit)="create()">
    <select [(ngModel)]="form.field" name="field">
      <option *ngFor="let f of fields" [ngValue]="f">{{ f }}</option>
    </select>
    <select [(ngModel)]="form.matchType" name="matchType">
      <option *ngFor="let t of matchTypes" [ngValue]="t">{{ t }}</option>
    </select>
    <input type="text" [(ngModel)]="form.pattern" name="pattern" placeholder="Muster" required />
    <select [(ngModel)]="form.categoryId" name="categoryId">
      <option *ngFor="let c of categories" [ngValue]="c.id">{{ c.name }}</option>
    </select>
    <input type="number" [(ngModel)]="form.priority" name="priority" placeholder="Prio" />
    <button type="submit">Regel anlegen</button>
  </form>

  <button type="button" class="apply" (click)="applyAll()">Regeln auf unkategorisierte anwenden</button>

  <table>
    <thead><tr><th>Feld</th><th>Typ</th><th>Muster</th><th>Kategorie</th><th>Prio</th><th>Aktiv</th><th></th></tr></thead>
    <tbody>
      <tr *ngFor="let r of rules" [class.disabled]="!r.enabled">
        <td>{{ r.field }}</td><td>{{ r.matchType }}</td><td>{{ r.pattern }}</td>
        <td>{{ categoryName(r.categoryId) }}</td><td>{{ r.priority }}</td>
        <td><button type="button" (click)="toggle(r)">{{ r.enabled ? 'an' : 'aus' }}</button></td>
        <td><button type="button" class="danger" (click)="remove(r)">Löschen</button></td>
      </tr>
      <tr *ngIf="rules.length === 0"><td colspan="7" class="muted">Noch keine Regeln.</td></tr>
    </tbody>
  </table>
</section>
```

- [ ] **Step 3: SCSS**

```scss
.manage {
  padding: 1rem;
  .error { color: #c62828; }
  .info { color: #1565c0; }
  .muted { color: #888; text-align: center; }
  .row-form { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 0.5rem; }
  .apply { margin-bottom: 1rem; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 0.5rem; border-bottom: 1px solid #eee; text-align: left; }
  tr.disabled { opacity: 0.5; }
  .danger { color: #c62828; }
}
```

- [ ] **Step 4: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/pages/finance-rules/
git commit -m "feat(finance): add rules management page"
```

### Task 32: Budgets management page

**Files:**
- Create: `frontend/src/app/pages/finance-budgets/finance-budgets.component.ts`
- Create: `frontend/src/app/pages/finance-budgets/finance-budgets.component.html`
- Create: `frontend/src/app/pages/finance-budgets/finance-budgets.component.scss`

- [ ] **Step 1: Component TypeScript**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { Budget, BudgetStatusResponse, Category } from '../../models/finance.model';

@Component({
  selector: 'app-finance-budgets',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-budgets.component.html',
  styleUrl: './finance-budgets.component.scss'
})
export class FinanceBudgetsComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  budgets: Budget[] = [];
  categories: Category[] = [];
  status: BudgetStatusResponse | null = null;
  month = this.currentMonth();

  overallAmount: number | null = null;
  categoryId: number | null = null;
  categoryAmount: number | null = null;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe(c => this.categories = c.filter(x => x.kind === 'EXPENSE'));
    this.load();
  }

  load(): void {
    this.financeService.getBudgets().subscribe({
      next: (b) => {
        this.budgets = b;
        this.overallAmount = b.find(x => x.categoryId == null)?.amount ?? null;
      },
      error: (e: Error) => this.errorMessage = e.message
    });
    this.financeService.getBudgetStatus(this.month).subscribe({
      next: (s) => this.status = s,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  saveOverall(): void {
    if (this.overallAmount == null) {
      return;
    }
    this.financeService.saveBudget({ amount: this.overallAmount }).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  saveCategory(): void {
    if (this.categoryId == null || this.categoryAmount == null) {
      return;
    }
    this.financeService.saveBudget({ categoryId: this.categoryId, amount: this.categoryAmount }).subscribe({
      next: () => { this.categoryAmount = null; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  remove(budget: Budget): void {
    this.financeService.deleteBudget(budget.id).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  categoryName(id?: number): string {
    if (id == null) {
      return 'Gesamt';
    }
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }

  statusColor(level?: string): string {
    switch (level) {
      case 'RED': return '#c62828';
      case 'YELLOW': return '#f9a825';
      default: return '#2e7d32';
    }
  }

  private currentMonth(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }
}
```

- [ ] **Step 2: Template**

```html
<section class="manage">
  <h1>Budgets</h1>
  <p class="error" *ngIf="errorMessage">{{ errorMessage }}</p>

  <div class="forms">
    <form class="row-form" (ngSubmit)="saveOverall()">
      <strong>Gesamtbudget / Monat</strong>
      <input type="number" [(ngModel)]="overallAmount" name="overall" placeholder="z. B. 2000" />
      <button type="submit">Speichern</button>
    </form>

    <form class="row-form" (ngSubmit)="saveCategory()">
      <strong>Kategorie-Budget</strong>
      <select [(ngModel)]="categoryId" name="cat">
        <option [ngValue]="null" disabled>Kategorie…</option>
        <option *ngFor="let c of categories" [ngValue]="c.id">{{ c.name }}</option>
      </select>
      <input type="number" [(ngModel)]="categoryAmount" name="catAmount" placeholder="Limit" />
      <button type="submit">Speichern</button>
    </form>
  </div>

  <label>Status-Monat <input type="month" [(ngModel)]="month" (ngModelChange)="load()" /></label>

  <h2>Status {{ month }}</h2>
  <div class="status" *ngIf="status as s">
    <div class="bar-row" *ngIf="s.overall as ov">
      <span class="name">Gesamt</span>
      <div class="bar"><div class="fill" [style.width.%]="ov.percent > 100 ? 100 : ov.percent"
                            [style.background]="statusColor(ov.status)"></div></div>
      <span>{{ ov.spent | number:'1.0-0' }} / {{ ov.limit | number:'1.0-0' }} € ({{ ov.percent }} %)</span>
    </div>
    <div class="bar-row" *ngFor="let item of s.categories">
      <span class="name">{{ item.categoryName }}</span>
      <div class="bar"><div class="fill" [style.width.%]="item.percent > 100 ? 100 : item.percent"
                            [style.background]="statusColor(item.status)"></div></div>
      <span>{{ item.spent | number:'1.0-0' }} / {{ item.limit | number:'1.0-0' }} € ({{ item.percent }} %)</span>
    </div>
  </div>

  <h2>Gesetzte Budgets</h2>
  <table>
    <thead><tr><th>Bereich</th><th>Limit</th><th></th></tr></thead>
    <tbody>
      <tr *ngFor="let b of budgets">
        <td>{{ categoryName(b.categoryId) }}</td>
        <td>{{ b.amount | number:'1.2-2' }} €</td>
        <td><button type="button" class="danger" (click)="remove(b)">Löschen</button></td>
      </tr>
      <tr *ngIf="budgets.length === 0"><td colspan="3" class="muted">Noch keine Budgets.</td></tr>
    </tbody>
  </table>
</section>
```

- [ ] **Step 3: SCSS**

```scss
.manage {
  padding: 1rem;
  .error { color: #c62828; }
  .muted { color: #888; text-align: center; }
  .forms { display: flex; gap: 2rem; flex-wrap: wrap; margin-bottom: 1rem; }
  .row-form { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 0.5rem; border-bottom: 1px solid #eee; text-align: left; }
  .danger { color: #c62828; }
  .bar-row {
    display: grid; grid-template-columns: 1fr 3fr 2fr; gap: 0.5rem; align-items: center; margin-bottom: 0.4rem;
    .bar { background: #eee; border-radius: 4px; height: 12px; overflow: hidden; }
    .fill { height: 100%; }
  }
}
```

- [ ] **Step 4: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/pages/finance-budgets/
git commit -m "feat(finance): add budgets management page"
```

### Task 33: Recurring payments page

**Files:**
- Create: `frontend/src/app/pages/finance-recurring/finance-recurring.component.ts`
- Create: `frontend/src/app/pages/finance-recurring/finance-recurring.component.html`
- Create: `frontend/src/app/pages/finance-recurring/finance-recurring.component.scss`

- [ ] **Step 1: Component TypeScript**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { BankAccount, Category, RecurringPayment } from '../../models/finance.model';

@Component({
  selector: 'app-finance-recurring',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-recurring.component.html',
  styleUrl: './finance-recurring.component.scss'
})
export class FinanceRecurringComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  accounts: BankAccount[] = [];
  categories: Category[] = [];
  items: RecurringPayment[] = [];
  selectedAccountId: number | null = null;
  detecting = false;
  errorMessage: string | null = null;
  infoMessage: string | null = null;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe(c => this.categories = c);
    this.financeService.getAccounts().subscribe({
      next: (a) => {
        this.accounts = a;
        this.selectedAccountId = a.length > 0 ? a[0].id : null;
      },
      error: (e: Error) => this.errorMessage = e.message
    });
    this.load();
  }

  load(): void {
    this.financeService.getRecurring().subscribe({
      next: (r) => this.items = r,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  detect(): void {
    if (this.selectedAccountId == null) {
      return;
    }
    this.detecting = true;
    this.infoMessage = null;
    this.financeService.detectRecurring(this.selectedAccountId).subscribe({
      next: (found) => {
        this.detecting = false;
        this.infoMessage = `${found.length} neue Kandidaten gefunden.`;
        this.load();
      },
      error: (e: Error) => { this.detecting = false; this.errorMessage = e.message; }
    });
  }

  confirm(item: RecurringPayment): void {
    this.financeService.confirmRecurring(item.id).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  remove(item: RecurringPayment): void {
    this.financeService.deleteRecurring(item.id).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  categoryName(id?: number): string {
    if (id == null) {
      return '—';
    }
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }
}
```

- [ ] **Step 2: Template**

```html
<section class="manage">
  <h1>Wiederkehrende Zahlungen</h1>
  <p class="error" *ngIf="errorMessage">{{ errorMessage }}</p>
  <p class="info" *ngIf="infoMessage">{{ infoMessage }}</p>

  <div class="row-form">
    <select [(ngModel)]="selectedAccountId" name="acc">
      <option *ngFor="let a of accounts" [ngValue]="a.id">{{ a.name }}</option>
    </select>
    <button type="button" [disabled]="detecting || selectedAccountId == null" (click)="detect()">
      {{ detecting ? 'Suche…' : 'Wiederkehrende suchen' }}
    </button>
  </div>

  <table>
    <thead>
      <tr><th>Empfänger</th><th>Betrag</th><th>Intervall</th><th>Nächste Fälligkeit</th>
          <th>Kategorie</th><th>Status</th><th></th></tr>
    </thead>
    <tbody>
      <tr *ngFor="let item of items">
        <td>{{ item.counterpartyPattern }}</td>
        <td>{{ item.expectedAmount | number:'1.2-2' }} €</td>
        <td>{{ item.interval }}</td>
        <td>{{ item.nextDueDate }}</td>
        <td>{{ categoryName(item.categoryId) }}</td>
        <td>{{ item.confirmed ? 'bestätigt' : 'Kandidat' }}</td>
        <td>
          <button type="button" *ngIf="!item.confirmed" (click)="confirm(item)">Bestätigen</button>
          <button type="button" class="danger" (click)="remove(item)">Verwerfen</button>
        </td>
      </tr>
      <tr *ngIf="items.length === 0"><td colspan="7" class="muted">Noch nichts erkannt.</td></tr>
    </tbody>
  </table>
</section>
```

- [ ] **Step 3: SCSS**

```scss
.manage {
  padding: 1rem;
  .error { color: #c62828; }
  .info { color: #1565c0; }
  .muted { color: #888; text-align: center; }
  .row-form { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 0.5rem; border-bottom: 1px solid #eee; text-align: left; }
  .danger { color: #c62828; margin-left: 0.3rem; }
}
```

- [ ] **Step 4: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/pages/finance-recurring/
git commit -m "feat(finance): add recurring payments page"
```

### Task 34: Routing and navigation

**Files:**
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts`

- [ ] **Step 1: Add finance routes**

In `frontend/src/app/app.routes.ts`, insert these route objects before the wildcard `{ path: '**', ... }` entry:

```typescript
  {
    path: 'finance',
    loadComponent: () => import('./pages/finance-overview/finance-overview.component').then(m => m.FinanceOverviewComponent),
    title: 'Ausgaben - Household Manager'
  },
  {
    path: 'finance/transactions',
    loadComponent: () => import('./pages/finance-transactions/finance-transactions.component').then(m => m.FinanceTransactionsComponent),
    title: 'Transaktionen - Household Manager'
  },
  {
    path: 'finance/accounts',
    loadComponent: () => import('./pages/finance-accounts/finance-accounts.component').then(m => m.FinanceAccountsComponent),
    title: 'Konten - Household Manager'
  },
  {
    path: 'finance/categories',
    loadComponent: () => import('./pages/finance-categories/finance-categories.component').then(m => m.FinanceCategoriesComponent),
    title: 'Kategorien - Household Manager'
  },
  {
    path: 'finance/rules',
    loadComponent: () => import('./pages/finance-rules/finance-rules.component').then(m => m.FinanceRulesComponent),
    title: 'Regeln - Household Manager'
  },
  {
    path: 'finance/budgets',
    loadComponent: () => import('./pages/finance-budgets/finance-budgets.component').then(m => m.FinanceBudgetsComponent),
    title: 'Budgets - Household Manager'
  },
  {
    path: 'finance/recurring',
    loadComponent: () => import('./pages/finance-recurring/finance-recurring.component').then(m => m.FinanceRecurringComponent),
    title: 'Wiederkehrende - Household Manager'
  },
```

- [ ] **Step 2: Add a navigation entry with submenu**

In `frontend/src/app/components/header/header.component.ts`, add this object to the `navLinks` array (e.g. after the `Energie` entry):

```typescript
    {
      path: '/finance',
      label: 'Ausgaben',
      children: [
        { path: '/finance', label: 'Uebersicht', exact: true },
        { path: '/finance/transactions', label: 'Transaktionen' },
        { path: '/finance/recurring', label: 'Wiederkehrende' },
        { path: '/finance/budgets', label: 'Budgets' },
        { path: '/finance/categories', label: 'Kategorien' },
        { path: '/finance/rules', label: 'Regeln' },
        { path: '/finance/accounts', label: 'Konten' }
      ]
    },
```

- [ ] **Step 3: Build and commit**

Run: `cd frontend && npm run build` (expect success), then:

```bash
git add frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(finance): wire up finance routes and navigation"
```

---

## Final Verification

### Task 35: Full build and test pass

- [ ] **Step 1: Backend — full build with tests**

Set `JAVA_HOME` to a JDK 21 install first (the default is JDK 17). Then:
Run: `cd backend && mvn clean install`
Expected: BUILD SUCCESS. Note: DB-backed integration tests may fail without a local
MariaDB — the new logic is covered by pure unit tests (parser, dedup, matcher,
categorization, budget evaluator, recurrence analyzer, transaction service) which must pass.

- [ ] **Step 2: Frontend — build and unit tests**

Run: `cd frontend && npm run build`
Expected: build succeeds.
Run: `cd frontend && ng test --watch=false --browsers=ChromeHeadless`
Expected: existing tests still pass.

- [ ] **Step 3: Manual smoke test (optional but recommended)**

Start backend (`mvn spring-boot:run`) and frontend (`npm start`), then:
1. Create an account under `/finance/accounts`.
2. Import a `camt.053` file on `/finance` — verify the summary counts.
3. Re-import the same file — verify all are skipped as duplicates.
4. On `/finance/transactions`, categorize a transaction and accept the rule suggestion.
5. Set a budget on `/finance/budgets` and confirm the status bar reflects spending.
6. Run recurrence detection on `/finance/recurring`.
7. Toggle Layout A/B on `/finance` and reload — the choice persists.

- [ ] **Step 4: Final commit (if any cleanup was needed)**

```bash
git add -A
git commit -m "chore(finance): final cleanup after verification"
```

---

## Notes for the implementer

- **Build with JDK 21.** Default is JDK 17; set `JAVA_HOME` accordingly before any `mvn` command.
- **Repository package.** Every JPA repository MUST be in `com.household.manager.repository` (JpaConfig restricts scanning) — already reflected in all tasks.
- **Controller base path.** Controllers map `/v1/...`; the public URL is `/api/v1/...` (context path adds `/api`). Frontend services use `/api/v1/finance/...`.
- **Signed amounts.** Expenses are negative throughout; the parser sets the sign from `CdtDbtInd`. Analytics/budgets use `.abs()` when presenting expense magnitudes.
- **Surfaced refinements** (consciously deviating from the spec, all low-risk): focused hand-written JAXB model instead of full XSD generation (Module 2); `localStorage` instead of `ApplicationSetting` for the layout toggle (Task 27).
