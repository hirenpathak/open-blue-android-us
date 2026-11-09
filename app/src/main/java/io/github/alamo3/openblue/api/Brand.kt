package io.github.alamo3.openblue.api

/**
 * The Hyundai Motor Group brands that share the Canadian Bluelink API ("tods"): the same paths,
 * headers and payloads on a different host. Kia's host and credentials follow hyundai_kia_connect_api,
 * which sends no brand header to Kia Connect.
 */
enum class Brand(
    val label: String,
    val company: String,
    /** What the owner calls the service, for text shown in the app. */
    val serviceName: String,
    val host: String,
    /** The "brand" request header the official app sends; null to leave it out. */
    val brandHeader: String?,
    val clientId: String,
    val clientSecret: String,
    /** Hyundai's Bluelink Store (features on demand). */
    val hasStore: Boolean,
    /** Hyundai's in-app charging: public chargers, trip planning and paid-session history. */
    val hasInAppCharging: Boolean,
) {
    HYUNDAI(
        "Hyundai", "Hyundai", "Bluelink", "mybluelink.ca", "H",
        "HATAHSPACA0232141ED9722C67715A0B", "CLISCR01AHSPA", hasStore = true, hasInAppCharging = true,
    ),
    KIA(
        "Kia", "Kia", "Kia Connect", "kiaconnect.ca", null,
        "HATAHSPACA0232141ED9722C67715A0B", "CLISCR01AHSPA", hasStore = false, hasInAppCharging = false,
    ),
    /**
     * Hyundai USA: a different backend entirely (telematics API, OAuth login, PIN as a header).
     * Served by [UsaBluelinkClient], NOT by the tods paths in [BluelinkClient]; baseUrl is unused
     * for this entry. The client id/secret are the public values embedded in the MyHyundai app.
     */
    HYUNDAI_USA(
        "Hyundai USA", "Hyundai", "Bluelink", "api.telematics.hyundaiusa.com", "H",
        "m66129Bb-em93-SPAHYN-bZ91-am4540zp19920", "v558o935-6nne-423i-baa8",
        hasStore = false, hasInAppCharging = false,
    );

    val baseUrl: String
        get() = if (this == HYUNDAI_USA) "https://$host/ac/v2/" else "https://$host/tods/api/"
}
