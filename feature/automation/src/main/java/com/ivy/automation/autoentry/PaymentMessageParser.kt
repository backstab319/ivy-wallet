package com.ivy.automation.autoentry

/**
 * A payment that was detected in a bank SMS or in a UPI app notification.
 */
data class ParsedPayment(
    val amount: Double,
    /** Who the money went to, when the message says so. */
    val merchant: String?,
    /** UPI reference / UTR / transaction number, used to recognize duplicates. */
    val reference: String?,
    /** Last digits of the bank account or card that was charged. */
    val accountHint: String?,
)

/**
 * Recognizes "money left your account" messages sent by Indian banks and UPI apps.
 * Returns null for everything else: OTPs, credits, payment requests, failed payments,
 * reminders and promotions.
 */
object PaymentMessageParser {

    private val ignore = Regex(
        "\\b(otp|one[ -]time password|verification code|" +
            "(will|shall|to|would) be (debited|deducted|charged)|" +
            "due (on|by|date|of|amount)|min(imum)? (amt )?due|is due|overdue|bill (is )?generated|reminder|" +
            "request(ed|s)?|collect request|" +
            "fail(ed|ure)?|declined|unsuccessful|not (completed|successful|processed)|could ?n[o']t|" +
            "revers(ed|al)|mandate|e-?mandate|autopay (is )?(set|created|registered)|" +
            "offer|win |eligible|pre-?approved|apply now|loan)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Words that only ever describe money leaving the account. */
    private val strongDebit = Regex(
        "\\b(debited|deducted|spent|withdrawn|withdrawal)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Words that describe a payment, but are also used for money coming in. */
    private val weakDebit = Regex(
        "\\b(paid|sent|purchase|purchased|transferred|payment of|txn of|transaction of)\\b",
        RegexOption.IGNORE_CASE
    )

    private val credit = Regex(
        "\\b(credited (to|in|into) your|credited with|is credited|has been credited|received|" +
            "paid you|sent you|refund(ed)?|deposited|cashback)\\b",
        RegexOption.IGNORE_CASE
    )

    private val amount = Regex(
        "(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE
    )

    /** SBI style: "A/C X1234 debited by 250.0 on date ..." */
    private val amountAfterVerb = Regex(
        "\\b(?:debited|deducted|spent|paid|sent|withdrawn)\\s+(?:by|for|of|with)\\s+" +
            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9/-])",
        RegexOption.IGNORE_CASE
    )

    private val notTheAmount = Regex(
        "(bal|balance|limit|lmt|outstanding)\\W*(is|of)?\\W*$",
        RegexOption.IGNORE_CASE
    )

    private val merchant = Regex(
        "\\b(?:to|at|towards)\\s+(?:vpa\\s+|upi id\\s+)?" +
            "([A-Za-z0-9][A-Za-z0-9@._&'*/ -]{1,39}?)" +
            "(?=\\s+(?:on|ref\\w*|upi|via|using|from|avl|avbl|bal|not|if|with|for|thru|through|dt|dated)\\b" +
            "|\\s*[.,;:(]|\\s*$)",
        RegexOption.IGNORE_CASE
    )

    private val notAMerchant = Regex(
        "^(your|ur|a/c|ac|acct|account|bank|card|the|you|be|us|report|block|know|avoid|call|sms)\\b",
        RegexOption.IGNORE_CASE
    )

    private val reference = Regex(
        "(?:ref(?:erence)?(?:\\s*(?:no|num|number|id))?|utr|rrn|txn(?:\\s*(?:id|no))?|upi)" +
            "[\\s.:#/-]*([0-9]{6,})",
        RegexOption.IGNORE_CASE
    )

    private val account = Regex(
        "\\b(?:a/c|ac|acct|account|card)(?:\\s*(?:no\\.?|number|ending(?: with| in)?))?" +
            "[\\s.:-]*[x*.]*\\s*([0-9]{3,6})\\b",
        RegexOption.IGNORE_CASE
    )

    fun parse(message: String): ParsedPayment? {
        val text = message.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || ignore.containsMatchIn(text)) return null

        val isDebit = strongDebit.containsMatchIn(text) ||
            (weakDebit.containsMatchIn(text) && !credit.containsMatchIn(text))
        if (!isDebit) return null

        val amountValue = findAmount(text) ?: return null

        return ParsedPayment(
            amount = amountValue,
            merchant = findMerchant(text),
            reference = reference.find(text)?.groupValues?.get(1),
            accountHint = account.find(text)?.groupValues?.get(1),
        )
    }

    private fun findAmount(text: String): Double? {
        return amount.findAll(text)
            .filterNot { match ->
                // "Avl Bal Rs 12,000" is not what was paid
                notTheAmount.containsMatchIn(text.substring(0, match.range.first))
            }
            .mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }
            .firstOrNull { it > 0.0 }
            ?: amountAfterVerb.find(text)?.groupValues?.get(1)
                ?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun findMerchant(text: String): String? {
        return merchant.findAll(text)
            .map { it.groupValues[1].trim().trimEnd('-', '/', '.') }
            .firstOrNull { it.length >= 2 && !notAMerchant.containsMatchIn(it) && it.any(Char::isLetter) }
    }
}
