package com.ivy.automation.drivesync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Merges the data on this device with the copy on Google Drive.
 *
 * All three inputs are Ivy Wallet backup JSONs (see IvyWalletCompleteData):
 * - **local**: what this device has now
 * - **remote**: what is on Google Drive now (null when there is nothing there)
 * - **base**: what both sides had after the last successful sync of this device
 *   (null on the first sync)
 *
 * Comparing against **base** tells edits and deletions apart: an item that is in
 * **base** but no longer in **remote** was deleted on another device; one that is
 * missing from **base** is simply new. When the same item changed on both sides
 * the copy from Google Drive wins.
 */
object SyncMerger {

    /** Keys of the backup that hold one value for the whole app, not a list of items. */
    private val wholeValues = setOf("settings", "sharedPrefs")

    fun merge(
        json: Json,
        local: String,
        base: String?,
        remote: String?,
    ): String {
        if (remote == null) return local

        val remoteObj = json.parseToJsonElement(remote).jsonObject
        val baseObj = base?.let { json.parseToJsonElement(it).jsonObject }
        val localObj = json.parseToJsonElement(
            // First time this device meets this Drive copy: both may have their own
            // "Cash" account or "Food" category. Adopt the ids from Drive so they
            // become one, without changing anything for the devices already syncing.
            if (baseObj == null) adoptRemoteIds(json, local, remoteObj) else local
        ).jsonObject

        val merged = LinkedHashMap<String, JsonElement>()
        for (key in localObj.keys + remoteObj.keys) {
            val l = localObj[key]
            val r = remoteObj[key]
            val b = baseObj?.get(key)
            merged[key] = when {
                l == null -> r!!
                r == null -> l
                key !in wholeValues && l is JsonArray && r is JsonArray ->
                    mergeItems(key, l, b as? JsonArray, r, firstSync = baseObj == null)

                // one value for the whole app: ours if only we changed it
                else -> if (baseObj != null && r == b) l else r
            }
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }

    /** True when both backups hold the same data, in whatever order. */
    fun sameData(json: Json, first: String, second: String): Boolean {
        return canonical(json, first) == canonical(json, second)
    }

    private fun canonical(json: Json, data: String): Map<String, Any> {
        return json.parseToJsonElement(data).jsonObject.mapValues { (key, value) ->
            if (value is JsonArray && key !in wholeValues) {
                value.associateBy { identity(key, it) }
            } else {
                value
            }
        }
    }

    private fun mergeItems(
        key: String,
        local: JsonArray,
        base: JsonArray?,
        remote: JsonArray,
        firstSync: Boolean,
    ): JsonArray {
        val l = local.associateBy { identity(key, it) }
        val r = remote.associateBy { identity(key, it) }
        val b = base.orEmpty().associateBy { identity(key, it) }

        val result = ArrayList<JsonElement>(maxOf(l.size, r.size))
        for (id in l.keys + r.keys) {
            val inLocal = l[id]
            val inRemote = r[id]
            val inBase = b[id]
            val keep = when {
                inLocal != null && inRemote != null ->
                    // changed only here -> ours, otherwise Drive wins
                    if (!firstSync && inRemote == inBase) inLocal else inRemote

                inLocal != null ->
                    // deleted on another device, unless we edited it since
                    if (inBase != null && inLocal == inBase) null else inLocal

                else ->
                    // deleted here, unless another device edited it since
                    if (inBase != null && inRemote == inBase) null else inRemote
            }
            if (keep != null) result.add(keep)
        }
        return JsonArray(result)
    }

    private fun identity(key: String, item: JsonElement): String {
        val obj = item as? JsonObject ?: return item.toString()
        return if (key == "tagAssociations") {
            "${obj["tagId"]}|${obj["associatedId"]}"
        } else {
            obj["id"]?.toString() ?: obj.toString()
        }
    }

    private fun adoptRemoteIds(json: Json, local: String, remote: JsonObject): String {
        val localObj = json.parseToJsonElement(local).jsonObject
        var result = local
        for (key in listOf("accounts", "categories")) {
            val remoteIdByName = idsByUniqueName(remote[key])
            for ((name, localId) in idsByUniqueName(localObj[key])) {
                val remoteId = remoteIdByName[name] ?: continue
                if (remoteId != localId) result = result.replace(localId, remoteId)
            }
        }
        return result
    }

    private fun idsByUniqueName(items: JsonElement?): Map<String, String> {
        val list = (items as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return list.groupBy { it["name"]?.jsonPrimitive?.content }
            .filter { (name, sameName) -> name != null && sameName.size == 1 }
            .mapNotNull { (name, sameName) ->
                val id = sameName.single()["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                name!! to id
            }.toMap()
    }
}
