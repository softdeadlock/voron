package messenger.android.data

/**
 * Which color identity to render — independent of [ThemeMode]'s light/dark/system choice. Every
 * variant but [CORVID] is a dark-only identity: picking one while the resolved appearance is light
 * falls back to the ordinary Corvid light ("paper") scheme, since none of these have anything
 * distinct to say about a light background — see ui/theme/Theme.kt.
 */
enum class ThemeVariant {
    /** The default: violet/teal/bronze "corvid feather iridescence", matching the marketing site. */
    CORVID,

    /** A stricter, near-monochrome black — true-black ground, minimal violet, mostly grayscale. */
    NOIR,

    /** Warm amber/rust over a brown-black ground — coals, not fire. */
    EMBER,

    /** Cold cyan/steel-blue over a blue-black ground — ice, not sky. */
    GLACIER,

    /** Deep moss green/olive over a green-black ground — forest floor, not spring leaves. */
    MOSS,

    /**
     * Gold over near-black, with the other four brand hues folded in as secondary/tertiary accents
     * instead of a fifth unrelated color. Only ever offered to a device that already has
     * [messenger.android.data.SettingsStore.loadIsFounder] == true — see that doc for how that's
     * decided. Not selectable otherwise; [messenger.android.ui.theme.displayName] etc. still handle
     * it normally so nothing breaks if a founder device downgrades and re-upgrades.
     */
    FOUNDER;

    companion object {
        fun fromStorageKey(key: String?): ThemeVariant = entries.firstOrNull { it.name == key } ?: CORVID
    }
}
