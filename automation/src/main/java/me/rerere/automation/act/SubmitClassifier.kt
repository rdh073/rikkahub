package me.rerere.automation.act

import me.rerere.automation.observe.UiTarget

/**
 * Submit-class classifier for the general tap (#198 slice 11, design Q2 / I-act-5). Decides whether a
 * tap on a resolved [UiTarget] is "submit-class" — a commit/send/pay-style action whose effect is
 * irreversible and side-effect-committing — and therefore carries the dangerous [me.rerere.automation
 * .cap.Sink.SUBMIT] (which the core then gates behind an out-of-band user confirmation).
 *
 * This classifier is INTENTIONALLY OVER-BROAD (conservative by construction), and must NOT be
 * weakened to reduce false positives. The asymmetry is the whole point:
 *  - a FALSE POSITIVE costs exactly one harmless extra confirmation prompt on a benign tap;
 *  - a FALSE NEGATIVE lets the model commit an UNCONFIRMED irreversible pay/transfer — catastrophic.
 * So when in doubt, classify as submit. It therefore matches a broad commit-keyword set as a
 * case-insensitive SUBSTRING (not whole-word) of EITHER the target's visible [UiTarget.text] OR its
 * [UiTarget.semanticKey] — a button labelled "Pay now", a content-description `confirm_purchase`, a
 * localized "Place order" all match.
 *
 * Plain affirmatives ("OK", "Cancel", "Back", "Close", "Edit", "Search") are NOT in the keyword set,
 * so an ordinary dialog tap (the slice-10 "OK"-button happy path) stays non-submit and is never gated.
 * Only a TAP is ever classified; scroll / global-nav / set_text are never submit-class (the core only
 * consults this for [me.rerere.automation.backend.NodeActionKind.CLICK]).
 *
 * Total & pure (module purity I10 — no android.*): null text/semanticKey is treated as no-match, never
 * throws; the same target always yields the same result.
 */
object SubmitClassifier {

    /**
     * The commit-keyword set, lowercased. Deliberately broad: every term that plausibly labels a
     * commit/send/pay/checkout/subscribe action. Multi-word entries (e.g. "place order") match as a
     * substring just like single words, so a localized or differently-spaced label still trips. Adding
     * a term here only ever ADDS confirmation prompts — never removes a safety gate — so err toward
     * inclusion. Do NOT prune this to silence false positives (design I-act-5: over-broad on purpose).
     */
    val COMMIT_KEYWORDS: List<String> = listOf(
        "submit",
        "send money",
        "send",
        "pay",
        "buy",
        "purchase",
        "place order",
        "order",
        "checkout",
        "check out",
        "confirm",
        "transfer",
        "subscribe",
        "donate",
        "continue to pay",
        "complete purchase",
        "complete order",
        "proceed to pay",
    )

    /**
     * True iff [target] is a submit-class tap target — ANY [COMMIT_KEYWORDS] entry appears as a
     * case-insensitive substring of the target's [UiTarget.text] OR [UiTarget.semanticKey]. Total:
     * a null text/semanticKey contributes no match (never throws).
     */
    fun isSubmitClass(target: UiTarget): Boolean = isSubmitClass(target.text, target.semanticKey)

    /**
     * Field-level overload (the load-bearing predicate): true iff any commit keyword is a
     * case-insensitive substring of [text] OR [semanticKey]. Either field may be null (treated as the
     * empty string — no match). Lowercase each field once and scan it independently, so a keyword can
     * only match WITHIN a single field (never spanning the text↔key boundary).
     */
    fun isSubmitClass(text: String?, semanticKey: String?): Boolean {
        val lowerText = text?.lowercase()
        val lowerKey = semanticKey?.lowercase()
        return COMMIT_KEYWORDS.any { keyword ->
            lowerText?.contains(keyword) == true || lowerKey?.contains(keyword) == true
        }
    }
}
