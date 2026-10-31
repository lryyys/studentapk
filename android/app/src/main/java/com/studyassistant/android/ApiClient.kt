package com.studyassistant.android

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class UserProfile(val tgId: Long, val fullName: String, val role: String)
data class LessonItem(val id: Int, val dateStr: String, val dayLabel: String, val startTime: String, val endTime: String, val lessonType: String, val subject: String, val teacher: String, val room: String)
data class HomeworkItem(val id: Int, val dateStr: String, val subject: String, val description: String, val createdAt: String)
data class ReminderItem(val id: Int, val title: String, val remindDate: String, val remindTime: String, val isSent: Boolean)
data class AdminUserItem(val tgId: Long, val username: String, val fullName: String, val role: String, val status: String)

data class SyncSnapshot(val user: UserProfile, val lessons: List<LessonItem>, val homework: List<HomeworkItem>, val reminders: List<ReminderItem>)

class ApiClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("study_assistant_v2", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "https://your-service.run.app") ?: "https://your-service.run.app"
        set(value) = prefs.edit().putString("base_url", value.trimEnd('/')).apply()

    var apiToken: String?
        get() = prefs.getString("api_token", null)
        set(value) = prefs.edit().putString("api_token", value).apply()

    fun logout() { prefs.edit().remove("api_token").remove("cached_sync").apply() }

    fun getCachedSync(): SyncSnapshot? {
        val raw = prefs.getString("cached_sync", null) ?: return null
        return try { parseSyncJson(JSONObject(raw)) } catch (e: Exception) { null }
    }

    suspend fun pairWithCode(code: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("pair_code", code.trim())
            val res = request("POST", "/api/auth/pair", body.toString(), auth = false)
            val json = JSONObject(res)
            apiToken = json.getString("api_token")
            Result.success(UserProfile(json.getLong("tg_id"), json.getString("full_name"), json.getString("role")))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun syncAll(): Result<SyncSnapshot> = withContext(Dispatchers.IO) {
        try {
            val res = request("GET", "/api/sync")
            prefs.edit().putString("cached_sync", res).apply()
            Result.success(parseSyncJson(JSONObject(res)))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun createHomework(dateStr: String, subject: String, description: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("date_str", dateStr).put("subject", subject).put("description", description)
            request("POST", "/api/homework", body.toString())
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteHomework(id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try { request("DELETE", "/api/homework/$id"); Result.success(Unit) } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun createReminder(title: String, remindDate: String, remindTime: String, lessonId: Int? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("title", title).put("remind_date", remindDate).put("remind_time", remindTime)
            if (lessonId != null) body.put("lesson_id", lessonId)
            request("POST", "/api/reminders", body.toString())
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteReminder(id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try { request("DELETE", "/api/reminders/$id"); Result.success(Unit) } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getAdminUsers(): Result<List<AdminUserItem>> = withContext(Dispatchers.IO) {
        try {
            val res = request("GET", "/api/admin/users")
            val arr = JSONArray(res)
            val list = mutableListOf<AdminUserItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(AdminUserItem(o.getLong("tg_id"), o.optString("username", ""), o.getString("full_name"), o.getString("role"), o.getString("status")))
            }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun setUserStatus(tgId: Long, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        try { request("POST", "/api/admin/users/$tgId/status?status=$status", ""); Result.success(Unit) } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun sendBroadcast(text: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("message_text", text)
            val res = request("POST", "/api/admin/broadcast", body.toString())
            Result.success(JSONObject(res).optInt("sent_count", 0))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun upsertLesson(dateStr: String, dayLabel: String, start: String, end: String, type: String, subj: String, teacher: String, room: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("date_str", dateStr).put("day_label", dayLabel).put("start_time", start).put("end_time", end).put("lesson_type", type).put("subject", subj).put("teacher", teacher).put("room", room)
            request("PUT", "/api/admin/schedule/lesson", body.toString())
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun uploadExcelUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Файл не найден")
            val boundary = "Boundary-${UUID.randomUUID()}"
            val conn = (URL("$baseUrl/api/admin/schedule/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiToken")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"schedule.xlsx\"\r\n")
                out.writeBytes("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n")
                out.write(bytes)
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val text = BufferedReader(InputStreamReader(if (code in 200..299) conn.inputStream else conn.errorStream)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException(text)
            Result.success(JSONObject(text).optInt("imported_lessons", 0))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun request(method: String, path: String, jsonBody: String? = null, auth: Boolean = true): String {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (auth && !apiToken.isNullOrEmpty()) setRequestProperty("Authorization", "Bearer $apiToken")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = BufferedReader(InputStreamReader(if (code in 200..299) conn.inputStream else conn.errorStream)).use { it.readText() }
        if (code !in 200..299) throw IllegalStateException(text)
        return text
    }

    private fun parseSyncJson(json: JSONObject): SyncSnapshot {
        val u = json.getJSONObject("user")
        val user = UserProfile(u.getLong("tg_id"), u.getString("full_name"), u.getString("role"))
        val lessons = mutableListOf<LessonItem>()
        val lArr = json.getJSONArray("lessons")
        for (i in 0 until lArr.length()) {
            val o = lArr.getJSONObject(i)
            lessons.add(LessonItem(o.getInt("id"), o.getString("date_str"), o.getString("day_label"), o.getString("start_time"), o.getString("end_time"), o.getString("lesson_type"), o.getString("subject"), o.optString("teacher", ""), o.optString("room", "")))
        }
        val homework = mutableListOf<HomeworkItem>()
        val hArr = json.getJSONArray("homework")
        for (i in 0 until hArr.length()) {
            val o = hArr.getJSONObject(i)
            homework.add(HomeworkItem(o.getInt("id"), o.getString("date_str"), o.getString("subject"), o.getString("description"), o.optString("created_at", "")))
        }
        val reminders = mutableListOf<ReminderItem>()
        val rArr = json.getJSONArray("reminders")
        for (i in 0 until rArr.length()) {
            val o = rArr.getJSONObject(i)
            reminders.add(ReminderItem(o.getInt("id"), o.getString("title"), o.getString("remind_date"), o.getString("remind_time"), o.optBoolean("is_sent", false)))
        }
        return SyncSnapshot(user, lessons, homework, reminders)
    }
}
