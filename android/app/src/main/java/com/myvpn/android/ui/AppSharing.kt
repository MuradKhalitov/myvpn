package com.myvpn.android.ui

import android.content.Context
import android.content.Intent

/** Stable public links used by Android presentation actions. */
object AppLinks {
    const val LATEST_APK_URL = "https://api.myvpn05.ru/downloads/myvpn-latest.apk"
}

object AppSharing {
    fun shareText(): String = """
        MyVPN — приложение для подключения к VPN.

        Скачать последнюю версию:
        ${AppLinks.LATEST_APK_URL}
    """.trimIndent()

    fun shareApp(context: Context) {
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, shareText())
        context.startActivity(Intent.createChooser(sendIntent, "Поделиться приложением"))
    }
}
