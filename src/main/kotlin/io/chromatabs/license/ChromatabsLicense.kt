package io.chromatabs.license

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.LicensingFacade

/**
 * JetBrains Marketplace licence check for the "Paid" model (§0 decision: perpetual, one-time
 * purchase — no subscription/fallback logic needed here, that's Marketplace-side, not ours).
 *
 * [PRODUCT_CODE] must match plugin.xml's `<product-descriptor code="">` exactly once that tag is
 * added — see the README's "Marketplace publishing prep" section for why it isn't added yet.
 *
 * @see <a href="https://plugins.jetbrains.com/docs/marketplace/add-marketplace-license-verification-calls-to-the-plugin-code.html">
 *   Add license verification calls to the plugin code</a>
 */
object ChromatabsLicense {

    const val PRODUCT_CODE = "PCHROMATABS"

    private const val KEY_PREFIX = "key:"
    private const val STAMP_PREFIX = "stamp:"

    /**
     * `null` means [LicensingFacade] hasn't finished initialising yet — this happens early during
     * IDE startup, and JetBrains' own guidance is that interpreting it is up to the plugin. Callers
     * should treat `null` as "allowed": there's no positive evidence of a missing licence yet, and
     * refusing here would incorrectly block a licensed user during their IDE's first second alive.
     */
    fun isLicensed(): Boolean? {
        val facade = LicensingFacade.getInstance() ?: return null
        val stamp = facade.getConfirmationStamp(PRODUCT_CODE) ?: return false
        return when {
            stamp.startsWith(KEY_PREFIX) -> LicenseSignatureVerification.isKeyValid(stamp.removePrefix(KEY_PREFIX))
            stamp.startsWith(STAMP_PREFIX) -> LicenseSignatureVerification.isLicenseServerStampValid(stamp.removePrefix(STAMP_PREFIX))
            else -> false
        }
    }

    /** Opens the IDE's own registration dialog, pre-selected to [PRODUCT_CODE]. */
    fun requestLicense(message: String?) {
        ApplicationManager.getApplication().invokeLater(
            { showRegisterDialog(message) },
            ModalityState.nonModal(),
        )
    }

    private fun showRegisterDialog(message: String?) {
        val actionManager = ActionManager.getInstance()
        // "RegisterPlugins" in the open-source IDE, "Register" in commercial distributions.
        val registerAction = actionManager.getAction("RegisterPlugins") ?: actionManager.getAction("Register") ?: return
        val dataContext = DataContext { dataId ->
            when (dataId) {
                "register.product-descriptor.code" -> PRODUCT_CODE
                "register.message" -> message
                else -> null
            }
        }
        // createFromDataContext + actionPerformed, not the newer ActionUtil.performAction(..., ActionUiKind):
        // that overload only exists from platform 243 (confirmed absent in the 242 jars), and 242 is our floor.
        registerAction.actionPerformed(AnActionEvent.createFromDataContext("", Presentation(), dataContext))
    }
}
