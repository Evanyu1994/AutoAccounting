package net.ankio.auto.adapter.ezb

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Only fixed local messages may leave this boundary. Never propagate response/exception text. */
class EzbFailure(message: String) : Exception(message)
class EzbClient(private val config: EzbConfig) {
    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        fun normalizeUrl(value: String): String {
            val url = value.trim().toHttpUrlOrNull() ?: throw EzbFailure("地址不正确，请填写 HTTPS 服务器地址")
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null) throw EzbFailure("地址必须为 HTTPS，且不能带账号、查询参数或片段")
            return url.toString().trimEnd('/')
        }
        fun offset() = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
    }
    fun call(path: String, body: Map<String, Any>? = null): JsonElement {
        if (config.token.isBlank()) throw EzbFailure("未填写 API 令牌，请点首页 ezBookkeeping 名称打开连接配置")
        try {
            val request = Request.Builder().url(normalizeUrl(config.url) + "/api/v1/" + path)
                .header("Authorization", "Bearer ${config.token}")
                .header("X-Timezone-Offset", offset().toString())
                .header("Accept", "application/json").apply {
                    if (body != null) post(Gson().toJson(body).toRequestBody("application/json".toMediaType()))
                }.build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401) throw EzbFailure("令牌无效或已过期，请重新配置")
                if (response.code == 403) throw EzbFailure("令牌没开权限，或服务拒绝访问")
                if (response.code != 200) throw EzbFailure("服务器拒绝请求（HTTP ${response.code}），请检查配置与账单")
                val envelope = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                if (envelope.get("success")?.asBoolean != true) throw EzbFailure("服务器未接受请求，请检查账户、分类及权限")
                return envelope.get("result") ?: throw EzbFailure("服务器响应格式不正确")
            }
        } catch (e: EzbFailure) { throw e }
        catch (_: java.io.IOException) { throw EzbFailure("地址不通或连接中断，请检查网络；补传前先核对服务器账单") }
        catch (_: Exception) { throw EzbFailure("连接配置或服务器响应格式不正确") }
    }
    fun catalog(): EzbMapping.Catalog {
        try {
            val accounts = mutableListOf<EzbMapping.Account>()
            fun readAccounts(nodes: JsonElement) {
                for (node in nodes.asJsonArray) {
                    val a = node.asJsonObject
                    if (a.get("hidden")?.asBoolean == true) continue
                    if (a.get("type").asInt == 2) {
                        a.get("subAccounts")?.let { readAccounts(it) }
                    } else accounts.add(EzbMapping.Account(a.get("id").asString, a.get("name").asString, a.get("currency").asString))
                }
            }
            readAccounts(call("accounts/list.json"))
            val categories = mutableListOf<EzbMapping.Category>()
            for ((_, group) in call("transaction/categories/list.json").asJsonObject.entrySet()) {
                for (node in group.asJsonArray) {
                    val p = node.asJsonObject
                    if (p.get("hidden")?.asBoolean == true) continue
                    val children = p.getAsJsonArray("subCategories") ?: continue
                    for (child in children) {
                        val c = child.asJsonObject
                        if (c.get("hidden")?.asBoolean == true) continue
                        categories.add(EzbMapping.Category(c.get("id").asString, c.get("name").asString,
                            p.get("id").asString, p.get("name").asString, p.get("type").asInt))
                    }
                }
            }
            val tags = call("transaction/tags/list.json").asJsonArray.mapNotNull {
                val t = it.asJsonObject
                if (t.get("hidden")?.asBoolean == true) null else EzbMapping.Tag(t.get("id").asString, t.get("name").asString)
            }
            return EzbMapping.Catalog(accounts, categories, tags)
        } catch (e: EzbFailure) { throw e }
        catch (_: Exception) { throw EzbFailure("账户或分类响应格式不正确") }
    }
}
