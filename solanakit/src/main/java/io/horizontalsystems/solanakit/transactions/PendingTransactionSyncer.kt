package io.horizontalsystems.solanakit.transactions

import android.util.Log
import com.solana.api.Api
import com.solana.models.ConfirmedTransaction
import com.solana.rxsolana.api.getBlockHeight
import com.solana.rxsolana.api.getConfirmedTransaction
import com.solana.rxsolana.api.getSlot
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.sol4k.RpcUrl
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.logging.Logger

class PendingTransactionSyncer(
    private val rpcClient: Api,
    private val storage: TransactionStorage,
    private val transactionManager: TransactionManager
) {
    private val logger = Logger.getLogger("PendingTransactionSyncer")

    val web3j by lazy {
        val builder = HttpService.getOkHttpClientBuilder()
        Web3j.build(HttpService("https://solana-mainnet.core.chainstack.com/35d90e70a574359e50b11e10b5460414", builder.build()))
    }

    suspend fun sync() {
        val updatedTransactions = mutableListOf<Transaction>()

        val pendingTransactions = storage.pendingTransactions()
        val currentBlockHeight = try {
            rpcClient.getBlockHeight().await()
        } catch (error: Throwable) {
            return
        }

        pendingTransactions.forEach { pendingTx ->
            try {
                    val params: MutableList<Any> = ArrayList()
                    params.add(pendingTx.hash)
                    rpcClient.router.request<ConfirmedTransaction>("getTransaction", params, ConfirmedTransaction::class.java) {
                        Log.e("longwen", "isSuccess=${it.isSuccess}")
                        Log.e("longwen", "result=${it.getOrNull()}")
                        if (it.isSuccess) {
                            it.getOrNull()?.meta?.let { meta ->
                                updatedTransactions.add(
                                    pendingTx.copy(pending = false, error = meta.err?.toString())
                                )
                            }
                            storage.updateTransactions(updatedTransactions)
                            GlobalScope.launch {
                                transactionManager.notifyTransactionsUpdate(storage.getFullTransactions(updatedTransactions.map { it.hash }))
                            }
                        }
                    }

                /*val confirmedTransaction = withTimeout(2000) {
                    rpcClient.getConfirmedTransaction(pendingTx.hash).await()
                }

                confirmedTransaction.meta?.let { meta ->
                    updatedTransactions.add(
                        pendingTx.copy(pending = false, error = meta.err?.toString())
                    )
                }*/

            } catch (error: Throwable) {
                if (currentBlockHeight <= pendingTx.lastValidBlockHeight) {
                    sendTransaction(pendingTx.base64Encoded)

                    updatedTransactions.add(
                        pendingTx.copy(retryCount = pendingTx.retryCount + 1)
                    )
                } else {
                    updatedTransactions.add(
                        pendingTx.copy(pending = false, error = "BlockHash expired")
                    )
                }

                logger.info("getConfirmedTx exception ${error.message ?: error.javaClass.simpleName}")
            }
        }

        storage.updateTransactions(updatedTransactions)
        transactionManager.notifyTransactionsUpdate(storage.getFullTransactions(updatedTransactions.map { it.hash }))
    }

    private fun sendTransaction(encodedTransaction: String) {
        try {
            val connection = URL(RpcUrl.MAINNNET.value).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use {

                val body = "{" +
                        "\"method\": \"sendTransaction\", " +
                        "\"jsonrpc\": \"2.0\", " +
                        "\"id\": ${System.currentTimeMillis()}, " +
                        "\"params\": [" +
                        "\"$encodedTransaction\", " +
                        "{" +
                        "\"encoding\": \"base64\"," +
                        "\"skipPreflight\": false," +
                        "\"preflightCommitment\": \"confirmed\"," +
                        "\"maxRetries\": 0" +
                        "}" +
                        "]" +
                        "}"

                it.write(body.toByteArray())
            }
            val responseBody = connection.inputStream.use {
                BufferedReader(InputStreamReader(it)).use { reader ->
                    reader.readText()
                }
            }
            connection.disconnect()
        } catch (e: Throwable) {
        }
    }

}
