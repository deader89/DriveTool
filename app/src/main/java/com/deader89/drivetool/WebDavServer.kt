package com.deader89.drivetool

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import java.text.SimpleDateFormat
import java.util.*

object WebDavServer {
    private const val TAG = "WebDavServer"
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun isActive(): Boolean = server != null

    fun start(context: Context, treeUri: Uri): Result<Unit> {
        if (server != null) return Result.success(Unit)

        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return Result.failure(Exception("Invalid URI"))

            val engine = embeddedServer(CIO, environment = applicationEnvironment {
                log = LogKtorWrapper()
            }, configure = {
                connector {
                    port = 8081
                    host = "0.0.0.0"
                }
            }) {
                install(Authentication) {
                    basic("auth-basic") {
                        realm = "Access to WebDAV"
                        validate { credentials ->
                            if (credentials.name == "admin" && credentials.password == "admin") {
                                UserIdPrincipal(credentials.name)
                            } else {
                                null
                            }
                        }
                    }
                }

                routing {
                    authenticate("auth-basic") {
                        route("/{name...}") {
                            handle {
                                val method = call.request.local.method.value
                                Log.d(TAG, "Request: $method ${call.request.uri}")

                                val pathSegments = call.parameters.getAll("name") ?: emptyList()
                                val relativePath = pathSegments.joinToString("/")
                                val encodedPath = if (pathSegments.isEmpty()) "/" else "/" + pathSegments.joinToString("/") { 
                                    java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") 
                                }

                                Log.d(TAG, "Resolved path: $relativePath (segments: ${pathSegments.size})")

                                if (isInvalidPath(relativePath)) {
                                    Log.w(TAG, "Forbidden path: $relativePath")
                                    call.respond(HttpStatusCode.Forbidden, "Invalid path")
                                    return@handle
                                }

                                val targetDoc = try {
                                    findDocument(rootDoc, pathSegments)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error finding document", e)
                                    null
                                }

                                when (method) {
                                    "OPTIONS" -> {
                                        call.response.headers.append("Allow", "OPTIONS, GET, HEAD, PROPFIND, PUT, MKCOL, DELETE, MOVE, COPY, PROPPATCH")
                                        call.response.headers.append("DAV", "1, 2")
                                        call.response.headers.append("MS-Author-Via", "DAV")
                                        call.respond(HttpStatusCode.OK, "")
                                    }

                                    "HEAD" -> {
                                        if (targetDoc != null && targetDoc.exists()) {
                                            call.response.headers.append("Last-Modified", formatHttpDate(targetDoc.lastModified()))
                                            call.respond(HttpStatusCode.OK)
                                        } else call.respond(HttpStatusCode.NotFound)
                                    }

                                    "PROPFIND" -> {
                                        if (targetDoc == null || !targetDoc.exists()) {
                                            call.respond(HttpStatusCode.NotFound)
                                            return@handle
                                        }
                                        val depth = call.request.headers["Depth"] ?: "1"
                                        val xmlResponse = buildWebDavXml(context, encodedPath, targetDoc, depth)
                                        
                                        // Windows benötigt diese Header oft auch in der PROPFIND Antwort
                                        call.response.headers.append("DAV", "1, 2")
                                        call.response.headers.append("MS-Author-Via", "DAV")
                                        
                                        call.respondText(xmlResponse, ContentType.parse("application/xml; charset=utf-8"), HttpStatusCode.MultiStatus)
                                    }

                                    "PROPPATCH" -> {
                                        if (targetDoc != null && targetDoc.exists()) {
                                            val requestBody = try { call.receiveText() } catch (e: Exception) { "" }
                                            val xml = buildPropPatchResponse(encodedPath, requestBody)
                                            call.respondText(xml, ContentType.parse("application/xml; charset=utf-8"), HttpStatusCode.MultiStatus)
                                        } else {
                                            call.respond(HttpStatusCode.NotFound)
                                        }
                                    }

                                    "LOCK" -> {
                                        // Windows benötigt LOCK oft für die Bearbeitung direkt auf dem Server
                                        // Wir simulieren einen erfolgreichen Lock (Shared/Exclusive)
                                        val token = "opaquelocktoken:" + UUID.randomUUID().toString()
                                        call.response.headers.append("Lock-Token", "<$token>")
                                        val xml = """<?xml version="1.0" encoding="utf-8" ?>
<d:prop xmlns:d="DAV:">
  <d:lockdiscovery>
    <d:activelock>
      <d:locktype><d:write/></d:locktype>
      <d:lockscope><d:exclusive/></d:lockscope>
      <d:depth>0</d:depth>
      <d:owner><d:href>admin</d:href></d:owner>
      <d:timeout>Second-3600</d:timeout>
      <d:locktoken><d:href>$token</d:href></d:locktoken>
      <d:lockroot><d:href>$encodedPath</d:href></d:lockroot>
    </d:activelock>
  </d:lockdiscovery>
</d:prop>""".trimIndent()
                                        call.respondText(xml, ContentType.parse("application/xml; charset=utf-8"), HttpStatusCode.OK)
                                    }

                                    "UNLOCK" -> {
                                        call.respond(HttpStatusCode.NoContent)
                                    }

                                    "GET" -> {
                                        if (targetDoc == null || !targetDoc.exists() || targetDoc.isDirectory) {
                                            call.respond(HttpStatusCode.NotFound)
                                            return@handle
                                        }
                                        val inputStream = context.contentResolver.openInputStream(targetDoc.uri)
                                        if (inputStream != null) {
                                            call.response.headers.append("Last-Modified", formatHttpDate(targetDoc.lastModified()))
                                            call.respond(object : OutgoingContent.ReadChannelContent() {
                                                override val contentType = ContentType.parse(getMimeType(targetDoc.name))
                                                override val contentLength = targetDoc.length()
                                                override fun readFrom() = inputStream.toByteReadChannel()
                                            })
                                        } else {
                                            call.respond(HttpStatusCode.InternalServerError)
                                        }
                                    }

                                    "PUT" -> {
                                        try {
                                            if (pathSegments.isEmpty()) {
                                                call.respond(HttpStatusCode.BadRequest)
                                                return@handle
                                            }
                                            val parentSegments = pathSegments.dropLast(1)
                                            val fileName = pathSegments.last()
                                            val parentDoc = if (parentSegments.isEmpty()) rootDoc else findDocument(rootDoc, parentSegments)

                                            if (parentDoc == null || !parentDoc.isDirectory) {
                                                call.respond(HttpStatusCode.Conflict)
                                                return@handle
                                            }

                                            val existingFile = parentDoc.findFile(fileName)
                                            val docToFiles = existingFile ?: parentDoc.createFile(getMimeType(fileName), fileName)

                                            if (docToFiles == null) {
                                                call.respond(HttpStatusCode.InternalServerError)
                                                return@handle
                                            }

                                            val input = call.receiveChannel()
                                            context.contentResolver.openOutputStream(docToFiles.uri, "wt")?.use { os ->
                                                input.toInputStream().copyTo(os)
                                                os.flush()
                                            }
                                            call.respond(HttpStatusCode.Created)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error during PUT operation", e)
                                            call.respond(HttpStatusCode.InternalServerError)
                                        }
                                    }

                                    "MKCOL" -> {
                                        if (pathSegments.isEmpty()) {
                                            call.respond(HttpStatusCode.BadRequest)
                                            return@handle
                                        }
                                        val parentSegments = pathSegments.dropLast(1)
                                        val dirName = pathSegments.last()
                                        val parentDoc = if (parentSegments.isEmpty()) rootDoc else findDocument(rootDoc, parentSegments)

                                        if (parentDoc != null && parentDoc.createDirectory(dirName) != null) {
                                            call.respond(HttpStatusCode.Created)
                                        } else {
                                            call.respond(HttpStatusCode.InternalServerError)
                                        }
                                    }

                                    "DELETE" -> {
                                        if (targetDoc != null && targetDoc.uri != rootDoc.uri && targetDoc.delete()) {
                                            call.respond(HttpStatusCode.NoContent)
                                        } else {
                                            call.respond(HttpStatusCode.NotFound) // Windows verlangt oft 404 falls Pfad fehlerhaft
                                        }
                                    }

                                    "MOVE" -> {
                                        if (targetDoc == null || !targetDoc.exists()) {
                                            call.respond(HttpStatusCode.NotFound)
                                            return@handle
                                        }

                                        val destinationHeader = call.request.headers["Destination"] ?: return@handle call.respond(HttpStatusCode.BadRequest)
                                        val overwrite = call.request.headers["Overwrite"] ?: "T"

                                        val destPath = decodeDestination(destinationHeader)
                                        val destSegments = destPath.split("/").filter { it.isNotEmpty() }
                                        if (destSegments.isEmpty()) return@handle call.respond(HttpStatusCode.BadRequest)

                                        val destParentSegments = destSegments.dropLast(1)
                                        val newName = destSegments.last()

                                        val targetParentDoc = if (destParentSegments.isEmpty()) rootDoc else findDocument(rootDoc, destParentSegments)
                                        if (targetParentDoc == null || !targetParentDoc.isDirectory) {
                                            call.respond(HttpStatusCode.Conflict)
                                            return@handle
                                        }

                                        val existingFile = targetParentDoc.findFile(newName)
                                        if (existingFile != null) {
                                            if (overwrite == "F") {
                                                call.respond(HttpStatusCode.PreconditionFailed)
                                                return@handle
                                            } else {
                                                existingFile.delete()
                                            }
                                        }

                                        try {
                                            val parentSegments = pathSegments.dropLast(1)
                                            val currentParentDoc = if (parentSegments.isEmpty()) rootDoc else findDocument(rootDoc, parentSegments) ?: rootDoc

                                            val movedUri = try {
                                                DocumentsContract.moveDocument(
                                                    context.contentResolver,
                                                    targetDoc.uri,
                                                    currentParentDoc.uri,
                                                    targetParentDoc.uri
                                                )
                                            } catch (e: Exception) { null }

                                            if (movedUri != null) {
                                                if (targetDoc.name != newName) {
                                                    DocumentsContract.renameDocument(context.contentResolver, movedUri, newName)
                                                }
                                                call.respond(if (existingFile != null) HttpStatusCode.NoContent else HttpStatusCode.Created)
                                            } else {
                                                if (targetDoc.renameTo(newName)) {
                                                    call.respond(if (existingFile != null) HttpStatusCode.NoContent else HttpStatusCode.Created)
                                                } else {
                                                    call.respond(HttpStatusCode.Forbidden)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            call.respond(HttpStatusCode.Forbidden)
                                        }
                                    }

                                    "COPY" -> {
                                        if (targetDoc == null || !targetDoc.exists() || targetDoc.isDirectory) {
                                            call.respond(HttpStatusCode.NotImplemented)
                                            return@handle
                                        }

                                        val destinationHeader = call.request.headers["Destination"] ?: return@handle call.respond(HttpStatusCode.BadRequest)
                                        val overwrite = call.request.headers["Overwrite"] ?: "T"

                                        val destPath = decodeDestination(destinationHeader)
                                        val destSegments = destPath.split("/").filter { it.isNotEmpty() }
                                        if (destSegments.isEmpty()) return@handle call.respond(HttpStatusCode.BadRequest)

                                        val destParentSegments = destSegments.dropLast(1)
                                        val newName = destSegments.last()

                                        val targetParentDoc = if (destParentSegments.isEmpty()) rootDoc else findDocument(rootDoc, destParentSegments)
                                        if (targetParentDoc == null || !targetParentDoc.isDirectory) {
                                            call.respond(HttpStatusCode.Conflict)
                                            return@handle
                                        }

                                        val existingFile = targetParentDoc.findFile(newName)
                                        if (existingFile != null && overwrite == "F") {
                                            call.respond(HttpStatusCode.PreconditionFailed)
                                            return@handle
                                        }

                                        try {
                                            val newFile = existingFile ?: targetParentDoc.createFile(targetDoc.type ?: "application/octet-stream", newName)
                                            if (newFile != null) {
                                                context.contentResolver.openInputStream(targetDoc.uri)?.use { input ->
                                                    context.contentResolver.openOutputStream(newFile.uri, "wt")?.use { output ->
                                                        input.copyTo(output)
                                                        output.flush()
                                                    }
                                                }
                                                call.respond(if (existingFile != null) HttpStatusCode.NoContent else HttpStatusCode.Created)
                                            } else {
                                                call.respond(HttpStatusCode.InternalServerError)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error during COPY", e)
                                            call.respond(HttpStatusCode.InternalServerError)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            engine.start(wait = false)
            server = engine
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SAF WebDAV", e)
            Result.failure(e)
        }
    }

    private fun decodeDestination(dest: String): String {
        return try {
            val path = if (dest.startsWith("http")) java.net.URI(dest).path else dest
            java.net.URLDecoder.decode(path, "UTF-8").trim('/')
        } catch (e: Exception) {
            dest.trim('/')
        }
    }

    private fun isInvalidPath(path: String): Boolean {
        return path.contains("..")
    }

    // Fix: Nutzt jetzt saubere List-basierte Segmente, schützt vor falschem URL-Encoding
    private fun findDocument(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current: DocumentFile? = root
        for (part in segments) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") return null
            current = current?.findFile(part)
            if (current == null) break
        }
        return current
    }

    private fun buildWebDavXml(context: Context, encodedBaseUri: String, doc: DocumentFile, depth: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n")
        sb.append("<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">\n")

        val cleanBaseUri = "/" + encodedBaseUri.split("/").filter { it.isNotBlank() }.joinToString("/")

        // 1. Root Element
        appendDocXml(sb, doc, cleanBaseUri, true)

        // 2. Kinder auflisten (Cursor)
        if (depth != "0" && doc.isDirectory) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(doc.uri, DocumentsContract.getDocumentId(doc.uri))
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS
            )

            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
                cursor?.let {
                    val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val parentId = DocumentsContract.getDocumentId(doc.uri)

                    while (it.moveToNext()) {
                        val docId = it.getString(idIndex)
                        if (docId == parentId) continue

                        // Wir bauen ein temporäres DocumentFile-ähnliches Objekt oder nutzen direkt die Cursor-Daten
                        // Um appendDocXml konsistent zu halten, extrahieren wir die Daten hier:
                        val name = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                        val size = it.getLong(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
                        val lastMod = it.getLong(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                        val mimeType = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                        val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                        appendDocXmlRaw(sb, name, size, lastMod, isDir, cleanBaseUri, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying child documents via cursor", e)
            } finally {
                cursor?.close()
            }
        }

        sb.append("</d:multistatus>")
        return sb.toString()
    }

    private fun buildPropPatchResponse(uri: String, requestBody: String): String {
        val hasWinProperties = requestBody.contains("Win32")
        return """<?xml version="1.0" encoding="utf-8" ?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>$uri</d:href>
    <d:propstat>
      <d:prop>
        ${if (hasWinProperties) "<Win32CreationTime xmlns=\"urn:schemas-microsoft-com:\"/><Win32LastAccessTime xmlns=\"urn:schemas-microsoft-com:\"/><Win32LastModifiedTime xmlns=\"urn:schemas-microsoft-com:\"/><Win32FileAttributes xmlns=\"urn:schemas-microsoft-com:\"/>" else ""}
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>""".trimIndent()
    }

    private fun appendDocXml(sb: StringBuilder, doc: DocumentFile, cleanBaseUri: String, isRoot: Boolean) {
        appendDocXmlRaw(sb, doc.name ?: "", doc.length(), doc.lastModified(), doc.isDirectory, cleanBaseUri, isRoot)
    }

    private fun appendDocXmlRaw(sb: StringBuilder, name: String, size: Long, lastMod: Long, isDir: Boolean, cleanBaseUri: String, isRoot: Boolean) {
        val lastModFixed = if (lastMod <= 0) System.currentTimeMillis() else lastMod
        
        val fileUri = if (isRoot) cleanBaseUri else {
            val encodedName = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            if (cleanBaseUri.endsWith("/")) "$cleanBaseUri$encodedName" else "$cleanBaseUri/$encodedName"
        }
        val normalizedUri = if (isDir && !fileUri.endsWith("/")) "$fileUri/" else fileUri

        val rfc1123 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val iso8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }

        sb.append("  <d:response>\n")
        sb.append("    <d:href>${escapeXml(normalizedUri)}</d:href>\n")
        sb.append("    <d:propstat>\n")
        sb.append("      <d:prop>\n")
        sb.append("        <d:displayname>${escapeXml(name)}</d:displayname>\n")
        sb.append("        <d:getlastmodified>${rfc1123.format(Date(lastModFixed))}</d:getlastmodified>\n")
        sb.append("        <d:creationdate>${iso8601.format(Date(lastModFixed))}</d:creationdate>\n")
        
        if (isDir) {
            sb.append("        <d:resourcetype><d:collection/></d:resourcetype>\n")
            sb.append("        <d:getcontenttype>httpd/unix-directory</d:getcontenttype>\n")
        } else {
            sb.append("        <d:resourcetype/>\n")
            sb.append("        <d:getcontentlength>$size</d:getcontentlength>\n")
            sb.append("        <d:getcontenttype>${getMimeType(name)}</d:getcontenttype>\n")
            sb.append("        <d:getetag>\"${lastModFixed}-${size}\"</d:getetag>\n")
        }
        
        sb.append("        <d:supportedlock>\n")
        sb.append("          <d:lockentry><d:lockscope><d:exclusive/></d:lockscope><d:locktype><d:write/></d:locktype></d:lockentry>\n")
        sb.append("          <d:lockentry><d:lockscope><d:shared/></d:lockscope><d:locktype><d:write/></d:locktype></d:lockentry>\n")
        sb.append("        </d:supportedlock>\n")

        sb.append("      </d:prop>\n")
        sb.append("      <d:status>HTTP/1.1 200 OK</d:status>\n")
        sb.append("    </d:propstat>\n")
        sb.append("  </d:response>\n")
    }

    private fun formatHttpDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return sdf.format(Date(timestamp))
    }

    private fun getMimeType(fileName: String?): String {
        if (fileName == null) return "application/octet-stream"
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
            ?: fileName.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }

    fun stop(): Result<Unit> {
        server?.stop(1000, 2000)
        server = null
        return Result.success(Unit)
    }

    private class LogKtorWrapper : org.slf4j.Logger by org.slf4j.helpers.NOPLogger.NOP_LOGGER
}
