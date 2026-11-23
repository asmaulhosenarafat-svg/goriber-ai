package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class PlayBillingSnapshot(
    val playStoreInstalled: Boolean = false,
    val billingConnected: Boolean = false,
    val statusMessage: String = "Connecting to Google Play Billing...",
    val activeProductIds: Set<String> = emptySet(),
    val loadedProducts: Map<String, ProductDetails> = emptyMap()
)

class PlayBillingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onVerifiedPlayPurchase: (productId: String, orderId: String, purchaseToken: String) -> Unit,
    private val onBillingStatusMessage: (String) -> Unit
) : PurchasesUpdatedListener {

    private val _snapshot = MutableStateFlow(
        PlayBillingSnapshot(playStoreInstalled = isPlayStoreInstalled())
    )
    val snapshot: StateFlow<PlayBillingSnapshot> = _snapshot.asStateFlow()

    private var productIdsToQuery: List<String> = listOf(
        "goriber_ai_7d",
        "goriber_ai_30d",
        "goriber_ai_90d",
        "goriber_ai_180d",
        "goriber_ai_365d",
        "goriber_ai_lifetime"
    )

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    fun startConnection(productIds: List<String>? = null) {
        if (!productIds.isNullOrEmpty()) {
            productIdsToQuery = productIds.filter { it.isNotBlank() }.distinct()
        }
        val playInstalled = isPlayStoreInstalled()
        if (!playInstalled) {
            _snapshot.value = _snapshot.value.copy(
                playStoreInstalled = false,
                billingConnected = false,
                statusMessage = "Google Play Store app is not detected on this device/emulator."
            )
            return
        }
        if (billingClient.isReady) {
            scope.launch {
                refreshProductsAndPurchases()
            }
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _snapshot.value = _snapshot.value.copy(
                        playStoreInstalled = true,
                        billingConnected = true,
                        statusMessage = "Connected to Google Play Billing."
                    )
                    scope.launch {
                        refreshProductsAndPurchases()
                    }
                } else {
                    _snapshot.value = _snapshot.value.copy(
                        playStoreInstalled = playInstalled,
                        billingConnected = false,
                        statusMessage = "Play Billing unavailable (${billingResult.responseCode}): ${billingResult.debugMessage}"
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                _snapshot.value = _snapshot.value.copy(
                    billingConnected = false,
                    statusMessage = "Google Play Billing disconnected."
                )
            }
        })
    }

    suspend fun refreshProductsAndPurchases() {
        if (!billingClient.isReady) return
        val ids = productIdsToQuery.ifEmpty { return }

        val subsProducts = ids.filterNot { it.contains("lifetime", ignoreCase = true) }
        val inappProducts = ids.filter { it.contains("lifetime", ignoreCase = true) }

        val detailsMap = mutableMapOf<String, ProductDetails>()

        if (subsProducts.isNotEmpty()) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    subsProducts.map { pid ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(pid)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build()
                    }
                )
                .build()
            val res = billingClient.queryProductDetails(params)
            if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                res.productDetailsList?.forEach { detailsMap[it.productId] = it }
            }
        }

        if (inappProducts.isNotEmpty()) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    inappProducts.map { pid ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(pid)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    }
                )
                .build()
            val res = billingClient.queryProductDetails(params)
            if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                res.productDetailsList?.forEach { detailsMap[it.productId] = it }
            }
        }

        // Also query INAPP for any product IDs not returned as SUBS (in case Play Console configures 7d/30d as one-time passes)
        val remainingIds = ids.filterNot { detailsMap.containsKey(it) }
        if (remainingIds.isNotEmpty()) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    remainingIds.map { pid ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(pid)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    }
                )
                .build()
            val res = billingClient.queryProductDetails(params)
            if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                res.productDetailsList?.forEach { detailsMap[it.productId] = it }
            }
        }

        val activeIds = mutableSetOf<String>()
        val subsPurchases = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )
        if (subsPurchases.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            for (p in subsPurchases.purchasesList) {
                handlePurchase(p, activeIds)
            }
        }

        val inappPurchases = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        )
        if (inappPurchases.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            for (p in inappPurchases.purchasesList) {
                handlePurchase(p, activeIds)
            }
        }

        val status = if (detailsMap.isEmpty()) {
            "Play Billing connected. No live Play Console products found for IDs (${ids.joinToString(", ")}). Publish products in Play Console to enable Google Play checkout."
        } else {
            "Loaded ${detailsMap.size} Google Play product(s). Active Play entitlements: ${activeIds.size}."
        }

        _snapshot.value = _snapshot.value.copy(
            billingConnected = true,
            loadedProducts = detailsMap,
            activeProductIds = activeIds,
            statusMessage = status
        )
    }

    fun launchPurchase(activity: Activity, productId: String): JSONObject {
        if (productId.isBlank()) {
            return JSONObject()
                .put("started", false)
                .put("message", "No Google Play Product ID is assigned to this package.")
        }
        if (!billingClient.isReady) {
            startConnection()
            return JSONObject()
                .put("started", false)
                .put("message", _snapshot.value.statusMessage)
        }
        val details = _snapshot.value.loadedProducts[productId]
            ?: return JSONObject()
                .put("started", false)
                .put(
                    "message",
                    "Product '$productId' is not live in Google Play Console yet. Create '$productId' in Play Console Monetization > Products/Subscriptions to accept real Play Store payments."
                )

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        if (details.productType == BillingClient.ProductType.SUBS) {
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken.isNullOrBlank()) {
                return JSONObject()
                    .put("started", false)
                    .put("message", "No active subscription base plan/offer found for '$productId'.")
            }
            productParamsBuilder.setOfferToken(offerToken)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            JSONObject().put("started", true).put("message", "Google Play checkout opened.")
        } else {
            JSONObject().put("started", false).put("message", "Play Billing error (${result.responseCode}): ${result.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    scope.launch {
                        val activeIds = _snapshot.value.activeProductIds.toMutableSet()
                        for (purchase in purchases) {
                            handlePurchase(purchase, activeIds, triggerVerifyCallback = true)
                        }
                        _snapshot.value = _snapshot.value.copy(activeProductIds = activeIds)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                onBillingStatusMessage("Google Play purchase was cancelled.")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                scope.launch { refreshProductsAndPurchases() }
                onBillingStatusMessage("Item is already owned on Google Play. Restoring purchases...")
            }
            else -> {
                onBillingStatusMessage("Play Billing response (${billingResult.responseCode}): ${billingResult.debugMessage}")
            }
        }
    }

    private suspend fun handlePurchase(
        purchase: Purchase,
        activeIds: MutableSet<String>,
        triggerVerifyCallback: Boolean = false
    ) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(ackParams)
            }
            purchase.products.forEach { pid ->
                activeIds.add(pid)
                if (triggerVerifyCallback) {
                    onVerifiedPlayPurchase(
                        pid,
                        purchase.orderId ?: "GP-${System.currentTimeMillis()}",
                        purchase.purchaseToken
                    )
                }
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            onBillingStatusMessage("Google Play payment is pending completion.")
        }
    }

    fun restorePurchases() {
        if (!billingClient.isReady) {
            startConnection()
            onBillingStatusMessage("Reconnecting to Google Play Billing...")
            return
        }
        scope.launch {
            refreshProductsAndPurchases()
            onBillingStatusMessage(_snapshot.value.statusMessage)
        }
    }

    fun openPlayStoreListing(activity: Activity) {
        val pkg = context.packageName
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
            setPackage("com.android.vending")
        }
        try {
            activity.startActivity(marketIntent)
        } catch (_: Exception) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
            )
            activity.startActivity(webIntent)
        }
    }

    fun openPlaySubscriptions(activity: Activity) {
        val pkg = context.packageName
        val uri = Uri.parse("https://play.google.com/store/account/subscriptions?package=$pkg")
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun openPlayStoreHome(activity: Activity) {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Goriber+AI"))
        try {
            activity.startActivity(marketIntent)
        } catch (_: Exception) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps")))
        }
    }

    fun toJson(): JSONObject {
        val snap = _snapshot.value
        val productsJson = JSONArray()
        snap.loadedProducts.values.forEach { pd ->
            val priceText = pd.oneTimePurchaseOfferDetails?.formattedPrice
                ?: pd.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                ?: ""
            productsJson.put(JSONObject().apply {
                put("productId", pd.productId)
                put("title", pd.title)
                put("type", pd.productType)
                put("formattedPrice", priceText)
            })
        }
        return JSONObject().apply {
            put("playStoreInstalled", snap.playStoreInstalled)
            put("billingConnected", snap.billingConnected)
            put("statusMessage", snap.statusMessage)
            put("activeProductIds", JSONArray(snap.activeProductIds.toList()))
            put("loadedProducts", productsJson)
        }
    }

    private fun isPlayStoreInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo("com.android.vending", PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.android.vending", 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun endConnection() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}
