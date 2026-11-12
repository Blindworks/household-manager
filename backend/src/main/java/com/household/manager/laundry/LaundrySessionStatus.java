package com.household.manager.laundry;

import java.util.List;

/** Lebenslauf eines Waschgangs; "offen" sind ASKING, DRYING und MAYBE_DRY. */
public enum LaundrySessionStatus {
    ASKING,
    DECLINED,
    DRYING,
    MAYBE_DRY,
    DONE,
    EXPIRED;

    public static final List<LaundrySessionStatus> OPEN = List.of(ASKING, DRYING, MAYBE_DRY);
    public static final List<LaundrySessionStatus> FINISHED = List.of(DONE, EXPIRED);
}
