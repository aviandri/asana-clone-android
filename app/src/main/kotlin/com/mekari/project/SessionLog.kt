package com.mekari.project

import android.util.Log

/**
 * Device diagnostics for QA (A5 sessions, A6 background checks), at INFO so `adb logcat -s MekariSession
 * MekariInbox` shows them. Reasons and counts only: never a token, sid, key material or user content.
 */
internal object SessionLog {
    const val TAG = "MekariSession"
    const val INBOX_TAG = "MekariInbox"

    fun session(message: String) {
        Log.i(TAG, message)
    }

    fun inbox(message: String) {
        Log.i(INBOX_TAG, message)
    }
}
