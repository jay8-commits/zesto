package com.example.zesto.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/**
 * Result of an RTSP connection probe.
 */
sealed class RTSPProbeResult {
    data class Success(val message: String, val publicMethods: String? = null) : RTSPProbeResult()
    data class AuthRequired(val message: String) : RTSPProbeResult()
    data class ServerError(val code: Int, val message: String) : RTSPProbeResult()
    data class Timeout(val message: String) : RTSPProbeResult()
    data class NetworkUnreachable(val message: String) : RTSPProbeResult()
    data class InvalidUrl(val message: String) : RTSPProbeResult()
    data class Failed(val message: String) : RTSPProbeResult()

    fun toDisplayString(): String = when (this) {
        is Success -> "CONNECTED: $message"
        is AuthRequired -> "AUTHENTICATION REQUIRED: $message"
        is ServerError -> "RTSP SERVER ERROR: (Code $code) $message"
        is Timeout -> "TIMEOUT: $message"
        is NetworkUnreachable -> "NETWORK UNREACHABLE: $message"
        is InvalidUrl -> "INVALID URL: $message"
        is Failed -> "FAILED: $message"
    }

    val isConnected: Boolean get() = this is Success
}

/**
 * Real RFC 2326 RTSP Connection Tester.
 * Sends an RTSP OPTIONS handshake over raw TCP socket to verify server availability,
 * reachability, and RTSP protocol compliance without starting full decoding.
 */
object RTSPConnectionTester {

    suspend fun probe(url: String, timeoutMs: Long = 4000L): RTSPProbeResult = withContext(Dispatchers.IO) {
        val uri = try {
            val parsed = URI(url)
            if (parsed.scheme?.lowercase() != "rtsp") {
                return@withContext RTSPProbeResult.InvalidUrl("URL scheme must be 'rtsp://'")
            }
            if (parsed.host.isNullOrBlank()) {
                return@withContext RTSPProbeResult.InvalidUrl("Missing hostname or IP address")
            }
            parsed
        } catch (e: Exception) {
            return@withContext RTSPProbeResult.InvalidUrl("Malformed RTSP URL: ${e.message}")
        }

        val host = uri.host
        val port = if (uri.port > 0) uri.port else 554
        val path = if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath

        val socket = Socket()
        try {
            socket.soTimeout = timeoutMs.toInt()
            socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())

            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            // RFC 2326 Section 10.1: OPTIONS request
            val rtspRequest = "OPTIONS rtsp://$host:$port$path RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "User-Agent: Zesto/1.0 (Android Systems Pipeline)\r\n\r\n"

            writer.write(rtspRequest)
            writer.flush()

            val statusLine = reader.readLine()
            if (statusLine == null) {
                return@withContext RTSPProbeResult.Failed("Server closed socket immediately without response")
            }

            var publicMethods: String? = null
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Public:", ignoreCase = true)) {
                    publicMethods = line.substringAfter(":").trim()
                }
                line = reader.readLine()
            }

            // Parse status code (e.g. RTSP/1.0 200 OK or RTSP/1.0 401 Unauthorized)
            val parts = statusLine.split(" ", limit = 3)
            val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val statusReason = parts.getOrNull(2) ?: statusLine

            when (statusCode) {
                200 -> {
                    val methodInfo = if (publicMethods != null) " (Methods: $publicMethods)" else ""
                    RTSPProbeResult.Success("RTSP 200 OK at $host:$port$methodInfo", publicMethods)
                }
                401 -> RTSPProbeResult.AuthRequired("RTSP 401 Unauthorized at $host:$port - Credentials required")
                404 -> RTSPProbeResult.ServerError(404, "RTSP 404 Not Found at $host:$port$path - Check OBS stream name")
                in 400..499 -> RTSPProbeResult.ServerError(statusCode, "RTSP Client Error ($statusCode $statusReason)")
                in 500..599 -> RTSPProbeResult.ServerError(statusCode, "RTSP Server Error ($statusCode $statusReason)")
                else -> RTSPProbeResult.Success("Server responded: $statusLine")
            }
        } catch (e: SocketTimeoutException) {
            RTSPProbeResult.Timeout("No response from $host:$port within ${timeoutMs}ms")
        } catch (e: UnknownHostException) {
            RTSPProbeResult.InvalidUrl("Host '$host' cannot be resolved. Check IP address")
        } catch (e: ConnectException) {
            val msg = e.localizedMessage ?: ""
            if (msg.contains("refused", ignoreCase = true)) {
                RTSPProbeResult.Failed("Connection refused on $host:$port. Ensure OBS RTSP server is running")
            } else {
                RTSPProbeResult.Failed("Connect failed: $msg")
            }
        } catch (e: NoRouteToHostException) {
            RTSPProbeResult.NetworkUnreachable("No route to host $host. Check Wi-Fi and subnet")
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.contains("EHOSTUNREACH", ignoreCase = true) || msg.contains("ENETUNREACH", ignoreCase = true)) {
                RTSPProbeResult.NetworkUnreachable("Host unreachable at $host:$port")
            } else {
                RTSPProbeResult.Failed("Error: $msg")
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}
