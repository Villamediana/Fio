package com.villamediana.novelo.billing

import android.app.Activity
import com.villamediana.novelo.ai.NoveloServer

/**
 * Buying Novelo+. The Play build asks Google Play (its own sheet, its own price) and hands the
 * purchase to the Novelo's server, which checks it with Google; the GitHub build simulates it (the
 * same screens, nothing charged), for testing everything. [com.villamediana.novelo.Store] gives the
 * one of each build.
 */
interface Billing {
    /** A simulated purchase (the GitHub build): the screen says nothing is charged. */
    val test: Boolean

    /** The monthly price as the store shows it ("R$ 14,90"); null when the store can't say now. */
    suspend fun price(): String?

    /** Buys Novelo+ and gives the server's new status; [BillingCancelled] when she backs out. */
    suspend fun subscribe(activity: Activity): NoveloServer.Status

    /** What she already bought (another phone, reinstalled), handed to the server again; null when nothing. */
    suspend fun restore(): NoveloServer.Status?
}

/** She closed the store's sheet without buying. */
class BillingCancelled : Exception("cancelled")

/** The store couldn't sell now ([reason]). */
class BillingFailed(reason: String) : Exception(reason)
