package com.household.manager.service;

import com.household.manager.finance.DedupHasher;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time maintenance operations for the finance module.
 *
 * <p>After the dedup algorithm was changed from reference-only to composite hashing,
 * existing transactions need their {@code dedupHash} recomputed so that a subsequent
 * re-import can recognise them correctly and only insert the previously-collapsed rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinanceMaintenanceService {

    private final TransactionRepository transactionRepository;
    private final DedupHasher dedupHasher;

    /**
     * Recomputes the dedup hash for every persisted transaction using the current algorithm.
     *
     * @return the number of transactions updated
     */
    @Transactional
    public int recomputeDedupHashes() {
        List<Transaction> all = transactionRepository.findAll();
        for (Transaction t : all) {
            t.setDedupHash(dedupHasher.hash(t.getAccountId(), t));
        }
        transactionRepository.saveAll(all);
        log.info("Recomputed dedup hashes for {} transactions", all.size());
        return all.size();
    }
}
