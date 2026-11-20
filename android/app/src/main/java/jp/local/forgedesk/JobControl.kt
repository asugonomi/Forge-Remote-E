package jp.local.forgedesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val CONTROL_ROUTE = "/forge-remote/v1/job-control"

/** The private queue ID stays on its owner's device and the companion. Forge's progress ID is public. */
internal fun forgeRemoteTaskId(id: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(("jp.local.forgedesk/v1:" + id).toByteArray(Charsets.US_ASCII))
    val digits = "0123456789abcdef"
    val hex = buildString(64) {
        for (byte in digest) {
            append(digits[(byte.toInt() ushr 4) and 15])
            append(digits[byte.toInt() and 15])
        }
    }
    return "task(fr-$hex)"
}

data class ForgeControlTicket(val id: String, val unit: Int, val epoch: String)

internal class ForgeJobControls(
    private val scope: CoroutineScope,
    private val isActive: (String, ForgeClient) -> Boolean,
    private val notify: (String) -> Unit,
    private val acknowledged: (String, String) -> Unit
) {
    var ticket by mutableStateOf<ForgeControlTicket?>(null)
        private set
    var status by mutableStateOf("")
        private set
    private var submitting by mutableStateOf<ForgeControlTicket?>(null)
    private var busy by mutableStateOf(false)
    val pending: Boolean get() = busy || (submitting != null && submitting == ticket)
    private var watching: String? = null

    suspend fun watch(id: String, client: ForgeClient) {
        watching = id
        ticket = null
        busy = false
        status = "PCの操作対象を確認中"
        try {
            while (isActive(id, client)) {
                var interval = 800L
                try {
                    val reply = client.post(CONTROL_ROUTE, JSONObject().put("action", "poll").put("proof", id))
                    if (!isActive(id, client)) break
                    if (!reply.optBoolean("available")) {
                        ticket = null
                        busy = false
                        status = "PCのジョブ操作拡張を確認してください"
                        interval = 4500L
                    } else if (!reply.optBoolean("active")) {
                        ticket = null
                        busy = false
                        status = "PCの操作対象を確認中"
                    } else {
                        val unit = reply.getInt("unit")
                        val epoch = reply.getString("epoch")
                        require(unit >= 0 && epoch.matches(Regex("[0-9a-f]{32}")))
                        ticket = ForgeControlTicket(id, unit, epoch)
                        busy = reply.getString("status") != "idle"
                        status = if (busy) "この工程の操作を受け付けました" else ""
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ticket = null
                    busy = false
                    status = "PCのジョブ操作に接続できません"
                    interval = 2500L
                }
                delay(interval)
            }
        } finally {
            if (watching == id) {
                watching = null
                ticket = null
                busy = false
                status = ""
            }
        }
    }

    fun send(expected: ForgeControlTicket, action: String, client: ForgeClient) {
        if (!isActive(expected.id, client) || expected != ticket || pending) {
            notify("この工程は終了したか、操作を受け付け済みです")
            return
        }
        submitting = expected
        val request = JSONObject().put("proof", expected.id).put("action", action)
            .put("epoch", expected.epoch).put("unit", expected.unit).put("request_id", UUID.randomUUID().toString())
        scope.launch {
            try {
                // If the submit reply is lost, read the original receipt. Never replay a command on a later unit.
                var result = try { client.post(CONTROL_ROUTE, request) } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { JSONObject() }
                request.put("action", "receipt")
                repeat(120) {
                    when (result.optString("status")) {
                        "applied" -> if (result.optString("action") == action) {
                            acknowledged(expected.id, action)
                            notify(if (action == "skip") "現在の工程をスキップしました" else "このジョブを中断しました")
                            return@launch
                        }
                        "expired" -> {
                            notify("工程が終了したため、操作は適用されませんでした")
                            return@launch
                        }
                    }
                    delay(650)
                    result = try { client.post(CONTROL_ROUTE, request) } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { JSONObject() }
                }
                notify("PCから操作結果を確認できませんでした")
            } finally {
                if (submitting == expected) submitting = null
            }
        }
    }
}
