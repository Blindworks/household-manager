package com.household.manager.ankersolix.api;

/**
 * Known Anker Solix Cloud API endpoints.
 * <p>
 * All paths are relative to the regional base URL.
 * Based on the Python library's {@code apitypes.py}.
 */
public final class AnkerApiEndpoints {

    private AnkerApiEndpoints() {}

    // -------------------------------------------------------------------------
    // Site / System
    // -------------------------------------------------------------------------

    /** List all power systems (sites) of the account. */
    public static final String GET_SITE_LIST =
            "power_service/v1/site/get_site_list";

    /** Detailed info for a single site. */
    public static final String GET_SITE_HOMEPAGE =
            "power_service/v1/site/get_site_homepage";

    /** Device list and live power flow for a site. Note: API uses "scen" (not "scene"). */
    public static final String GET_SCENE_INFO =
            "power_service/v1/site/get_scen_info";

    // -------------------------------------------------------------------------
    // Solarbank / Device Control
    // -------------------------------------------------------------------------

    /** Get current schedule of a solarbank. */
    public static final String GET_DEVICE_PARM =
            "power_service/v1/site/get_site_device_param";

    /** Set schedule / operating parameters for a solarbank. */
    public static final String SET_DEVICE_PARM =
            "power_service/v1/site/set_site_device_param";

    /** Get system / site parameters (e.g. grid export switch). */
    public static final String GET_SYSTEM_INFO =
            "power_service/v1/site/get_system_info";

    /** Save home load baseline for smart-meter mode. */
    public static final String SAVE_HOME_LOAD =
            "power_service/v1/site/save_home_load";

    // -------------------------------------------------------------------------
    // Energy statistics
    // -------------------------------------------------------------------------

    /** Energy stats for a specific device over a date range. */
    public static final String GET_DEVICE_ENERGY =
            "power_service/v1/device/get_device_energy";

    /** Site-level energy analysis (daily/weekly/monthly breakdown). */
    public static final String ENERGY_ANALYSIS =
            "power_service/v1/site/energy_analysis";

    /** Daily site-level energy breakdown. */
    public static final String GET_SITE_PRICE =
            "power_service/v1/site/get_site_price";

    // -------------------------------------------------------------------------
    // Device details
    // -------------------------------------------------------------------------

    /** Get device details / settings by device SN. */
    public static final String GET_DEVICE_INFO =
            "power_service/v1/device/get_device_info";

    /** Bind / register a device. */
    public static final String BIND_DEVICES =
            "power_service/v1/app/compatible/bind_devices";
}
