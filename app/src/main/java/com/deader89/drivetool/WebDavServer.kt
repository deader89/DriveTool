package com.deader89.drivetool

import android.content.Context
import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object WebDavServer {
    private const val TAG = "WebDavServer"
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun isActive(): Boolean = server != null

    private fun mapToInternalPath(path: String): String {
        return when {
            path.startsWith("/storage/emulated/0") -> path.replace("/storage/emulated/0", "/data/media/0")
            else -> path
        }
    }

    fun start(context: Context, path: String): Result<Unit> {
        if (server != null) return Result.success(Unit)

        return try {
            val internalPath = mapToInternalPath(path)
            Log.d(TAG, "Starting WebDAV engine. Mapping: $path -> $internalPath")

            RootUtils.execute("chmod -R 777 '$internalPath' || true")

            val internalRootDir = File(internalPath)

            val engine = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
                routing {
                    route("/{name...}") {
                        handle {
                            val method = call.request.local.method.value
                            val encodedPath = call.request.uri.substringBefore("?")

                            val relativePath = try {
                                java.net.URLDecoder.decode(encodedPath, "UTF-8").removePrefix("/")
                            } catch (e: Exception) {
                                call.parameters.getAll("name")?.joinToString(File.separator) ?: ""
                            }

                            val requestedFile = File(internalRootDir, relativePath)

                            when (method) {
                                "OPTIONS" -> {
                                    call.response.headers.append("Allow", "OPTIONS, GET, HEAD, PROPFIND, PUT, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK")
                                    call.response.headers.append("DAV", "1, 2")
                                    call.response.headers.append("MS-Author-Via", "DAV")
                                    call.respond(HttpStatusCode.OK, "")
                                }

                                "HEAD" -> {
                                    val res = RootUtils.execute("test -e '${requestedFile.absolutePath}'")
                                    if (res.isSuccess) call.respond(HttpStatusCode.OK)
                                    else call.respond(HttpStatusCode.NotFound)
                                }

                                "PROPFIND" -> {
                                    if (RootUtils.execute("test -e '${requestedFile.absolutePath}'").isFailure) {
                                        call.respond(HttpStatusCode.NotFound)
                                        return@handle
                                    }
                                    val depth = call.request.headers["Depth"] ?: "1"
                                    val xmlResponse = buildWebDavXml(encodedPath, requestedFile, internalRootDir, depth)
                                    call.respondText(xmlResponse, ContentType.parse("application/xml; charset=utf-8"), HttpStatusCode.MultiStatus)
                                }

                                "PUT" -> {
                                    try {
                                        val tempFile = File(context.cacheDir, "upload_${UUID.randomUUID()}")
                                        val input = call.receiveChannel()
                                        tempFile.outputStream().use { os ->
                                            input.toInputStream().copyTo(os)
                                        }

                                        val destDir = requestedFile.parentFile?.absolutePath ?: internalRootDir.absolutePath

                                        val moveCmd = "mkdir -p '$destDir' && mv '${tempFile.absolutePath}' '${requestedFile.absolutePath}' && chmod 666 '${requestedFile.absolutePath}'"
                                        val moveRes = RootUtils.execute(moveCmd)

                                        if (moveRes.isSuccess) {
                                            RootUtils.execute("sync '${requestedFile.absolutePath}' || true")
                                            call.respond(HttpStatusCode.Created)
                                        } else {
                                            Log.e(TAG, "PUT move failed: ${moveRes.exceptionOrNull()?.message}")
                                            call.respond(HttpStatusCode.InternalServerError)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "PUT failed: ${e.message}")
                                        call.respond(HttpStatusCode.InternalServerError)
                                    }
                                }

                                "MKCOL" -> {
                                    val res = RootUtils.execute("mkdir -p '${requestedFile.absolutePath}'")
                                    if (res.isSuccess) call.respond(HttpStatusCode.Created)
                                    else call.respond(HttpStatusCode.InternalServerError)
                                }

                                "PROPPATCH" -> {
                                    val responseXml = """<?xml version="1.0" encoding="utf-8" ?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>${escapeXml(encodedPath)}</d:href>
    <d:propstat>
      <d:prop><srtns:srt_modifiedtime xmlns:srtns="http://schemas.microsoft.com/exchange/"/></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>""".trimMargin()
                                    call.respondText(responseXml, ContentType.parse("application/xml; charset=utf-8"), HttpStatusCode.MultiStatus)
                                }

                                "DELETE" -> {
                                    val res = RootUtils.execute("rm -rf '${requestedFile.absolutePath}'")
                                    if (res.isSuccess) call.respond(HttpStatusCode.NoContent)
                                    else call.respond(HttpStatusCode.InternalServerError)
                                }

                                "MOVE", "COPY" -> {
                                    val destHeader = call.request.headers["Destination"] ?: ""
                                    val destUri = try {
                                        val url = java.net.URL(destHeader)
                                        java.net.URLDecoder.decode(url.path, "UTF-8")
                                    } catch (e: Exception) {
                                        java.net.URLDecoder.decode(destHeader.substringAfter(":8080"), "UTF-8")
                                    }.removePrefix("/")

                                    val destFile = File(internalRootDir, destUri)
                                    val cmd = if (method == "MOVE") "mv" else "cp -r"
                                    val destParent = destFile.parentFile?.absolutePath ?: internalRootDir.absolutePath

                                    val res = RootUtils.execute("mkdir -p '$destParent' && $cmd '${requestedFile.absolutePath}' '${destFile.absolutePath}'")
                                    if (res.isSuccess) call.respond(HttpStatusCode.Created)
                                    else call.respond(HttpStatusCode.InternalServerError)
                                }

                                "LOCK" -> {
                                    val token = "opaquelocktoken:${UUID.randomUUID()}"
                                    call.response.headers.append("Lock-Token", "<$token>")
                                    val lockXml = """<?xml version="1.0" encoding="utf-8" ?>
                                        |<d:prop xmlns:d="DAV:">
                                        |  <d:lockdiscovery>
                                        |    <d:activelock>
                                        |      <d:locktype><d:write/></d:locktype>
                                        |      <d:lockscope><d:exclusive/></d:lockscope>
                                        |      <d:depth>0</d:depth>
                                        |      <d:owner><d:href>DriveTool</d:href></d:owner>
                                        |      <d:timeout>Second-3600</d:timeout>
                                        |      <d:locktoken><d:href>$token</d:href></d:locktoken>
                                        |      <d:lockroot><d:href>$encodedPath</d:href></d:lockroot>
                                        |    </d:activelock>
                                        |  </d:lockdiscovery>
                                        |</d:prop>""".trimMargin()
                                    call.respondText(lockXml, ContentType.parse("application/xml; charset=utf-8"), HttpStatusCode.OK)
                                }

                                "UNLOCK" -> call.respond(HttpStatusCode.NoContent)
                            }
                        }
                    }

                    get("/{name...}") {
                        val encodedPath = call.request.uri.substringBefore("?")
                        val relativePath = try {
                            java.net.URLDecoder.decode(encodedPath, "UTF-8").removePrefix("/")
                        } catch (e: Exception) {
                            call.parameters.getAll("name")?.joinToString(File.separator) ?: ""
                        }
                        val file = File(internalRootDir, relativePath)

                        if (file.exists() && !file.isDirectory) {
                            call.respondFile(file)
                        } else if (file.isDirectory) {
                            call.respondText("DriveTool WebDAV is active.")
                        } else {
                            val check = RootUtils.execute("test -f '${file.absolutePath}'")
                            if (check.isSuccess) {
                                val temp = File(context.cacheDir, "download_${UUID.randomUUID()}")
                                val copied = RootUtils.execute("cp '${file.absolutePath}' '${temp.absolutePath}' && chmod 666 '${temp.absolutePath}'")
                                if (copied.isSuccess && temp.exists()) {
                                    call.respondFile(temp)
                                    launchCleanUpTask(context, temp)
                                } else {
                                    call.respond(HttpStatusCode.InternalServerError)
                                }
                            } else {
                                call.respond(HttpStatusCode.NotFound)
                            }
                        }
                    }
                }
            }

            engine.start(wait = false)
            server = engine
            Result.success(Unit)
        } catch (e: Exception) {
            server = null
            Log.e(TAG, "Failed to start WebDAV server", e)
            Result.failure(e)
        }
    }

    private fun buildWebDavXml(requestUri: String, requestedFile: File, rootDir: File, depth: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n")
        sb.append("<d:multistatus xmlns:d=\"DAV:\">\n")

        val cleanBaseUri = "/" + requestUri.split("/").filter { it.isNotBlank() }.joinToString("/")

        val selfStat = RootUtils.execute("stat -c '%n|%s|%Y|%F' '${requestedFile.absolutePath}'").getOrNull()
        if (selfStat != null) {
            appendFileXml(sb, requestedFile, selfStat, cleanBaseUri, true)
        }

        if (depth != "0") {
            val findResult = RootUtils.execute("find '${requestedFile.absolutePath}' -maxdepth 1 -not -path '${requestedFile.absolutePath}' -exec stat -c '%n|%s|%Y|%F' {} +").getOrNull()

            findResult?.lines()?.filter { it.isNotBlank() }?.forEach { line ->
                val path = line.substringBefore("|")
                val file = File(path)
                appendFileXml(sb, file, line, cleanBaseUri, false)
            }
        }

        sb.append("</d:multistatus>")
        return sb.toString()
    }

    private fun appendFileXml(sb: StringBuilder, file: File, statLine: String, cleanBaseUri: String, isRoot: Boolean) {
        val parts = statLine.split("|")
        if (parts.size < 4) return

        val size = parts[1].toLongOrNull() ?: 0L
        val lastMod = (parts[2].toLongOrNull() ?: 0L) * 1000
        val type = parts[3]
        val isDir = type.contains("directory")

        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val creationSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

        val fileUri = if (isRoot) {
            cleanBaseUri
        } else {
            val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            if (cleanBaseUri.endsWith("/")) "$cleanBaseUri$encodedName" else "$cleanBaseUri/$encodedName"
        }

        val normalizedUri = if (isDir && !fileUri.endsWith("/")) "$fileUri/" else fileUri
        val etag = "\"${size}-${lastMod}\""

        sb.append("  <d:response>\n")
        sb.append("    <d:href>${escapeXml(normalizedUri)}</d:href>\n")
        sb.append("    <d:propstat>\n")
        sb.append("      <d:prop>\n")
        sb.append("        <d:displayname>${escapeXml(file.name)}</d:displayname>\n")
        sb.append("        <d:getetag>$etag</d:getetag>\n")
        if (isDir) {
            sb.append("        <d:resourcetype><d:collection/></d:resourcetype>\n")
            sb.append("        <d:getcontenttype>httpd/unix-directory</d:getcontenttype>\n")
        } else {
            sb.append("        <d:resourcetype/>\n")
            sb.append("        <d:getcontentlength>$size</d:getcontentlength>\n")
            val ext = file.extension.lowercase()
            val mime = if (ext == "iso") "application/x-cd-image" else "application/octet-stream"
            sb.append("        <d:getcontenttype>$mime</d:getcontenttype>\n")
        }
        sb.append("        <d:getlastmodified>${sdf.format(lastMod)}</d:getlastmodified>\n")
        sb.append("        <d:creationdate>${creationSdf.format(lastMod)}</d:creationdate>\n")
        sb.append("        <d:isreadonly>0</d:isreadonly>\n")
        sb.append("      </d:prop>\n")
        sb.append("      <d:status>HTTP/1.1 200 OK</d:status>\n")
        sb.append("    </d:propstat>\n")
        sb.append("  </d:response>\n")
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun launchCleanUpTask(context: Context, target: File) {
        Thread {
            try {
                Thread.sleep(60000)
                if (target.exists()) target.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Cache cleanup task anomaly: ${e.message}")
            }
        }.start()
    }

    fun stop(): Result<Unit> {
        Log.d(TAG, "Stopping WebDAV server")
        val currentServer = server
        server = null
        currentServer?.stop(1000, 5000)
        return Result.success(Unit)
    }
}
