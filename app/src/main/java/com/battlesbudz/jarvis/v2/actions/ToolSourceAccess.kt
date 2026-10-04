package com.battlesbudz.jarvis.v2.actions

/** Remembered first-source access per tool family (D09, T08). Persisted in the journal. */
enum class SourceAccessState { GRANTED, DENIED, REVOKED }

data class ToolSourceAccessRecord(
    val family: String,
    val scopes: Set<String>,
    val state: SourceAccessState,
    val updatedAtMs: Long
)

/**
 * M1e tool-family permission policy (T08, D09/D10).
 *
 * Families are the "app/data source" unit from D09: the first time a family
 * dispatches, its access is remembered. A new tool can never broaden an
 * existing grant's scope — the grant is capped at the family's scope set —
 * and a denial or revocation blocks dispatch on every adapter. Within a
 * family, new tools are automatically exposed under the already-approved
 * access (D10); broader access needs an explicit re-grant.
 *
 * Scopes are family-grained in M1e: these tools are gated by install-time
 * permissions and user-enabled capabilities (e.g. the accessibility service),
 * not by per-tool runtime permission prompts. Per-tool scope narrowing is
 * future work for runtime-permission-gated tools (M3+).
 */
object ToolSourcePolicy {
    fun familyOf(tool: String): String = when (tool) {
        "read_battery", "set_volume", "open_app" -> "phone"
        "media_control" -> "media"
        "open_website" -> "web"
        "open_settings" -> "settings"
        "navigate" -> "map"
        "screen_observe", "screen_tap", "screen_scroll", "screen_type" -> "screen"
        else -> "unknown"
    }

    /** The full scope set a family grant may ever cover; a grant can never exceed this. */
    fun familyScopes(family: String): Set<String> = when (family) {
        "phone" -> setOf("battery.read", "audio.modify", "app.launch")
        "media" -> setOf("media.keys")
        "web" -> setOf("web.open")
        "settings" -> setOf("settings.open")
        "map" -> setOf("maps.directions")
        "screen" -> setOf("screen.read", "screen.control")
        else -> emptySet()
    }

    fun requiredScopes(tool: String): Set<String> = when (tool) {
        "read_battery" -> setOf("battery.read")
        "set_volume" -> setOf("audio.modify")
        "open_app" -> setOf("app.launch")
        "media_control" -> setOf("media.keys")
        "open_website" -> setOf("web.open")
        "open_settings" -> setOf("settings.open")
        "navigate" -> setOf("maps.directions")
        "screen_observe" -> setOf("screen.read")
        "screen_tap", "screen_scroll", "screen_type" -> setOf("screen.control")
        else -> emptySet()
    }

    fun describeFamily(family: String): String = when (family) {
        "phone" -> "phone controls"
        "media" -> "media control"
        "web" -> "website opening"
        "settings" -> "system settings"
        "map" -> "map directions"
        "screen" -> "screen control"
        else -> "this phone feature"
    }
}

/**
 * M1e source-access admission over the persisted journal (T08).
 *
 * Constructed with the task ledger so the remembered first-grant, denials
 * and revocations share the same durable store as attempts and approvals.
 * The live Android capability check is separate ([ToolCapabilityProbe]) and
 * runs at admission and immediately before dispatch.
 */
class ToolSourceAccess(private val ledger: ToolTaskLedger) {

    /** Null when dispatch may proceed; otherwise the honest denial receipt. No adapter is touched. */
    fun denial(request: ActionRequest): ExecutionResult? {
        val family = ToolSourcePolicy.familyOf(request.name)
        val record = ledger.journal().sourceAccess.find { it.family == family } ?: return null
        if (record.state != SourceAccessState.GRANTED) {
            return ExecutionResult(ExecutionResult.Outcome.DENIED_PERMISSION,
                "Access to ${ToolSourcePolicy.describeFamily(family)} was ${record.state.name.lowercase()}. " +
                    "I can't use it until you restore access.")
        }
        val missing = ToolSourcePolicy.requiredScopes(request.name) - record.scopes
        if (missing.isNotEmpty()) {
            return ExecutionResult(ExecutionResult.Outcome.DENIED_PERMISSION,
                "This needs broader access than was granted for ${ToolSourcePolicy.describeFamily(family)}. " +
                    "Please approve the broader access first.")
        }
        return null
    }

    /**
     * Remember first-source access after a successful dispatch. A dispatch
     * never overwrites a denial/revocation and never broadens the grant
     * beyond the family's scope set (T08).
     */
    fun recordGrant(request: ActionRequest) = ledger.recordSourceGrant(request)

    fun revoke(family: String): Boolean = ledger.revokeSourceAccess(family)

    fun recordDenial(family: String) = ledger.recordSourceDenial(family)
}
