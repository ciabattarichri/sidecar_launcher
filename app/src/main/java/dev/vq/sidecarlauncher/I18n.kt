package dev.vq.sidecarlauncher

import java.util.Locale

/** UI language (IT/EN), chosen from the PC plugin. The activity restarts when it changes. */
object I18n {
    val en: Boolean get() = when (Remote.config.value.language) {
        "en" -> true
        "it" -> false
        else -> java.util.Locale.getDefault().language != "it" // "system"
    }
    val locale: Locale get() = if (en) Locale.ENGLISH else Locale.ITALIAN
}

fun tr(it: String, en: String): String = if (I18n.en) en else it
