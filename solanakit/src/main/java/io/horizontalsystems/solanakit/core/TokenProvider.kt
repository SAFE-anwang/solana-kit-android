package io.horizontalsystems.solanakit.core

import io.horizontalsystems.solanakit.transactions.JupiterApiService
import io.horizontalsystems.solanakit.transactions.SolanaFmService

class TokenProvider(private val jupiterApiService: SolanaFmService) {

    suspend fun getTokenInfo(mintAddress: String) = jupiterApiService.tokenInfo(mintAddress)

}
