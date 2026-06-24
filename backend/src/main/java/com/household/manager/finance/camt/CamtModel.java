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
