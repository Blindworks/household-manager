package com.household.manager.finance;

import java.io.InputStream;

/** Strategy for parsing a bank statement file into a {@link ParsedStatement}. */
public interface StatementParser {
    ParsedStatement parse(InputStream in);
}
