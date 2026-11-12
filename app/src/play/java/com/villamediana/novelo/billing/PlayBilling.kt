package com.villamediana.novelo.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.villamediana.novelo.ai.NoveloServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Novelo+ through Google Play: the product and its monthly plan come from what the server says
 * ([NoveloServer.Status.offer]), the purchase carries this install's code (so the server knows
 * whose it is), and what Google sold goes to the server, which checks it with Google and confirms it
 * there (an unconfirmed purchase is refunded in 3 days).
 */
class PlayBilling(context: Context, private val server: NoveloServer) : Billing {
    override val test: Boolean = false

    /** What the store's sheet answered for the purchase going on now. */
    @Volatile private var waiting: CompletableDeferred<Pair<BillingResult, List<Purchase>>>? = null
    private val lock = Mutex()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> waiting?.complete(result to purchases.orEmpty()) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun ready() {
        if (client.isReady) return
        val r = suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onBillingServiceDisconnected() {}
            })
        }
        if (r.responseCode != BillingClient.BillingResponseCode.OK) throw BillingFailed("setup ${r.responseCode}")
    }

    private fun offer(): NoveloServer.Offer = server.status.value?.offer ?: NoveloServer.Offer()

    /** The product and the offer of its monthly plan (the plain one, not a promotion). */
    private suspend fun monthly(): Pair<ProductDetails, ProductDetails.SubscriptionOfferDetails> {
        ready()
        val o = offer()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(o.product).setProductType(BillingClient.ProductType.SUBS).build()))
            .build()
        val r = withContext(Dispatchers.IO) { client.queryProductDetails(params) }
        val details = r.productDetailsList?.firstOrNull() ?: throw BillingFailed("no product ${r.billingResult.responseCode}")
        val offers = details.subscriptionOfferDetails.orEmpty()
        val pick = offers.firstOrNull { it.basePlanId == o.basePlan && it.offerId == null }
            ?: offers.firstOrNull { it.basePlanId == o.basePlan }
            ?: offers.firstOrNull()
            ?: throw BillingFailed("no offer")
        return details to pick
    }

    override suspend fun price(): String? = runCatching {
        monthly().second.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice
    }.getOrNull()

    override suspend fun subscribe(activity: Activity): NoveloServer.Status = lock.withLock {
        val (details, offer) = monthly()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offer.offerToken).build()))
            .setObfuscatedAccountId(server.install)
            .build()
        val answer = CompletableDeferred<Pair<BillingResult, List<Purchase>>>()
        waiting = answer
        try {
            val started = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
            if (started.responseCode != BillingClient.BillingResponseCode.OK) throw BillingFailed("launch ${started.responseCode}")
            val (result, purchases) = answer.await()
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> Unit
                BillingClient.BillingResponseCode.USER_CANCELED -> throw BillingCancelled()
                // Bought before (another phone, reinstalled): it is hers, the server takes it again.
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> return@withLock restore() ?: throw BillingFailed("owned")
                else -> throw BillingFailed("buy ${result.responseCode}")
            }
            val bought = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                ?: throw BillingFailed("pending")
            withTimeout(60_000) { server.purchase("play", bought.purchaseToken) }
        } finally {
            waiting = null
        }
    }

    override suspend fun restore(): NoveloServer.Status? = runCatching {
        ready()
        val r = withContext(Dispatchers.IO) {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        }
        val own = r.purchasesList.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED } ?: return@runCatching null
        server.purchase("play", own.purchaseToken)
    }.getOrNull()
}
