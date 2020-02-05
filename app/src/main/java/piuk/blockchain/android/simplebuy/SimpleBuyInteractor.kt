package piuk.blockchain.android.simplebuy

import com.blockchain.swap.nabu.NabuToken
import com.blockchain.swap.nabu.datamanagers.CustodialWalletManager
import com.blockchain.swap.nabu.models.nabu.Kyc2TierState
import com.blockchain.swap.nabu.service.TierService
import com.blockchain.ui.trackLoading
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.FiatValue
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.rxkotlin.zipWith
import piuk.blockchain.android.util.AppUtil
import java.lang.IllegalStateException
import java.util.concurrent.TimeUnit

class SimpleBuyInteractor(
    private val tierService: TierService,
    private val custodialWalletManager: CustodialWalletManager,
    private val nabu: NabuToken,
    private val appUtil: AppUtil
) {

    fun fetchBuyLimitsAndSupportedCryptoCurrencies(targetCurrency: String):
            Single<SimpleBuyIntent.UpdatedBuyLimitsAndSupportedCryptoCurrencies> =
        nabu.fetchNabuToken()
            .flatMap { custodialWalletManager.getBuyLimitsAndSupportedCryptoCurrencies(it, targetCurrency) }
            .map { SimpleBuyIntent.UpdatedBuyLimitsAndSupportedCryptoCurrencies(it) }
            .trackLoading(appUtil.activityIndicator)

    fun fetchPredefinedAmounts(targetCurrency: String): Single<SimpleBuyIntent.UpdatedPredefinedAmounts> =
        custodialWalletManager.getPredefinedAmounts(targetCurrency)
            .map {
                SimpleBuyIntent.UpdatedPredefinedAmounts(it.sortedBy { value ->
                    value.valueMinor
                })
            }
            .trackLoading(appUtil.activityIndicator)

    fun cancelOrder(): Single<SimpleBuyIntent.OrderCanceled> =
        Single.just(SimpleBuyIntent.OrderCanceled)

    fun createOrder(cryptoCurrency: CryptoCurrency?, amount: FiatValue?): Single<SimpleBuyIntent.OrderCreated> =
        custodialWalletManager.createOrder(
            cryptoCurrency = cryptoCurrency ?: throw IllegalStateException("Missing Cryptocurrency "),
            action = "BUY",
            amount = amount ?: throw IllegalStateException("Missing amount ")
        ).map {
            SimpleBuyIntent.OrderCreated(id = it.id, expirationDate = it.expiresAt, orderState = it.state)
        }.trackLoading(appUtil.activityIndicator)

    fun fetchBankAccount(): Single<SimpleBuyIntent.BankAccountUpdated> =
        custodialWalletManager.getBankAccount().map {
            SimpleBuyIntent.BankAccountUpdated(it)
        }

    fun fetchQuote(cryptoCurrency: CryptoCurrency?, amount: FiatValue?): Single<SimpleBuyIntent.QuoteUpdated> =
        custodialWalletManager.getQuote(
            crypto = cryptoCurrency ?: throw IllegalStateException("Missing Cryptocurrency "),
            action = "BUY",
            amount = amount ?: throw IllegalStateException("Missing amount ")).map {
            SimpleBuyIntent.QuoteUpdated(it)
        }

    fun pollForKycState(): Single<SimpleBuyIntent.KycStateUpdated> =
        tierService.tiers().map {
            when {
                it.combinedState == Kyc2TierState.Tier2Approved ->
                    return@map SimpleBuyIntent.KycStateUpdated(KycState.VERIFIED)
                it.combinedState.isRejectedOrInReview() -> return@map SimpleBuyIntent.KycStateUpdated(KycState.FAILED)
                else -> return@map SimpleBuyIntent.KycStateUpdated(KycState.PENDING)
            }
        }.onErrorReturn {
            SimpleBuyIntent.KycStateUpdated(KycState.PENDING)
        }
            .repeatWhen { it.delay(5, TimeUnit.SECONDS).zipWith(Flowable.range(0, 6)) }
            .takeUntil { it.kycState != KycState.PENDING }
            .last(SimpleBuyIntent.KycStateUpdated(KycState.PENDING))
            .map {
                if (it.kycState == KycState.PENDING) {
                    return@map SimpleBuyIntent.KycStateUpdated(KycState.UNDECIDED)
                } else {
                    return@map it
                }
            }

    fun checkTierLevel(): Single<SimpleBuyIntent.KycStateUpdated> = tierService.tiers().map {
        when (it.combinedState) {
            Kyc2TierState.Tier2Approved -> SimpleBuyIntent.KycStateUpdated(KycState.VERIFIED)
            else -> SimpleBuyIntent.KycStateUpdated(KycState.PENDING)
        }
    }.onErrorReturn { SimpleBuyIntent.KycStateUpdated(KycState.PENDING) }

    private fun Kyc2TierState.isRejectedOrInReview(): Boolean =
        this == Kyc2TierState.Tier1Failed ||
                this == Kyc2TierState.Tier1InReview ||
                this == Kyc2TierState.Tier2InReview ||
                this == Kyc2TierState.Tier2Failed
}