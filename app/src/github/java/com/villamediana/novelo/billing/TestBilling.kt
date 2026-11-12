package com.villamediana.novelo.billing

import android.app.Activity
import com.villamediana.novelo.R
import com.villamediana.novelo.ai.NoveloServer
import com.villamediana.novelo.settings.t

/**
 * The GitHub build's Novelo+: the same screens as the Play build, the purchase simulated — the
 * server takes it as a test purchase (while the panel allows it) and nothing is charged.
 */
class TestBilling(private val server: NoveloServer) : Billing {
    override val test: Boolean = true

    override suspend fun price(): String = t(R.string.plans_test_price)

    override suspend fun subscribe(activity: Activity): NoveloServer.Status = server.purchase("test")

    override suspend fun restore(): NoveloServer.Status? = null
}
