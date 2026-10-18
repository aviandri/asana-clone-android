package com.mekari.project.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/** "Open in browser" (SPEC-ANDROID §5): a Custom Tab when a browser provides them, else a plain VIEW intent. */
object Browser {
    fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        val provider = CustomTabsClient.getPackageName(context, null)
        try {
            if (provider != null) {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: ActivityNotFoundException) {
            // No browser at all: nothing sensible to do on this device.
        }
    }
}
