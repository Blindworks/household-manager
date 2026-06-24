package com.household.manager.finance;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Selects the appropriate {@link StatementParser} based on the uploaded file's name.
 * Defaults to the XML CAMT parser when the filename is absent or has no recognized extension.
 */
@Component
@RequiredArgsConstructor
public class StatementParserResolver {

    private final CamtStatementParser camtStatementParser;
    private final CamtCsvStatementParser camtCsvStatementParser;

    public StatementParser resolve(String filename) {
        if (filename != null && filename.toLowerCase().endsWith(".csv")) {
            return camtCsvStatementParser;
        }
        return camtStatementParser;
    }
}
