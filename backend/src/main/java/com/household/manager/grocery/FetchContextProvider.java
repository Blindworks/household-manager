package com.household.manager.grocery;

import com.household.manager.grocery.source.FetchContext;

/** Liefert die aktuellen Abrufeingaben (Standort, gewählte Märkte, eingeschaltete Ketten). */
public interface FetchContextProvider {

    FetchContext current();
}
