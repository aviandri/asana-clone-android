package com.mekari.project.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Runs a holder action in the app scope: holders are main-confined and their actions never throw. */
fun CoroutineScope.launchAction(action: suspend () -> Unit): Job = launch { action() }

/** Runs [action] in the app scope and awaits it from the caller. If the caller goes away, the write still
 *  finishes (an add or PATCH is never abandoned halfway). */
suspend fun <T> CoroutineScope.runAction(action: suspend () -> T): T = async { action() }.await()
