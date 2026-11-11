package com.ivy.automation

import com.ivy.automation.autoentry.PaymentMessageParser
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class PaymentMessageParserTest {

    @Test
    fun `HDFC UPI debit`() {
        val res = PaymentMessageParser.parse(
            "Amt Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 11-11 Ref 431234567890 " +
                "Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"
        ).shouldNotBeNull()
        res.amount shouldBe 250.0
        res.merchant shouldBe "SWIGGY"
        res.reference shouldBe "431234567890"
        res.accountHint shouldBe "1234"
    }

    @Test
    fun `SBI UPI debit`() {
        val res = PaymentMessageParser.parse(
            "Dear UPI user A/C X5678 debited by 1,499.50 on date 11Nov26 trf to RAMESH KUMAR " +
                "Refno 631234567890. If not u? call 1800111109. -SBI"
        ).shouldNotBeNull()
        res.amount shouldBe 1499.5
        res.merchant shouldBe "RAMESH KUMAR"
        res.reference shouldBe "631234567890"
        res.accountHint shouldBe "5678"
    }

    @Test
    fun `ICICI debit with balance`() {
        val res = PaymentMessageParser.parse(
            "ICICI Bank Acct XX123 debited for Rs 1,200.00 on 11-Nov-26; AMAZON PAY credited. " +
                "UPI:631234567891. Call 18002662 for dispute. Avl Bal Rs 45,000.10"
        ).shouldNotBeNull()
        res.amount shouldBe 1200.0
        res.reference shouldBe "631234567891"
        res.accountHint shouldBe "123"
    }

    @Test
    fun `balance before amount is skipped`() {
        val res = PaymentMessageParser.parse(
            "Avl Bal INR 5,000.00. INR 199.00 spent on card ending 4321 at NETFLIX on 11/11."
        ).shouldNotBeNull()
        res.amount shouldBe 199.0
        res.merchant shouldBe "NETFLIX"
        res.accountHint shouldBe "4321"
    }

    @Test
    fun `debit to a VPA`() {
        val res = PaymentMessageParser.parse(
            "Rs.80.00 debited from A/c XX9988 and credited to VPA chaiwala@okaxis on 11-11-26. " +
                "UPI Ref no 123456789012"
        ).shouldNotBeNull()
        res.amount shouldBe 80.0
        res.merchant shouldBe "chaiwala@okaxis"
        res.reference shouldBe "123456789012"
    }

    @Test
    fun `UPI app notification`() {
        val res = PaymentMessageParser.parse("You paid ₹350 to Sharma Stores").shouldNotBeNull()
        res.amount shouldBe 350.0
        res.merchant shouldBe "Sharma Stores"
    }

    @Test
    fun `credits are ignored`() {
        PaymentMessageParser.parse(
            "Rs.5000.00 credited to your A/c XX1234 on 11-11 by UPI ref 123456789012"
        ) shouldBe null
        PaymentMessageParser.parse("Anita paid you ₹500") shouldBe null
        PaymentMessageParser.parse("You received ₹120.00 from Rohit") shouldBe null
    }

    @Test
    fun `OTPs requests failures and reminders are ignored`() {
        PaymentMessageParser.parse(
            "123456 is the OTP for txn of INR 999.00 at FLIPKART on card ending 4321"
        ) shouldBe null
        PaymentMessageParser.parse("Rohit has requested ₹200 from you. Pay now") shouldBe null
        PaymentMessageParser.parse(
            "Your payment of Rs 450 to ZOMATO failed. Amount debited, if any, will be refunded"
        ) shouldBe null
        PaymentMessageParser.parse(
            "Rs 799 will be debited from your A/c XX1234 on 15-Nov for Netflix autopay"
        ) shouldBe null
        PaymentMessageParser.parse("Your electricity bill of Rs 2,300 is due on 20 Nov. Pay now") shouldBe null
    }

    @Test
    fun `unrelated text is ignored`() {
        PaymentMessageParser.parse("Hey, are we still meeting at 5?") shouldBe null
        PaymentMessageParser.parse("") shouldBe null
    }
}
