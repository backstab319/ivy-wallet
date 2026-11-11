package com.ivy.automation

import com.ivy.automation.drivesync.SyncMerger
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class SyncMergerTest {
    private val json = Json

    private fun trn(id: String, amount: Int, account: String = "acc-1") =
        """{"id":"$id","amount":$amount,"accountId":"$account"}"""

    private fun acc(id: String, name: String) = """{"id":"$id","name":"$name"}"""

    private fun data(
        transactions: List<String> = emptyList(),
        accounts: List<String> = emptyList(),
        settings: String = """[{"id":"s","currency":"INR"}]""",
    ) = """{"accounts":[${accounts.joinToString(",")}],""" +
        """"transactions":[${transactions.joinToString(",")}],"settings":$settings}"""

    private fun merge(local: String, base: String?, remote: String?) =
        json.parseToJsonElement(SyncMerger.merge(json, local, base, remote)).jsonObject

    private fun transactionsOf(local: String, base: String?, remote: String?): Map<String, Int> =
        merge(local, base, remote)["transactions"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.content to
                it.jsonObject["amount"]!!.jsonPrimitive.content.toInt()
        }

    @Test
    fun `nothing on Drive keeps local data`() {
        val local = data(listOf(trn("a", 1)))
        SyncMerger.merge(json, local, base = local, remote = null) shouldBe local
    }

    @Test
    fun `new items from both sides are kept`() {
        val base = data(listOf(trn("a", 1)))
        transactionsOf(
            local = data(listOf(trn("a", 1), trn("b", 2))),
            base = base,
            remote = data(listOf(trn("a", 1), trn("c", 3))),
        ) shouldBe mapOf("a" to 1, "b" to 2, "c" to 3)
    }

    @Test
    fun `deleted on another device is deleted here`() {
        val base = data(listOf(trn("a", 1), trn("b", 2)))
        transactionsOf(local = base, base = base, remote = data(listOf(trn("a", 1)))) shouldBe
            mapOf("a" to 1)
    }

    @Test
    fun `deleted here stays deleted`() {
        val base = data(listOf(trn("a", 1), trn("b", 2)))
        transactionsOf(local = data(listOf(trn("a", 1))), base = base, remote = base) shouldBe
            mapOf("a" to 1)
    }

    @Test
    fun `local edit survives when Drive did not change`() {
        val base = data(listOf(trn("a", 1)))
        transactionsOf(local = data(listOf(trn("a", 5))), base = base, remote = base) shouldBe
            mapOf("a" to 5)
    }

    @Test
    fun `Drive wins when both sides edited`() {
        val base = data(listOf(trn("a", 1)))
        transactionsOf(
            local = data(listOf(trn("a", 5))),
            base = base,
            remote = data(listOf(trn("a", 9))),
        ) shouldBe mapOf("a" to 9)
    }

    @Test
    fun `an edit beats a delete`() {
        val base = data(listOf(trn("a", 1), trn("b", 2)))
        // "a" edited here and deleted there, "b" deleted here and edited there
        transactionsOf(
            local = data(listOf(trn("a", 7))),
            base = base,
            remote = data(listOf(trn("b", 8))),
        ) shouldBe mapOf("a" to 7, "b" to 8)
    }

    @Test
    fun `settings follow Drive only when Drive changed`() {
        val base = data(settings = """[{"id":"s","currency":"INR"}]""")
        val localEdited = data(settings = """[{"id":"s","currency":"USD"}]""")
        merge(localEdited, base, base)["settings"].toString() shouldBe
            """[{"id":"s","currency":"USD"}]"""
        merge(base, base, localEdited)["settings"].toString() shouldBe
            """[{"id":"s","currency":"USD"}]"""
    }

    @Test
    fun `first sync joins accounts with the same name`() {
        val local = data(
            transactions = listOf(trn("mine", 1, account = "local-cash")),
            accounts = listOf(acc("local-cash", "Cash"), acc("local-bank", "Bank")),
        )
        val remote = data(
            transactions = listOf(trn("theirs", 2, account = "drive-cash")),
            accounts = listOf(acc("drive-cash", "Cash")),
        )

        val merged = merge(local, base = null, remote = remote)

        merged["accounts"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet() shouldBe
            setOf("drive-cash", "local-bank")
        merged["transactions"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.content to
                it.jsonObject["accountId"]!!.jsonPrimitive.content
        } shouldBe mapOf("mine" to "drive-cash", "theirs" to "drive-cash")
    }
}
