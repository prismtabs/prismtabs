package io.prismtabs.license

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.LicensingFacade
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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
 * [PRODUCT_CODE] must match plugin.xml's `<product-descriptor code="">` exactly; `ProductDescriptorTest`
 * asserts that it does, and [isPaidBuild] reads the same tag.
 *
 * @see <a href="https://plugins.jetbrains.com/docs/marketplace/add-marketplace-license-verification-calls-to-the-plugin-code.html">
 *   Add license verification calls to the plugin code</a>
 */
object PrismTabsLicense {

    const val PRODUCT_CODE = "PPRISMTABS"

    private const val KEY_PREFIX = "key:"
    private const val STAMP_PREFIX = "stamp:"

    /** This plugin's own descriptor, as its class loader serves it — see [isPaidBuild]. */
    private const val PLUGIN_DESCRIPTOR = "/META-INF/plugin.xml"
    private const val PRODUCT_DESCRIPTOR_TAG = "product-descriptor"

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
     * the free trial, and in any build that isn't distributed as a paid plugin in the first place.
     *
     * That last clause is what [isPaidBuild] is for, and it is not a convenience: the paid-plugin
     * contract only exists once `plugin.xml` carries a `<product-descriptor>`, and until then
     * [LicensingFacade] has no product to issue a stamp for, so [isLicensed] answers `false` for
     * everyone. Gating on [isLicensed] alone would therefore lock the paid features in every build
     * that was never for sale — a build whose `plugin.xml` carries no `<product-descriptor>` at all.
     * With this clause the gates read the descriptor rather than a build flag, so they go live with
     * the tag and stay off without it.
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
     *
     * Read out of the bundled `plugin.xml` rather than out of the platform's parsed descriptor: the
     * one call that hands a plugin its own descriptor, `PluginManager.getPluginByClass`, is
     * `@ApiStatus.Internal` from 2026.2 on, and Marketplace refused the 1.4.14 upload over it —
     * see README §"Internal API: what the Marketplace asked for". The resource is this plugin's
     * own, served by its own class loader, and it is the *patched* copy that ships in the jar, so
     * it carries whatever `patchPluginXml` wrote. Matching on [PRODUCT_CODE] rather than merely on
     * the tag's presence keeps the answer ours even if some other descriptor were ever served
     * first, and is the same pairing `ProductDescriptorTest` asserts from the other side.
     *
     * Computed once: [paidFeaturesEnabled] is asked on every editor open (see
     * `TabGroupFileListener.autoJoinActiveGroup`), and a build does not stop being a paid one
     * mid-session.
     */
    private fun isPaidBuild(): Boolean = paidBuild

    private val paidBuild: Boolean by lazy { declaresOwnProductDescriptor() }

    private fun declaresOwnProductDescriptor(): Boolean = runCatching {
        val descriptor = PrismTabsLicense::class.java.getResourceAsStream(PLUGIN_DESCRIPTOR)
            ?: return@runCatching false
        val document = descriptor.use {
            DocumentBuilderFactory.newInstance()
                .apply { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                .newDocumentBuilder()
                .parse(it)
        }
        val tags = document.getElementsByTagName(PRODUCT_DESCRIPTOR_TAG)
        (0 until tags.length).any { (tags.item(it) as Element).getAttribute("code") == PRODUCT_CODE }
    }.getOrElse {
        // A descriptor this plugin cannot read is not evidence that it is for sale, and the free
        // tier is the safe answer either way: the gates fall back to "allowed", never to "locked".
        thisLogger().warn("Could not read $PLUGIN_DESCRIPTOR; treating this as a build that is not sold", it)
        false
    }

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
        // ActionUtil.invokeAction, not a direct actionPerformed call. AnAction.actionPerformed is
        // @ApiStatus.OverrideOnly, and OVERRIDE_ONLY_API_USAGES is in build.gradle.kts's
        // failureLevel deliberately (unlike INTERNAL_API_USAGES, dropped there for TabRepaint's one
        // accepted risk) — so invoking it failed `verifyPlugin` outright on every IDE in the
        // matrix. It was also the wrong call on its own merits: invokeAction runs the action's own
        // `update` first via lastUpdateAndCheckDumb and performs it only if it enabled itself,
        // where a bare actionPerformed fires a disabled action regardless.
        //
        // The remaining wrinkle is a *warning*, not a failure, and is unavoidable at our floor:
        // this overload is undeprecated on 242 but deprecated from 243 on, in favour of the
        // ActionUtil.performAction(..., ActionUiKind) that only exists from 243 (measured: 0
        // deprecation findings on IU-242, 1 on each of 243/251/252/253/261/262). Compiling against
        // 242 means taking the older call and its DEPRECATED_API_USAGES note — the same accepted
        // trade-off CaptionedListPopup makes for SeparatorWithText. Switch to performAction on the
        // day the floor moves to 243.
        //
        // Both checked with `javap` per CLAUDE.md's recipe. Note invokeAction builds the event
        // itself via the non-deprecated createFromInputEvent, which is what also retires the
        // deprecated createFromDataContext call this used to make.
        //
        // Trailing nulls are the InputEvent (there is no originating one — this upsell is raised by
        // a gate, not by a keystroke) and the `onDone` callback, left unused on purpose: see
        // [requestLicense]'s doc for why nothing here re-reads the licence when the dialog closes.
        ActionUtil.invokeAction(registerAction, dataContext, ActionPlaces.UNKNOWN, null, null)
    }
}
