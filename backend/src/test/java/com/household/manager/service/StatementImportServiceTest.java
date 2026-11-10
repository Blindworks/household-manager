package com.household.manager.service;

import com.household.manager.dto.ImportSummaryResponse;
import com.household.manager.finance.DedupHasher;
import com.household.manager.finance.ParsedStatement;
import com.household.manager.finance.StatementParser;
import com.household.manager.finance.StatementParserResolver;
import com.household.manager.model.entity.BankAccount;
import com.household.manager.model.entity.ImportBatch;
import com.household.manager.repository.BankAccountRepository;
import com.household.manager.repository.ImportBatchRepository;
import com.household.manager.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementImportServiceTest {

    private static final Long ACCOUNT_ID = 7L;

    private RecurringDetectionService detectionService;
    private StatementImportService service;

    @BeforeEach
    void setUp() {
        StatementParserResolver resolver = mock(StatementParserResolver.class);
        StatementParser parser = mock(StatementParser.class);
        when(resolver.resolve(any())).thenReturn(parser);
        when(parser.parse(any())).thenReturn(ParsedStatement.builder().transactions(List.of()).build());

        CategorizationService categorization = mock(CategorizationService.class);
        when(categorization.loadActiveRules()).thenReturn(List.of());

        ImportBatchRepository batchRepository = mock(ImportBatchRepository.class);
        when(batchRepository.save(any(ImportBatch.class))).thenAnswer(i -> {
            ImportBatch batch = i.getArgument(0);
            batch.setId(1L);
            return batch;
        });

        BankAccountRepository accountRepository = mock(BankAccountRepository.class);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(new BankAccount()));

        detectionService = mock(RecurringDetectionService.class);
        service = new StatementImportService(resolver, mock(DedupHasher.class), categorization,
                mock(TransactionRepository.class), batchRepository, accountRepository, detectionService);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void detectionRunsAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        ImportSummaryResponse summary = runImport();

        verify(detectionService, never()).detect(any());
        triggerAfterCommit();
        verify(detectionService, times(1)).detect(ACCOUNT_ID);
        org.junit.jupiter.api.Assertions.assertNotNull(summary);
    }

    @Test
    void detectionFailureDoesNotBreakImport() {
        TransactionSynchronizationManager.initSynchronization();
        when(detectionService.detect(any())).thenThrow(new RuntimeException("boom"));

        runImport();

        assertDoesNotThrow(this::triggerAfterCommit);
        verify(detectionService, times(1)).detect(ACCOUNT_ID);
    }

    @Test
    void withoutTransactionDetectionRunsImmediately() {
        when(detectionService.detect(any())).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(this::runImport);

        verify(detectionService, times(1)).detect(ACCOUNT_ID);
    }

    private ImportSummaryResponse runImport() {
        return service.importStatement(ACCOUNT_ID, "file.xml", new ByteArrayInputStream(new byte[0]));
    }

    private void triggerAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }
}
