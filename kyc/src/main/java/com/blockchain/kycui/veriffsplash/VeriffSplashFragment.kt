package com.blockchain.kycui.veriffsplash

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.support.annotation.DrawableRes
import android.support.annotation.IdRes
import android.support.design.widget.BottomSheetDialog
import android.support.graphics.drawable.VectorDrawableCompat
import android.support.v4.content.ContextCompat
import android.support.v4.graphics.drawable.DrawableCompat
import android.support.v7.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment.findNavController
import com.blockchain.kyc.models.nabu.SupportedDocuments
import com.blockchain.kyc.services.nabu.VeriffApplicantAndToken
import com.blockchain.kycui.navhost.KycProgressListener
import com.blockchain.kycui.navhost.models.KycStep
import com.blockchain.notifications.analytics.LoggableEvent
import com.blockchain.notifications.analytics.logEvent
import com.blockchain.ui.extensions.throttledClicks
import com.google.firebase.FirebaseApp
import com.onfido.android.sdk.capture.DocumentType
import io.reactivex.Observable
import mobi.lab.veriff.data.ColorSchema
import mobi.lab.veriff.data.Veriff
import org.koin.android.ext.android.inject
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import piuk.blockchain.androidcoreui.ui.base.BaseFragment
import piuk.blockchain.androidcoreui.ui.customviews.MaterialProgressDialog
import piuk.blockchain.androidcoreui.ui.customviews.ToastCustom
import piuk.blockchain.androidcoreui.utils.ParentActivityDelegate
import piuk.blockchain.androidcoreui.utils.extensions.getResolvedColor
import piuk.blockchain.androidcoreui.utils.extensions.inflate
import piuk.blockchain.androidcoreui.utils.extensions.toast
import piuk.blockchain.androidcoreui.utils.extensions.visible
import piuk.blockchain.kyc.R
import timber.log.Timber
import kotlinx.android.synthetic.main.fragment_kyc_veriff_splash.button_kyc_veriff_splash_next as buttonNext

class VeriffSplashFragment : BaseFragment<VeriffSplashView, VeriffSplashPresenter>(),
    VeriffSplashView {

    private val presenter: VeriffSplashPresenter by inject()
    private val progressListener: KycProgressListener by ParentActivityDelegate(this)
    private val countryCode by unsafeLazy { VeriffSplashFragmentArgs.fromBundle(arguments).countryCode }
    private var progressDialog: MaterialProgressDialog? = null
    override val uiState: Observable<String>
        get() = buttonNext.throttledClicks()
            .map { countryCode }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = container?.inflate(R.layout.fragment_kyc_veriff_splash)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logEvent(LoggableEvent.KycVerifyIdentity)

        progressListener.setHostTitle(R.string.kyc_onfido_splash_title)
        progressListener.incrementProgress(KycStep.VeriffSplashPage)

        onViewReady()
    }

    override fun showProgressDialog(cancelable: Boolean) {
        progressDialog = MaterialProgressDialog(activity).apply {
            setOnCancelListener { presenter.onProgressCancelled() }
            setMessage(R.string.kyc_country_selection_please_wait)
            setCancelable(cancelable)
            show()
        }
    }

    override fun dismissProgressDialog() {
        progressDialog?.apply { dismiss() }
        progressDialog = null
    }

    @SuppressLint("InflateParams")
    override fun continueToVeriff(
        applicant: VeriffApplicantAndToken,
        supportedDocuments: List<SupportedDocuments>
    ) {
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        val sheetView = requireActivity().layoutInflater.inflate(R.layout.bottom_sheet_onfido, null)

        supportedDocuments
            .map { it.toUiData() }
            .forEach {
                sheetView.findViewById<TextView>(it.textView)
                    .apply {
                        visible()
                        setLeftDrawable(it.icon)
                        launchVeriffOnClick(
                            applicant,
                            bottomSheetDialog
                        )
                    }
            }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun launchVeriff(applicant: VeriffApplicantAndToken) {
        val sessionToken = applicant.token
        Timber.d("Veriff session token: $sessionToken")
        // enable logging for the library
        // Veriff.setLoggingImplementation(Log.getInstance(MainActivity::class.java))
        val veriffSDK = Veriff.Builder("https://magic.veriff.me/v1/", sessionToken)
        // If this not specified, then reverts to the default colors
        val schema = ColorSchema.Builder()
            .setControlsColor(ContextCompat.getColor(requireContext(), R.color.primary_blue_accent))
            .build()
        veriffSDK.setCustomColorSchema(schema)
        // If not specified, then default gradient is used
        veriffSDK.setBackgroundImage(R.drawable.city_tartu)

        FirebaseApp.initializeApp(requireContext().applicationContext)
        veriffSDK.launch(requireActivity(), REQUEST_CODE_VERIFF)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_VERIFF) {
            Timber.d("Veriff result code $resultCode")
            if (resultCode == RESULT_OK) {
                presenter.submitVerification()
            }
        }
    }

    override fun showErrorToast(message: Int) {
        toast(message, ToastCustom.TYPE_ERROR)
    }

    override fun continueToCompletion() {
        val navOptions = NavOptions.Builder()
            .setPopUpTo(R.id.kyc_nav_xml, true)
            .build()
        findNavController(this).navigate(R.id.applicationCompleteFragment, null, navOptions)
    }

    override fun createPresenter(): VeriffSplashPresenter = presenter

    override fun getMvpView(): VeriffSplashView = this

    private fun TextView.launchVeriffOnClick(
        applicant: VeriffApplicantAndToken,
        bottomSheetDialog: BottomSheetDialog
    ) {
        this.setOnClickListener {
            launchVeriff(applicant)
            bottomSheetDialog.cancel()
        }
    }

    private fun TextView.setLeftDrawable(@DrawableRes drawable: Int) {
        VectorDrawableCompat.create(
            resources,
            drawable,
            ContextThemeWrapper(requireActivity(), R.style.AppTheme).theme
        )?.run {
            DrawableCompat.wrap(this)
            DrawableCompat.setTint(this, getResolvedColor(R.color.primary_gray_medium))
            this@setLeftDrawable.setCompoundDrawablesWithIntrinsicBounds(this, null, null, null)
        }
    }

    private fun SupportedDocuments.toUiData(): SupportedDocumentUiData = when (this) {
        SupportedDocuments.PASSPORT -> SupportedDocumentUiData(
            R.drawable.vector_plane,
            R.id.text_view_document_passport,
            DocumentType.PASSPORT
        )
        SupportedDocuments.DRIVING_LICENCE -> SupportedDocumentUiData(
            R.drawable.vector_car,
            R.id.text_view_document_drivers_license,
            DocumentType.DRIVING_LICENCE
        )
        SupportedDocuments.NATIONAL_IDENTITY_CARD -> SupportedDocumentUiData(
            R.drawable.vector_government,
            R.id.text_view_document_id_card,
            DocumentType.NATIONAL_IDENTITY_CARD
        )
    }

    private data class SupportedDocumentUiData(
        @DrawableRes val icon: Int,
        @IdRes val textView: Int,
        val documentType: DocumentType
    )

    companion object {

        private const val REQUEST_CODE_VERIFF = 1440
    }
}