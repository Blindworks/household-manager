package com.household.manager.blaulicht.source;

import com.household.manager.blaulicht.BlaulichtSourceKey;

import java.util.List;

/**
 * Adapter einer Blaulicht-Quelle. Liefert nur Meldungen, die noch nicht verarbeitet wurden; Aufteilen,
 * Ortsfilter und Kategorien sind Sache von {@code BlaulichtProcessor}.
 */
public interface BlaulichtSource {

    BlaulichtSourceKey key();

    boolean enabled();

    /** @throws BlaulichtFetchException wenn die Quelle als Ganzes nicht lesbar war */
    List<RawReport> fetchNew() throws BlaulichtFetchException;
}
