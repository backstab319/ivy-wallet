package com.ivy.automation.drivesync

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class DriveFile(
    val id: String,
    val md5: String?,
)

class DriveApiException(val status: Int, message: String) : Exception(message)

/**
 * The few Google Drive REST calls needed to keep one JSON file in the user's Drive.
 */
@Singleton
class DriveApi @Inject constructor(
    private val json: Json,
) {
    // Own client on purpose: the shared one logs request bodies and headers.
    private val client by lazy { HttpClient() }

    suspend fun find(token: String, name: String): DriveFile? {
        val response = client.get("$API/files") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("q", "name = '$name' and trashed = false")
            parameter("spaces", "drive")
            parameter("orderBy", "modifiedTime desc")
            parameter("pageSize", 1)
            parameter("fields", "files($FILE_FIELDS)")
        }
        val files = json.parseToJsonElement(response.requireOk()).jsonObject["files"]?.jsonArray
        return files?.firstOrNull()?.toString()?.toDriveFile()
    }

    suspend fun download(token: String, fileId: String): String {
        return client.get("$API/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("alt", "media")
        }.requireOk()
    }

    suspend fun create(token: String, name: String, content: String): DriveFile {
        val boundary = "ivy-wallet-${System.nanoTime()}"
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append("""{"name":"$name","mimeType":"application/json"}""")
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(content)
            append("\r\n--$boundary--")
        }
        return client.post("$UPLOAD_API/files") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("uploadType", "multipart")
            parameter("fields", FILE_FIELDS)
            setBody(
                ByteArrayContent(
                    bytes = body.toByteArray(Charsets.UTF_8),
                    contentType = ContentType.parse("multipart/related; boundary=$boundary"),
                )
            )
        }.requireOk().toDriveFile()
    }

    suspend fun update(token: String, fileId: String, content: String): DriveFile {
        return client.patch("$UPLOAD_API/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("uploadType", "media")
            parameter("fields", FILE_FIELDS)
            setBody(
                ByteArrayContent(
                    bytes = content.toByteArray(Charsets.UTF_8),
                    contentType = ContentType.Application.Json,
                )
            )
        }.requireOk().toDriveFile()
    }

    private suspend fun HttpResponse.requireOk(): String {
        val body = bodyAsText()
        if (!status.isSuccess()) {
            throw DriveApiException(status.value, "Google Drive error ${status.value}: ${body.take(300)}")
        }
        return body
    }

    private fun String.toDriveFile(): DriveFile {
        val obj = json.parseToJsonElement(this).jsonObject
        return DriveFile(
            id = obj.getValue("id").jsonPrimitive.content,
            md5 = obj["md5Checksum"]?.jsonPrimitive?.content,
        )
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        private const val FILE_FIELDS = "id,md5Checksum"
    }
}
