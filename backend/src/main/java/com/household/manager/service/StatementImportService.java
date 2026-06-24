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
