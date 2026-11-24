package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.adb.OwnListeningPorts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * 登录回调用的本机小服务(用 ChatGPT、OpenRouter、如意登录共用):只绑 127.0.0.1(别的设备连不进来),端口由系统挑一个空闲的。浏览器登录完跳回
 * `http://127.0.0.1:<端口>[path]?…`(或写成 localhost),这里读出参数,把这次登录做完(换令牌、核对)再回一页结果;做的期间浏览器那边
 * 一直等着这个连接。别的路径(如浏览器顺手要的图标)回 404。只收一次回调;用完或放弃都要 [close]。
 *
 * 有的服务(如如意)把授权码经 URL 片段(`#code=…&state=…`)跳回,片段不会发到服务端。这时先收到一次不带查询的 GET,
 * 回一页读 [location.hash] 的小脚本、让它改成 `?code=…&state=…` 再来一次;第二次才带着参数进来,照常处理。
 */
class LoopbackCallback private constructor(private val server: ServerSocket, private val path: String) : Closeable {
    val port: Int get() = server.localPort

    // 找无线调试端口时扫本机,别扫到自己这个监听
    init { OwnListeningPorts.add(server.localPort) }
    val redirectUri: String get() = redirectUri(HOST)

    /** 回调地址写成 [host](`127.0.0.1` 或 `localhost`,都指本机):有的服务只认其中一种写法。 */
    fun redirectUri(host: String): String = "http://$host:$port$path"

    /**
     * 等回调,最多 [timeoutMs];超时为 null。收到回调后先调 [page](可以挂起:在里面把登录做完),
     * 它给出的网页再回给浏览器,所以网页上能写最终结果。协程取消时立即返回(连接关掉)。
     * [accept] 不认的回调(如 state 对不上:不是这次登录发起的)回 400、接着等。
     * 第一次进来没带查询的(授权码在 URL 片段里,被浏览器剥掉了)回一页转跳脚本、接着等第二次带查询的。
     */
    suspend fun await(
        timeoutMs: Long,
        accept: (Map<String, String>) -> Boolean = { true },
        page: suspend (Map<String, String>) -> String,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        server.soTimeout = POLL_MS
        while (System.currentTimeMillis() < deadline) {
            ensureActive()
            val socket = try { server.accept() } catch (e: SocketTimeoutException) { continue } catch (e: IOException) { return@withContext null }
            socket.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = try { reader.readLine() } catch (e: IOException) { null } ?: return@use
                // 读掉请求头
                while (true) {
                    val line = try { reader.readLine() } catch (e: IOException) { null }
                    if (line.isNullOrEmpty()) break
                }
                val parts = requestLine.split(' ')
                val target = parts.getOrNull(1).orEmpty()
                val pathOnly = target.substringBefore('?')
                if (parts.firstOrNull() != "GET" || pathOnly != path) {
                    respond(s.getOutputStream(), 404, "Not Found", "text/plain", "Not Found")
                    return@use
                }
                val query = target.substringAfter('?', "")
                // 授权码经 URL 片段回传的服务:片段到不了这里,第一次是不带查询的请求,回一页脚本转成查询再来一次
                if (query.isEmpty()) {
                    respond(s.getOutputStream(), 200, "OK", "text/html; charset=utf-8", fragmentRedirectPage())
                    return@use
                }
                // 转义写坏的(如落单的 %)当作不是这次的回调
                val params = runCatching { parseQuery(query) }.getOrNull()
                if (params == null || !accept(params)) {
                    respond(s.getOutputStream(), 400, "Bad Request", "text/plain", "Bad Request")
                    return@use
                }
                respond(s.getOutputStream(), 200, "OK", "text/html; charset=utf-8", page(params))
                return@withContext params
            }
        }
        null
    }

    override fun close() {
        OwnListeningPorts.remove(server.localPort)
        runCatching { server.close() }
    }

    /** 把 URL 片段里的参数搬进查询串再来一次的小页面;没有片段就只显示一句等待。 */
    private fun fragmentRedirectPage(): String {
        val safe = path.replace("\\", "\\\\").replace("'", "\\'")
        return """<!doctype html><meta charset="utf-8"><title>登录</title><script>
            |var p='$safe';
            |if (location.hash && location.hash.length > 1) {
            |  location.replace(p + '?' + location.hash.slice(1));
            |} else {
            |  document.write('等待登录完成\u2026');
            |}
            |</script>""".trimMargin()
    }

    private fun respond(out: java.io.OutputStream, code: Int, reason: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PATH = "/auth/callback"
        private const val POLL_MS = 300
        private const val READ_TIMEOUT_MS = 5_000

        /** 开一个监听;[port] 为 0 时由系统挑;[path] 是这次登录的回调路径(默认 [PATH])。 */
        fun open(path: String = PATH, port: Int = 0): LoopbackCallback = LoopbackCallback(ServerSocket(port, 4, InetAddress.getByName(HOST)), path)

        fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
