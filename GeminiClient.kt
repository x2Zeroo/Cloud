package com.cloud.assistant

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GeminiClient {
    private const val MODEL = "gemini-3.1-flash-lite"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_INSTRUCTION = """คุณชื่อ Cloud เป็นผู้ช่วย AI พูดไทย ถ้ามีคนถามชื่อให้ตอบว่า "Cloud"
ถ้าผู้ใช้สั่งเปิดแอป ให้ตอบข้อความปกติแล้วปิดท้ายด้วย [OPEN:ชื่อแอป] เลือกจากรายการนี้เท่านั้น: youtube, line, facebook, instagram, maps, phone, sms, email, chrome, settings
ถ้าไม่ใช่คำสั่งเปิดแอป ห้ามใส่ [OPEN:...]
ตอบสั้น กระชับ เป็นธรรมชาติ เหมาะสำหรับพูดออกเสียง"""

    class GeminiException(message: String) : Exception(message)

    /** history: list of (role="user"|"model", text) pairs owned by the caller. */
    suspend fun send(apiKey: String, history: List<Pair<String, String>>): String =
        suspendCancellableCoroutine { cont ->
            val contents = JSONArray().apply {
                history.forEach { (role, text) ->
                    put(JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().put(JSONObject().put("text", text)))
                    })
                }
            }
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION)))
                })
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 512)
                    put("temperature", 0.8)
                })
            }
            val request = Request.Builder()
                .url("$ENDPOINT?key=$apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(GeminiException("เชื่อมต่อ Gemini ไม่สำเร็จ: ${e.message}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val errMsg = runCatching {
                                JSONObject(raw).getJSONObject("error").getString("message")
                            }.getOrDefault("HTTP ${resp.code}")
                            cont.resumeWithException(GeminiException(errMsg))
                            return
                        }
                        runCatching {
                            val json = JSONObject(raw)
                            val candidates = json.optJSONArray("candidates")
                            if (candidates == null || candidates.length() == 0) {
                                cont.resumeWithException(GeminiException("ไม่มีคำตอบ"))
                                return
                            }
                            val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                            val sb = StringBuilder()
                            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
                            cont.resume(sb.toString())
                        }.onFailure { e ->
                            cont.resumeWithException(GeminiException("ตอบกลับผิดรูปแบบ: ${e.message}"))
                        }
                    }
                }
            })
        }
}
