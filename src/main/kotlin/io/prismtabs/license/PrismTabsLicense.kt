package io.prismtabs.license

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.LicensingFacade

/**
 * JetBrains Marketplace licence check for the Freemium + Yearly Subscription (no fallback) model
 * — see the README's "Marketplace publishing prep" section for the decision and the free/paid
 * feature split. Subscription expiry is handled by the signed stamp itself, so [isLicensed] needs
 * no scheme-specific logic; what freemium does change is the shape of the call sites. Callers gate
 * individual paid features (tool window, collapse/expand, active-group auto-join, palette editing),
 * never the plugin as a whole, and they ask [paidFeaturesEnabled] or [requirePaidFeature] rather
 * than [isLicensed] directly — those two resolve the "not a paid build yet" and "facade still
 * starting up" cases in one place instead of at each site.
 *
 * [PRODUCT_CODE] must match plugin.xml's `<product-descriptor code="">` exactly once that tag is
 * added — see the README's "Marketplace publishing prep" section for why it isn't added yet.
 *
 * @see <a href="https://plugins.jetbrains.com/docs/marketplace/add-marketplace-license-verification-calls-to-the-plugin-code.html">
 *   Add license verification calls to the plugin code</a>
 */
object PrismTabsLicense {

    const val PRODUCT_CODE = "PPRISMTABS"

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

    /**
     * The freemium gate: `true` when the paid features may run — on an active subscription, during
     * the 30-day trial, and in any build that isn't distributed as a paid plugin in the first place.
     *
     * That last clause is what [isPaidBuild] is for, and it is not a convenience: the paid-plugin
     * contract only exists once `plugin.xml` carries a `<product-descriptor>`, and until then
     * [LicensingFacade] has no product to issue a stamp for, so [isLicensed] answers `false` for
     * everyone. Gating on [isLicensed] alone would therefore lock the paid features in every build
     * that was never for sale — `runIde`, `uiTest`, and any locally built plugin — which is exactly
     * what the README's "Marketplace publishing prep" section wants to avoid while the tag is still
     * deferred. With this clause the gates below can be wired now and go live by themselves the day
     * the tag lands.
     *
     * `false` means "fall back to the free tier", never "block the plugin": tab colouring,
     * unlimited groups, persistence and rename/move remapping are free forever.
     *
     * The decision itself is [decide] — split out because this function's own two reads
     * ([isPaidBuild], [isLicensed]) need a live platform and so, like [isLicensed] before them,
     * can't run under plain JUnit 5; [decide] has no such dependency and is what
     * `PrismTabsLicenseTest` actually exercises.
     */
    fun paidFeaturesEnabled(): Boolean = decide(isPaidBuild(), isLicensed())

    /**
     * The freemium truth table, isolated from platform I/O so it's unit-testable: not a paid build
     * yet, or paid but the facade hasn't answered `false`, both mean the paid features may run.
     */
    internal fun decide(isPaidBuild: Boolean, isLicensed: Boolean?): Boolean = !isPaidBuild || isLicensed != false

    /**
     * [paidFeaturesEnabled] plus the upsell, for a paid feature the user just invoked: returns
     * `true` if it may run, otherwise opens the registration dialog (where the trial is started and
     * a subscription key entered) and returns `false`.
     *
     * [feature] is the subject of the dialog's message sentence, e.g. "Collapsing a tab group".
     */
    fun requirePaidFeature(feature: String): Boolean {
        if (paidFeaturesEnabled()) return true
        requestLicense("$feature needs an active PrismTabs subscription. Start the free trial or activate a subscription to continue.")
        return false
    }

    /**
     * Whether this build is distributed as a paid plugin at all, read from our own descriptor's
     * `<product-descriptor>` — the same tag the Marketplace keys licensing off, so this cannot drift
     * from [PRODUCT_CODE]'s registration the way a separate build flag would.
     */
    private fun isPaidBuild(): Boolean =
        PluginManager.getPluginByClass(PrismTabsLicense::class.java)?.productCode != null

    /**
     * Opens the IDE's own registration dialog, pre-selected to [PRODUCT_CODE].
     *
     * Deliberately fire-and-forget, with no "and then re-check the licence" callback: nothing
     * broadcasts a licence change, and the obvious hook — run the callback after
     * [showRegisterDialog] returns — would depend on the register action showing its dialog
     * synchronously. That can't be confirmed here: `RegisterPlugins` resolves to an obfuscated class
     * (`com.c.z.c.c`) in the bundled marketplace plugin whose call chain isn't readable, and this
     * codebase doesn't write code against unverified platform behaviour. A callback that fired
     * before the user had typed anything would re-read the gate too early and leave the UI locked
     * behind a subscription just paid for — worse than not offering one.
     *
     * Callers therefore pick their licence state up at their own next natural refresh; for the
     * tool window that's the next time it is shown, or the next group change.
     */
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
