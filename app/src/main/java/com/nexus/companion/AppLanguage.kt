package com.nexus.companion

import java.util.Locale

enum class AppLanguage(val locale: Locale, val sttCode: String, val label: String) {
    DE(Locale.GERMAN, "de-DE", "Deutsch"),
    EN(Locale.ENGLISH, "en-US", "English");
}
