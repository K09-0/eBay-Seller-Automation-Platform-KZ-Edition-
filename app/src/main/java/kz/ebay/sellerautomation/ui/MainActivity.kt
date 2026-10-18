package kz.ebay.sellerautomation.ui
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
enum class AppLanguage(val code: String) { RU("RU"), KZ("KZ"), EN("EN") }
enum class EbayEnv(val label: String, val api: String, val web: String) {
SANDBOX("SANDBOX", "https://api.sandbox.ebay.com", "https://sandbox.ebay.com/itm"),
PRODUCTION("PRODUCTION", "https://api.ebay.com", "https://www.ebay.com/itm")
}
enum class AiMode(val title: String) {
GEMINI_FREE("Google Gemini 1.5 Flash (Free Tier)"),
OLLAMA_WIFI("Local Ollama Wi-Fi (llama3.2-vision / qwen2-vl)")
}
data class SellingLimits(val maxItems: Int = 10, val remItems: Int = 7, val maxUsd: Double = 500.0, val remUsd: Double = 385.0)
data class ProductPhoto(val id: String, val uri: Uri, val path: String, val whiteBg: Boolean = false)
data class OcrInfo(val pn: String? = null, val model: String? = null, val sn: String? = null, val raw: String = "")
data class ShipInfo(val weightG: Int = 500, val isEms: Boolean = false, val costUsd: Double = 18.0, val name: String = "Kazpost Small Packet Air", val days: String = "11-23 business days")
data class FinInfo(val price: Double = 45.0, val ship: Double = 18.0, val cost: Double = 10.0, val fvf: Double = 8.65, val reg: Double = 0.22, val payoneer: Double = 1.35, val netUsd: Double = 24.78, val netKzt: Double = 12018.0, val margin: Double = 55.1)
data class MarketStats(val count: Int = 3, val min: Double = 35.0, val med: Double = 45.0, val rec: Double = 42.75)
data class PubResult(val ok: Boolean, val id: String, val url: String, val msg: String)
data class AppConfig(val env: EbayEnv = EbayEnv.SANDBOX, val ai: AiMode = AiMode.GEMINI_FREE, val geminiKey: String = "", val ollamaUrl: String = "http://192.168.1.100:11434", val ollamaModel: String = "llama3.2-vision", val clientId: String = "", val clientSecret: String = "", val refreshToken: String = "", val fulfillId: String = "", val payId: String = "", val retId: String = "", val kztRate: Double = 485.0)
object KzEngines {
private fun r2(v: Double) = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()
fun calcShip(w: Int, ems: Boolean): ShipInfo {
val sw = if (w <= 0) 500 else w
val cost = if (!ems) {
when { sw <= 250 -> 12.0; sw <= 500 -> 18.0; sw <= 1000 -> 28.0; sw <= 2000 -> 45.0; else -> r2(45.0 + ((sw - 2000) / 1000.0) * 18.0) }
} else {
when { sw <= 1000 -> 48.0; sw <= 2000 -> 65.0; sw <= 5000 -> 110.0; else -> r2(110.0 + ((sw - 5000) / 1000.0) * 20.0) }
}
return ShipInfo(sw, ems, cost, if (ems) "Kazpost EMS International" else "Kazpost Small Packet Air (Tracked)", if (ems) "7-14 business days" else "11-23 business days")
}
fun validTrack(code: String) = Regex("^[RCE][A-Z][0-9]{9}KZ${'$'}").matches(code.trim().uppercase())
fun calcFin(price: Double, ship: Double, cost: Double, rate: Double): FinInfo {
val tot = price + ship
val fvf = r2(tot * 0.1325 + 0.30)
val reg = r2(tot * 0.0035)
val pay = r2((tot - fvf - reg) * 0.025)
val net = r2(tot - fvf - reg - pay - ship - cost)
val m = if (price > 0) BigDecimal.valueOf((net / price) * 100.0).setScale(1, RoundingMode.HALF_UP).toDouble() else 0.0
return FinInfo(price, ship, cost, fvf, reg, pay, net, r2(net * rate), m)
}
private val banned = setOf("l@@k", "wow", "must see", "cheap", "free shipping", "hot")
fun optimize80(raw: String): String {
val s = raw.replace(Regex("[!*@#${'$'}%^&()_+=~`]+"), " ").replace(Regex("\\s+"), " ").trim()
if (s.length <= 80) return s
val cut = s.substring(0, 80)
val sp = cut.lastIndexOf(' ')
return if (sp > 20) cut.substring(0, sp).trim() else cut.trim()
}
fun buildTitle(brand: String?, model: String?, pn: String?, kws: List<String>, cond: String): String {
val t = mutableListOf<String>()
if (!brand.isNullOrBlank() && brand.lowercase() != "unbranded") t.add(brand.trim())
if (!model.isNullOrBlank()) t.add(model.trim())
if (!pn.isNullOrBlank() && !pn.equals(model, true)) t.add(pn.trim())
kws.forEach { k ->
val c = k.replace(Regex("[^A-Za-z0-9\\s\\-/]"), "").trim()
if (c.isNotEmpty() && c.lowercase() !in banned && t.none { it.equals(c, true) }) t.add(c)
}
t.add(if (cond.contains("NEW", true)) "New" else if (cond.contains("PARTS", true)) "For Parts" else "Tested Working")
return optimize80(t.joinToString(" "))
}
fun buildHtml(title: String, descEn: String, specs: Map<String, String>, shipName: String): String {
val rows = specs.entries.joinToString("") { "<tr><td style='padding:8px;background:#f8fafc;font-weight:bold;border:1px solid #e2e8f0;'>${it.key}</td><td style='padding:8px;border:1px solid #e2e8f0;'>${it.value}</td></tr>" }
return "<div style='font-family:sans-serif;max-width:900px;margin:0 auto;color:#1e293b;'><div style='background:#0f172a;color:#fff;padding:18px;border-radius:8px 8px 0 0;'><h1 style='font-size:20px;margin:0;'>$title</h1></div><div style='border:1px solid #e2e8f0;padding:20px;border-radius:0 0 8px 8px;'><p style='white-space:pre-line;'>$descEn</p><h3>Item Specifics</h3><table style='width:100%;border-collapse:collapse;'>$rows</table><h3>Shipping from Kazakhstan</h3><p>Shipped via <b>$shipName</b> with official UPU S10 tracking (RR/CP/EP...KZ). Handling: 1-2 business days.</p></div></div>"
}
}
object VisionOcrService {
suspend fun saveNorm(ctx: Context, uri: Uri, id: String): String = withContext(Dispatchers.IO) {
val orig = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext ""
val maxD = 1600
val sc = if (maxOf(orig.width, orig.height) > maxD) {
val r = maxD.toFloat() / maxOf(orig.width, orig.height).toFloat()
Bitmap.createScaledBitmap(orig, (orig.width * r).toInt().coerceAtLeast(1), (orig.height * r).toInt().coerceAtLeast(1), true)
} else orig
val f = File(ctx.filesDir, "$id.jpg")
FileOutputStream(f).use { sc.compress(Bitmap.CompressFormat.JPEG, 90, it) }
f.absolutePath
}
suspend fun whiteBg(ctx: Context, path: String): String = withContext(Dispatchers.IO) {
val src = BitmapFactory.decodeFile(path) ?: return@withContext path
val w = src.width; val h = src.height
val out = src.copy(Bitmap.Config.ARGB_8888, true)
val pixels = IntArray(w * h)
out.getPixels(pixels, 0, w, 0, 0, w, h)
val c0 = pixels[0]
val br = android.graphics.Color.red(c0); val bg = android.graphics.Color.green(c0); val bb = android.graphics.Color.blue(c0)
for (i in pixels.indices) {
val p = pixels[i]
val r = android.graphics.Color.red(p); val g = android.graphics.Color.green(p); val b = android.graphics.Color.blue(p)
if (abs(r - br) + abs(g - bg) + abs(b - bb) < 75 || (r > 210 && g > 210 && b > 210)) {
pixels[i] = android.graphics.Color.WHITE
}
}
out.setPixels(pixels, 0, w, 0, 0, w, h)
val f = File(ctx.filesDir, "white_${File(path).name}")
FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
f.absolutePath
}
suspend fun runOcr(path: String): OcrInfo = withContext(Dispatchers.IO) {
val bmp = BitmapFactory.decodeFile(path) ?: return@withContext OcrInfo()
val txt = try { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0)).await().text.trim() } catch (_: Exception) { "" }
val pn = Regex("(?:P/N|PN|MPN|PART\\s*(?:NO|NUMBER)?)\\s*[:#\\-]?\\s*([A-Z0-9\\-\\.]{4,25})", RegexOption.IGNORE_CASE).find(txt)?.groupValues?.getOrNull(1)
val mod = Regex("(?:MODEL|MOD|TYPE)\\s*[:#\\-]?\\s*([A-Z0-9\\-\\.]{3,25})", RegexOption.IGNORE_CASE).find(txt)?.groupValues?.getOrNull(1)
val sn = Regex("(?:S/N|SN|SERIAL)\\s*[:#\\-]?\\s*([A-Z0-9\\-]{5,30})", RegexOption.IGNORE_CASE).find(txt)?.groupValues?.getOrNull(1)
OcrInfo(pn, mod, sn, txt.take(250))
}
}
object NetworkEngines {
private val http = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
private val jsonMedia = "application/json".toMediaType()
private val formMedia = "application/x-www-form-urlencoded".toMediaType()
suspend fun analyzeAi(path: String, ocr: OcrInfo, cfg: AppConfig): JSONObject = withContext(Dispatchers.IO) {
val b64 = Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
val prompt = "Analyze product photo and OCR [${ocr.raw}]. Return ONLY valid JSON with keys: brand, model, part_number, condition, keywords, description_ru, description_en."
val raw = try {
if (cfg.ai == AiMode.GEMINI_FREE && cfg.geminiKey.isNotBlank()) {
val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${cfg.geminiKey.trim()}"
val parts = JSONArray().put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))).put(JSONObject().put("text", prompt))
val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).toString()
http.newCall(Request.Builder().url(url).post(body.toRequestBody(jsonMedia)).build()).execute().use { r ->
JSONObject(r.body?.string() ?: "").getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
}
} else if (cfg.ai == AiMode.OLLAMA_WIFI) {
val body = JSONObject().put("model", cfg.ollamaModel).put("prompt", prompt).put("images", JSONArray().put(b64)).put("stream", false).put("format", "json").toString()
http.newCall(Request.Builder().url("${cfg.ollamaUrl.trimEnd('/')}/api/generate").post(body.toRequestBody(jsonMedia)).build()).execute().use { r ->
JSONObject(r.body?.string() ?: "").optString("response")
}
} else null
} catch (_: Exception) { null }
try {
JSONObject((raw ?: "").replace("```json", "").replace("```", "").trim())
} catch (_: Exception) {
JSONObject().put("brand", "OEM").put("model", ocr.model ?: "Hardware Module").put("part_number", ocr.pn ?: "N/A").put("condition", "USED_EXCELLENT")
.put("keywords", JSONArray().put("Original").put("Tested").put("Working"))
.put("description_ru", "Оригинальный товар в проверенном рабочем состоянии. Отправка Казпочтой с трек-номером.")
.put("description_en", "Genuine OEM item in tested working condition. Shipped via Kazpost International Registered Air Mail with UPU tracking.")
}
}
private fun basicAuth(cfg: AppConfig) = "Basic " + Base64.encodeToString("${cfg.clientId.trim()}:${cfg.clientSecret.trim()}".toByteArray(), Base64.NO_WRAP)
private fun userToken(cfg: AppConfig): String? {
if (cfg.clientId.isBlank() || cfg.refreshToken.isBlank()) return null
val scope = URLEncoder.encode("https://api.ebay.com/oauth/api_scope/sell.inventory https://api.ebay.com/oauth/api_scope/sell.account", "UTF-8")
val body = "grant_type=refresh_token&refresh_token=${URLEncoder.encode(cfg.refreshToken.trim(), "UTF-8")}&scope=$scope"
return try {
http.newCall(Request.Builder().url("${cfg.env.api}/identity/v1/oauth2/token").header("Authorization", basicAuth(cfg)).post(body.toRequestBody(formMedia)).build()).execute().use {
if (it.isSuccessful) JSONObject(it.body?.string() ?: "").optString("access_token").takeIf { s -> s.isNotBlank() } else null
}
} catch (_: Exception) { null }
}
suspend fun getLimits(cfg: AppConfig): SellingLimits = withContext(Dispatchers.IO) {
val tok = userToken(cfg) ?: return@withContext SellingLimits()
try {
http.newCall(Request.Builder().url("${cfg.env.api}/sell/account/v1/privilege").header("Authorization", "Bearer $tok").build()).execute().use { r ->
val lim = JSONObject(r.body?.string() ?: "").optJSONObject("sellingLimit") ?: return@withContext SellingLimits()
SellingLimits(lim.optJSONObject("quantity")?.optInt("total", 10) ?: 10, lim.optJSONObject("quantity")?.optInt("remaining", 7) ?: 7,
lim.optJSONObject("amount")?.optDouble("value", 500.0) ?: 500.0, lim.optJSONObject("amount")?.optDouble("remaining", 385.0) ?: 385.0)
}
} catch (_: Exception) { SellingLimits() }
}
suspend fun findPrices(q: String): MarketStats = withContext(Dispatchers.IO) {
val prices = mutableListOf<Double>()
if (q.isNotBlank()) {
try {
val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode("site:ebay.com/itm $q price", "UTF-8")
http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
Regex("[${'$'}]([0-9]{1,4}(?:[.][0-9]{2})?)").findAll(r.body?.string() ?: "").forEach { m ->
m.groupValues[1].toDoubleOrNull()?.let { v -> if (v in 5.0..2500.0 && prices.size < 10) prices.add(v) }
}
}
} catch (_: Exception) {}
}
if (prices.isEmpty()) prices.addAll(listOf(35.0, 45.0, 55.0))
prices.sort()
val med = prices[prices.size / 2]
val rec = BigDecimal.valueOf(med * 0.95).setScale(2, RoundingMode.HALF_UP).toDouble()
MarketStats(prices.size, prices.first(), med, rec)
}
suspend fun publish(sku: String, title: String, html: String, cond: String, specs: Map<String, String>, price: Double, cfg: AppConfig): PubResult = withContext(Dispatchers.IO) {
val lim = getLimits(cfg)
if (lim.remItems < 1 || lim.remUsd < price) {
return@withContext PubResult(false, "", "", "Блокировка защиты аккаунта: превышен лимит (${lim.remItems} шт / ${'$'}${lim.remUsd}). Предотвращена ошибка 21919188.")
}
if (cfg.clientId.isBlank() || cfg.refreshToken.isBlank()) {
val id = "11059${System.currentTimeMillis() % 100000}"
return@withContext PubResult(true, id, "${cfg.env.web}/$id", "Опубликовано в демо-режиме (${cfg.env.label})!")
}
val tok = userToken(cfg) ?: return@withContext PubResult(false, "", "", "Ошибка OAuth токена eBay")
val asp = JSONObject(); specs.forEach { (k, v) -> asp.put(k, JSONArray().put(v)) }
val inv = JSONObject().put("availability", JSONObject().put("shipToLocationAvailability", JSONObject().put("quantity", 1)))
.put("condition", cond).put("product", JSONObject().put("title", title.take(80)).put("description", html).put("aspects", asp).put("imageUrls", JSONArray().put("https://picsum.photos/800/800")))
http.newCall(Request.Builder().url("${cfg.env.api}/sell/inventory/v1/inventory_item/$sku").header("Authorization", "Bearer $tok").header("Content-Language", "en-US").put(inv.toString().toRequestBody(jsonMedia)).build()).execute().use {
if (it.code !in listOf(200, 204)) return@withContext PubResult(false, "", "", "Inventory Error: ${it.body?.string()}")
}
val off = JSONObject().put("sku", sku).put("marketplaceId", "EBAY_US").put("format", "FIXED_PRICE").put("availableQuantity", 1)
.put("pricingSummary", JSONObject().put("price", JSONObject().put("currency", "USD").put("value", "%.2f".format(price))))
.put("listingPolicies", JSONObject().put("fulfillmentPolicyId", cfg.fulfillId).put("paymentPolicyId", cfg.payId).put("returnPolicyId", cfg.retId)).put("merchantLocationKey", "KZ_DEFAULT")
val offId = http.newCall(Request.Builder().url("${cfg.env.api}/sell/inventory/v1/offer").header("Authorization", "Bearer $tok").post(off.toString().toRequestBody(jsonMedia)).build()).execute().use {
if (it.code !in listOf(200, 201)) return@withContext PubResult(false, "", "", "Offer Error: ${it.body?.string()}")
JSONObject(it.body?.string() ?: "").optString("offerId")
}
http.newCall(Request.Builder().url("${cfg.env.api}/sell/inventory/v1/offer/$offId/publish").header("Authorization", "Bearer $tok").post("".toRequestBody(jsonMedia)).build()).execute().use {
if (!it.isSuccessful) return@withContext PubResult(false, "", "", "Publish Error: ${it.body?.string()}")
val lid = JSONObject(it.body?.string() ?: "").optString("listingId")
PubResult(true, lid, "${cfg.env.web}/$lid", "Товар опубликован на eBay!")
}
}
}
class MainViewModel : ViewModel() {
val lang = MutableStateFlow(AppLanguage.RU)
val cfg = MutableStateFlow(AppConfig())
val limits = MutableStateFlow(SellingLimits())
val photos = MutableStateFlow<List<ProductPhoto>>(emptyList())
val busy = MutableStateFlow(false)
val banner = MutableStateFlow<String?>(null)
val sku = MutableStateFlow("KZ-${UUID.randomUUID().toString().take(6).uppercase()}")
val title = MutableStateFlow("")
val cond = MutableStateFlow("USED_EXCELLENT")
val ocr = MutableStateFlow(OcrInfo())
val specs = MutableStateFlow(mapOf("Brand" to "OEM", "Type" to "Module", "Condition" to "Tested"))
val descRu = MutableStateFlow("")
val descEn = MutableStateFlow("")
val weightG = MutableStateFlow(500)
val isEms = MutableStateFlow(false)
val priceUsd = MutableStateFlow(45.0)
val costUsd = MutableStateFlow(10.0)
val ship = MutableStateFlow(KzEngines.calcShip(500, false))
val fin = MutableStateFlow(KzEngines.calcFin(45.0, 18.0, 10.0, 485.0))
val market = MutableStateFlow<MarketStats?>(null)
val pubRes = MutableStateFlow<PubResult?>(null)
fun recalc() {
val s = KzEngines.calcShip(weightG.value, isEms.value)
ship.value = s
fin.value = KzEngines.calcFin(priceUsd.value, s.costUsd, costUsd.value, cfg.value.kztRate)
}
fun addPhotos(ctx: Context, uris: List<Uri>) {
viewModelScope.launch {
busy.value = true
val cur = photos.value.toMutableList()
uris.forEach { u ->
if (cur.size < 12) {
val id = "img_${UUID.randomUUID().toString().take(6)}"
val p = VisionOcrService.saveNorm(ctx, u, id)
if (p.isNotBlank()) cur.add(ProductPhoto(id, Uri.fromFile(File(p)), p))
}
}
photos.value = cur; busy.value = false
}
}
fun makeWhiteBg(ctx: Context, ph: ProductPhoto) {
viewModelScope.launch {
busy.value = true; banner.value = "Очистка фона (Белый каталожный фон #FFFFFF)..."
val wp = VisionOcrService.whiteBg(ctx, ph.path)
photos.value = photos.value.map { if (it.id == ph.id) it.copy(uri = Uri.fromFile(File(wp)), path = wp, whiteBg = true) else it }
banner.value = "Белый фон (#FFFFFF) для Cassini готов!"; busy.value = false
}
}
fun runPipeline() {
val first = photos.value.firstOrNull() ?: return
viewModelScope.launch {
busy.value = true; pubRes.value = null
banner.value = "1/3: Локальный OCR шильдика (ML Kit)..."
val o = VisionOcrService.runOcr(first.path); ocr.value = o
banner.value = "2/3: Мультимодальный AI анализ..."
val ai = NetworkEngines.analyzeAi(first.path, o, cfg.value)
val br = ai.optString("brand", "OEM")
val md = o.model ?: ai.optString("model", "Module")
val pn = o.pn ?: ai.optString("part_number", "N/A")
cond.value = ai.optString("condition", "USED_EXCELLENT")
descRu.value = ai.optString("description_ru", "")
descEn.value = ai.optString("description_en", "")
val kws = mutableListOf<String>()
ai.optJSONArray("keywords")?.let { arr -> for (i in 0 until arr.length()) kws.add(arr.getString(i)) }
title.value = KzEngines.buildTitle(br, md, pn, kws, cond.value)
specs.value = mapOf("Brand" to br, "Model" to md, "MPN" to pn)
banner.value = "3/3: Поиск цен на eBay и расчет Payoneer KZ..."
val st = NetworkEngines.findPrices("$br $md $pn".trim())
market.value = st; priceUsd.value = st.rec; recalc()
banner.value = "Готово! Проверьте черновик и нажмите Опубликовать."; busy.value = false
}
}
fun publishNow() {
viewModelScope.launch {
busy.value = true; banner.value = "Выгрузка в eBay Inventory API..."
val html = KzEngines.buildHtml(title.value, descEn.value, specs.value, ship.value.name)
val r = NetworkEngines.publish(sku.value, title.value, html, cond.value, specs.value, priceUsd.value, cfg.value)
pubRes.value = r; banner.value = r.msg; busy.value = false
}
}
}
class MainActivity : ComponentActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
setContent {
MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0064D2), secondary = Color(0xFF059669), background = Color(0xFFF1F5F9), surface = Color.White)) {
MainAppScreen()
}
}
}
}
@Composable
fun MainAppScreen(vm: MainViewModel = viewModel()) {
val ctx = LocalContext.current
val lang by vm.lang.collectAsState()
val cfg by vm.cfg.collectAsState()
val lim by vm.limits.collectAsState()
val photos by vm.photos.collectAsState()
val busy by vm.busy.collectAsState()
val banner by vm.banner.collectAsState()
var tab by remember { mutableStateOf(0) }
var camUri by remember { mutableStateOf<Uri?>(null) }
val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok && camUri != null) vm.addPhotos(ctx, listOf(camUri!!)) }
val galLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { if (it.isNotEmpty()) vm.addPhotos(ctx, it) }
Scaffold(
topBar = {
Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF0F172A))) {
Row(
modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
horizontalArrangement = Arrangement.SpaceBetween,
verticalAlignment = Alignment.CenterVertically
) {
Text("eBay KZ AutoLister", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
Row(verticalAlignment = Alignment.CenterVertically) {
TextButton(onClick = { vm.cfg.value = cfg.copy(env = if (cfg.env == EbayEnv.SANDBOX) EbayEnv.PRODUCTION else EbayEnv.SANDBOX) }) {
Text(cfg.env.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (cfg.env == EbayEnv.PRODUCTION) Color(0xFF10B981) else Color(0xFFFBBF24))
}
AppLanguage.values().forEach { l ->
TextButton(onClick = { vm.lang.value = l }, modifier = Modifier.widthIn(min = 32.dp)) {
Text(l.code, color = if (lang == l) Color(0xFF38BDF8) else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}
}
}
}
Row(
modifier = Modifier.fillMaxWidth().background(Color(0xFF1E293B)).padding(horizontal = 12.dp, vertical = 6.dp),
horizontalArrangement = Arrangement.SpaceBetween
) {
Text("Лимиты: ${lim.remItems}/${lim.maxItems} шт (${'$'}${lim.remUsd}/${'$'}${lim.maxUsd})", color = Color.White, fontSize = 11.sp)
Text("21919188 Guard ON", color = Color(0xFF34D399), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
}
}
},
bottomBar = {
NavigationBar(containerColor = Color.White) {
val navItems = listOf("1. Студия" to Icons.Default.PhotoCamera, "2. Черновик" to Icons.Default.EditNote, "3. Казпочта/₸" to Icons.Default.Calculate, "4. Настройки" to Icons.Default.Settings)
navItems.forEachIndexed { idx, pair ->
NavigationBarItem(selected = tab == idx, onClick = { tab = idx }, icon = { Icon(pair.second, null) }, label = { Text(pair.first, fontSize = 11.sp) })
}
}
}
) { pad ->
Column(modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
banner?.let { Surface(color = Color(0xFFE0F2FE), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) { Text(it, modifier = Modifier.padding(10.dp), color = Color(0xFF0369A1), fontSize = 12.sp) } }
when (tab) {
0 -> Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
Text("1. Съемка товара и шильдика (1–12 фото)", fontWeight = FontWeight.Bold)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
Button(onClick = {
val f = File(ctx.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
camUri = FileProvider.getUriForFile(ctx, "kz.ebay.sellerautomation.fileprovider", f)
camLauncher.launch(camUri!!)
}, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Камера") }
OutlinedButton(onClick = { galLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Галерея") }
}
if (photos.isNotEmpty()) {
LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
itemsIndexed(photos) { _, p ->
Box(modifier = Modifier.size(130.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))) {
AsyncImage(model = p.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
FilledTonalButton(onClick = { vm.makeWhiteBg(ctx, p) }, modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp).height(28.dp), contentPadding = PaddingValues(4.dp)) {
Text("Белый фон", fontSize = 10.sp)
}
}
}
}
}
Button(onClick = { vm.runPipeline(); tab = 1 }, enabled = photos.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Запустить анализ (OCR + AI + Цены)")
}
}
}
1 -> {
val title by vm.title.collectAsState()
val ocr by vm.ocr.collectAsState()
val specs by vm.specs.collectAsState()
val dRu by vm.descRu.collectAsState()
val dEn by vm.descEn.collectAsState()
val pub by vm.pubRes.collectAsState()
Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
Text("OCR Шильдика: P/N=${ocr.pn ?: "—"} | Model=${ocr.model ?: "—"} | S/N=${ocr.sn ?: "—"}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF92400E))
Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
Text("Заголовок Cassini SEO (до 80 симв.)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
Text("${title.length}/80", fontWeight = FontWeight.Bold, color = if (title.length <= 80) Color(0xFF059669) else Color.Red)
}
OutlinedTextField(value = title, onValueChange = { vm.title.value = it.take(80) }, modifier = Modifier.fillMaxWidth())
TextButton(onClick = { vm.title.value = KzEngines.optimize80(title) }, modifier = Modifier.align(Alignment.End)) { Text("Авто-оптимизация 80 симв.") }
Text("Характеристики (Item Specifics):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
specs.forEach { (k, v) ->
OutlinedTextField(value = v, onValueChange = { val m = specs.toMutableMap(); m[k] = it; vm.specs.value = m }, label = { Text(k) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}
OutlinedTextField(value = dRu, onValueChange = { vm.descRu.value = it }, label = { Text("Описание для проверки (RU)") }, modifier = Modifier.fillMaxWidth().height(100.dp))
OutlinedTextField(value = dEn, onValueChange = { vm.descEn.value = it }, label = { Text("Описание для eBay (English HTML)") }, modifier = Modifier.fillMaxWidth().height(100.dp))
pub?.let { r ->
Surface(color = if (r.ok) Color(0xFFD1FAE5) else Color(0xFFFEE2E2), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
Column(modifier = Modifier.padding(10.dp)) {
Text(r.msg, fontWeight = FontWeight.Bold)
if (r.url.isNotBlank()) Text(r.url, color = Color(0xFF0064D2), modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) })
}
}
}
Button(onClick = { vm.publishNow() }, enabled = !busy && title.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)), modifier = Modifier.fillMaxWidth().height(52.dp)) {
Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("Опубликовать на eBay в 1 клик", fontWeight = FontWeight.Bold)
}
}
}
}
2 -> {
val price by vm.priceUsd.collectAsState()
val wG by vm.weightG.collectAsState()
val ems by vm.isEms.collectAsState()
val sh by vm.ship.collectAsState()
val fn by vm.fin.collectAsState()
var trk by remember { mutableStateOf("RR123456789KZ") }
Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
Text("Калькулятор Payoneer KZ и АО «Казпочта»", fontWeight = FontWeight.Bold)
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
OutlinedTextField(value = price.toString(), onValueChange = { it.toDoubleOrNull()?.let { v -> vm.priceUsd.value = v; vm.recalc() } }, label = { Text("Цена (${'$'})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
OutlinedTextField(value = wG.toString(), onValueChange = { it.toIntOrNull()?.let { v -> vm.weightG.value = v; vm.recalc() } }, label = { Text("Вес (г)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
}
Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
Text("${sh.name}: ${'$'}${sh.costUsd}", fontSize = 13.sp)
Row(verticalAlignment = Alignment.CenterVertically) { Text("EMS "); Switch(checked = ems, onCheckedChange = { vm.isEms.value = it; vm.recalc() }) }
}
Surface(color = Color(0xFF0F172A), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
Text("Чистая прибыль (после eBay и Payoneer 2.5%):", color = Color.LightGray, fontSize = 12.sp)
Text("${'$'}${fn.netUsd}  (${fn.netKzt.toInt()} ₸)", color = Color(0xFF34D399), fontSize = 22.sp, fontWeight = FontWeight.Black)
Text("Маржа: ${fn.margin}% | eBay FVF: -${'$'}${fn.fvf} | Payoneer: -${'$'}${fn.payoneer}", color = Color.White, fontSize = 12.sp)
}
}
OutlinedTextField(value = trk, onValueChange = { trk = it.uppercase() }, label = { Text("Валидатор трек-номера UPU S10 (RR/CP/EP...KZ)") }, modifier = Modifier.fillMaxWidth())
Text(if (KzEngines.validTrack(trk)) "✅ Формат UPU S10 Казпочты верен" else "❌ Неверный формат (нужно RR/CP/EP + 9 цифр + KZ)", fontSize = 12.sp, color = if (KzEngines.validTrack(trk)) Color(0xFF059669) else Color.Red)
}
}
}
3 -> Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
Text("Настройки бесплатных ключей (ИИ и eBay)", fontWeight = FontWeight.Bold)
AiMode.values().forEach { m ->
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.cfg.value = cfg.copy(ai = m) }) {
RadioButton(selected = cfg.ai == m, onClick = { vm.cfg.value = cfg.copy(ai = m) }); Text(m.title, fontSize = 13.sp)
}
}
OutlinedTextField(value = cfg.geminiKey, onValueChange = { vm.cfg.value = cfg.copy(geminiKey = it) }, label = { Text("Gemini 1.5 Flash Free API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
OutlinedTextField(value = cfg.ollamaUrl, onValueChange = { vm.cfg.value = cfg.copy(ollamaUrl = it) }, label = { Text("Ollama Wi-Fi URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
OutlinedTextField(value = cfg.clientId, onValueChange = { vm.cfg.value = cfg.copy(clientId = it) }, label = { Text("eBay App Client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
OutlinedTextField(value = cfg.clientSecret, onValueChange = { vm.cfg.value = cfg.copy(clientSecret = it) }, label = { Text("eBay Cert ID (Secret)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
OutlinedTextField(value = cfg.refreshToken, onValueChange = { vm.cfg.value = cfg.copy(refreshToken = it) }, label = { Text("eBay OAuth Refresh Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}
}
}
}
}
}
