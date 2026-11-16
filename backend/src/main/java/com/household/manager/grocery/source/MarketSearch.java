package com.household.manager.grocery.source;

import com.household.manager.grocery.Chain;

import java.util.List;

public interface MarketSearch {

    Chain chain();

    List<MarketCandidate> search(String zip, double lat, double lng) throws SourceFetchException;
}
