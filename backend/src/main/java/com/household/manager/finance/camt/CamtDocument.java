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
