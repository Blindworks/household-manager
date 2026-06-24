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
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("Unknown account id: " + id);
        }
        repository.deleteById(id);
    }

    private BankAccountResponse toResponse(BankAccount a) {
        return BankAccountResponse.builder()
                .id(a.getId()).name(a.getName()).iban(a.getIban()).currency(a.getCurrency())
                .build();
    }
}
