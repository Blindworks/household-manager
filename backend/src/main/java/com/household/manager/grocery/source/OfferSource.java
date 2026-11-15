package com.household.manager.grocery.source;

import java.util.List;

public interface OfferSource {

    SourceKey key();

    boolean enabled();

    List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException;
}
