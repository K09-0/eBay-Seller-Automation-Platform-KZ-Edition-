package com.kz.ebayautomation
object EbayApi {
    const val BASE_URL = "https://api.ebay.com/"
    suspend fun createListing(titleKz: String, priceKzt: String): String {
        val priceUsd = (priceKzt.toDoubleOrNull() ?: 0.0) / 470.0
        return "Listing: $titleKz -> $${"%.2f".format(priceUsd)}"
    }
}
