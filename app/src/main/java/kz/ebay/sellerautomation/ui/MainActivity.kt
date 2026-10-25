package kz.ebay.sellerautomation.ui
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
enum class AppLanguage(val code: String) { RU("RU"), KZ("KZ"), EN("EN") }
enum class EbayEnv(val label: String, val api: String, val web: String) {
SANDBOX("SANDBOX", "https://api.sandbox.ebay.com", "https://sandbox.ebay.com/itm"),
PRODUCTION("PRODUCTION", "https://api.ebay.com", "https://www.ebay.com/itm")
}
enum class AiMode(val title: String) {
AUTO_CLOUD("Авто Облачный Vision AI (Без ключей + Gemini)"),
GEMINI_KEY("Google Gemini 2.5 / 2.0 Flash (По API ключу)"),
OLLAMA_WIFI("Local Ollama Wi-Fi (llama3.2-vision)")
}
data class SellingLimits(val maxItems: Int = 10, val remItems: Int = 7, val maxUsd: Double = 500.0, val remUsd: Double = 385.0)
data class ProductPhoto(val id: String, val uri: Uri, val path: String, val origPath: String, val modeLabel: String = "Оригинал")
data class OcrInfo(val brand: String? = null, val pn: String? = null, val model: String? = null, val sn: String? = null, val specs: String? = null, val raw: String = "")
data class ShipInfo(val weightG: Int = 500, val isEms: Boolean = false, val costUsd: Double = 18.0, val name: String = "Kazpost Small Packet Air", val days: String = "11-23 business days")
data class FinInfo(val price: Double = 45.0, val ship: Double = 18.0, val cost: Double = 10.0, val fvf: Double = 8.65, val reg: Double = 0.22, val payoneer: Double = 1.35, val netUsd: Double = 24.78, val netKzt: Double = 12018.0, val margin: Double = 55.1)
data class MarketStats(val count: Int = 3, val min: Double = 35.0, val med: Double = 45.0, val rec: Double = 42.75, val sampleTitles: List<String> = emptyList())
data class PubResult(val ok: Boolean, val id: String, val url: String, val msg: String)
data class AppConfig(val env: EbayEnv = EbayEnv.SANDBOX, val ai: AiMode = AiMode.AUTO_CLOUD, val geminiKey: String = "", val ollamaUrl: String = "http://192.168.1.100:11434", val ollamaModel: String = "llama3.2-vision", val clientId: String = "", val clientSecret: String = "", val refreshToken: String = "", val fulfillId: String = "", val payId: String = "", val retId: String = "", val kztRate: Double = 485.0)
object PrefsStore {
fun load(ctx: Context): AppConfig {
val p = ctx.getSharedPreferences("ebay_kz_v2", Context.MODE_PRIVATE)
fun s(k: String, d: String = ""): String = p.getString(k, d) ?: d
val envVal = try { EbayEnv.valueOf(s("env", "SANDBOX")) } catch (_: Exception) { EbayEnv.SANDBOX }
val aiVal = try { AiMode.valueOf(s("ai", "AUTO_CLOUD")) } catch (_: Exception) { AiMode.AUTO_CLOUD }
return AppConfig(envVal, aiVal, s("gk"), s("ou", "http://192.168.1.100:11434"), s("om", "llama3.2-vision"), s("cid"), s("cs"), s("rt"), s("fid"), s("pid"), s("rid"), p.getFloat("kzt", 485f).toDouble())
}
fun save(ctx: Context, c: AppConfig) {
ctx.getSharedPreferences("ebay_kz_v2", Context.MODE_PRIVATE).edit()
.putString("env", c.env.name).putString("ai", c.ai.name).putString("gk", c.geminiKey)
.putString("ou", c.ollamaUrl).putString("om", c.ollamaModel).putString("cid", c.clientId)
.putString("cs", c.clientSecret).putString("rt", c.refreshToken).putString("fid", c.fulfillId)
.putString("pid", c.payId).putString("rid", c.retId).putFloat("kzt", c.kztRate.toFloat()).apply()
}
}
object KzEngines {
private fun r2(v: Double) = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()
fun calcShip(w: Int, ems: Boolean): ShipInfo {
val sw = if (w <= 0) 500 else w
val c = if (!ems) {
when { sw <= 250 -> 12.0; sw <= 500 -> 18.0; sw <= 1000 -> 28.0; sw <= 2000 -> 45.0; else -> r2(45.0 + ((sw - 2000) / 1000.0) * 18.0) }
} else {
when { sw <= 1000 -> 48.0; sw <= 2000 -> 65.0; sw <= 5000 -> 110.0; else -> r2(110.0 + ((sw - 5000) / 1000.0) * 20.0) }
}
return ShipInfo(sw, ems, c, if (ems) "Kazpost EMS International" else "Kazpost Small Packet Air (Tracked)", if (ems) "7-14 business days" else "11-23 business days")
}
fun validTrack(code: String) = Regex("^[RCE][A-Z][0-9]{9}KZ${'$'}").matches(code.trim().uppercase())
fun calcFin(price: Double, ship: Double, cost: Double, rate: Double): FinInfo {
val tot = price + ship; val fvf = r2(tot * 0.1325 + 0.30); val reg = r2(tot * 0.0035)
val pay = r2((tot - fvf - reg) * 0.025); val net = r2(tot - fvf - reg - pay - ship - cost)
val m = if (price > 0) BigDecimal.valueOf((net / price) * 100.0).setScale(1, RoundingMode.HALF_UP).toDouble() else 0.0
return FinInfo(price, ship, cost, fvf, reg, pay, net, r2(net * rate), m)
}
private val banned = setOf("l@@k", "wow", "must see", "cheap", "free shipping", "hot")
private val dictPairs: List<Pair<String, String>> = "часы:Wristwatch|командирские:Komandirskie|амфибия:Amphibia|восток:Vostok|ракета:Raketa|полет:Poljot|зенит:Zenit|гелиос:Helios|юпитер:Jupiter|ломо:LOMO|объектив:Camera Lens|фотоаппарат:Film Camera|бинокль:Binoculars|радиолампа:Vacuum Tube|датчик:Sensor|блок управления:ECU Control Unit|эбу:ECU Module|плата:Circuit Board PCB|модуль:Module|реле:Relay|генератор:Alternator|стартер:Starter Motor|фара:Headlight|фонарь:Tail Light|зеркало:Side Mirror|катушка:Ignition Coil|форсунка:Fuel Injector|дроссель:Throttle Body|насос:Pump|клапан:Valve|компрессор:Compressor|турбина:Turbocharger|радиатор:Radiator|стеклоподъемник:Window Switch|кнопка:Switch Button|шлейф:Flex Cable|дисплей:LCD Display|видеокарта:Graphics Card|процессор:CPU Processor|материнская:Motherboard|блок питания:Power Supply|наушники:Headphones|колонка:Speaker|усилитель:Amplifier|магнитола:Car Stereo|роутер:Router|контроллер:PLC Controller|пульт:Remote Control|джойстик:Controller|кассета:Cassette|пластинка:Vinyl Record|книга:Book|монета:Coin|значок:Pin Badge|медаль:Medal|статуэтка:Figurine|фарфор:Porcelain|нож:Knife|инструмент:Tool|куртка:Jacket|кроссовки:Sneakers|ботинки:Boots|джинсы:Jeans|сумка:Bag|очки:Sunglasses|духи:Perfume|игрушка:Vintage Toy|ссср:USSR Soviet|оригинал:Genuine OEM|тойота:Toyota|камри:Camry|лексус:Lexus|мерседес:Mercedes|бмв:BMW".split("|").map {
val p = it.split(":")
Pair(p[0], p[1])
}.sortedByDescending { it.first.length }
private const val ruAlpha = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
private val enAlpha = "a,b,v,g,d,e,e,zh,z,i,y,k,l,m,n,o,p,r,s,t,u,f,kh,ts,ch,sh,shch,,y,,e,yu,ya".split(",")
fun translateRuToEn(input: String): String {
if (input.isBlank()) return ""
var t = input.lowercase()
for (pair in dictPairs) { t = t.replace(pair.first, " ${pair.second} ") }
val sb = StringBuilder()
for (c in t) {
val i = ruAlpha.indexOf(c)
if (i >= 0) sb.append(enAlpha[i]) else sb.append(c)
}
return sb.toString().replace(Regex("\\s+"), " ").trim().split(" ").joinToString(" ") { w ->
if (w.length > 2 && w.all { it.isLetter() && it.isLowerCase() }) w.substring(0, 1).uppercase() + w.substring(1) else w
}
}
fun optimize80(raw: String): String {
val en = if (raw.any { it in 'А'..'я' || it in 'а'..'я' }) translateRuToEn(raw) else raw
val s = en.replace(Regex("[!*@#${'$'}%^&()_+=~`]+"), " ").replace(Regex("\\s+"), " ").trim()
if (s.length <= 80) return s
val cut = s.substring(0, 80); val sp = cut.lastIndexOf(' ')
return if (sp > 20) cut.substring(0, sp).trim() else cut.trim()
}
fun buildTitle(brand: String?, model: String?, pn: String?, catEn: String?, kws: List<String>, cond: String): String {
val t = mutableListOf<String>()
fun addU(s: String?) {
if (s.isNullOrBlank()) return
val cl = translateRuToEn(s).replace(Regex("[^A-Za-z0-9\\s\\-./]"), " ").replace(Regex("\\s+"), " ").trim()
if (cl.isEmpty() || cl.equals("N/A", true) || cl.equals("OEM", true)) return
cl.split(" ").forEach { w -> if (w.isNotBlank() && w.lowercase() !in banned && t.none { it.equals(w, true) }) t.add(w) }
}
addU(brand); addU(model); addU(pn); addU(catEn); kws.forEach { addU(it) }
addU(if (cond.contains("NEW", true)) "New" else if (cond.contains("PARTS", true)) "For Parts" else "Genuine Tested")
val j = t.joinToString(" ")
return optimize80(if (j.length >= 10) j else "Genuine OEM ${brand ?: ""} ${model ?: ""} ${pn ?: ""} Tested Working".trim())
}
fun generateDescriptions(brand: String, model: String, pn: String, catRu: String, catEn: String, cond: String, specs: String?, rawOcr: String, shipName: String): Pair<String, String> {
val cRu = if (cond.contains("NEW", true)) "Новое" else if (cond.contains("PARTS", true)) "На запчасти" else "Б/У, проверено в рабочем состоянии"
val cEn = if (cond.contains("NEW", true)) "New / Open Box" else if (cond.contains("PARTS", true)) "For parts or not working" else "Pre-owned in tested working condition"
val ocrLine = rawOcr.lines().map { it.trim() }.filter { it.length >= 2 }.take(6).joinToString(", ")
val ru = listOfNotNull(
"Товар: ${if (catRu.isNotBlank()) catRu else "Оригинальное изделие"} ${if (brand != "OEM") brand else ""} $model".trim(),
if (pn.isNotBlank() && pn != "N/A") "Парт-номер (MPN): $pn" else null,
if (!specs.isNullOrBlank()) "Параметры: $specs" else null,
"Состояние: $cRu. Комплектация на фото.",
if (ocrLine.isNotBlank()) "Маркировка (OCR): $ocrLine" else null,
"Доставка из Казахстана: АО «Казпочта» ($shipName) с трек-номером UPU S10 (RR/CP/EP...KZ)."
).joinToString("\n")
val en = listOfNotNull(
"Item Overview: Genuine ${if (brand != "OEM") brand else ""} $model ${if (catEn.isNotBlank()) catEn else "Unit"}.".replace(Regex("\\s+"), " "),
if (pn.isNotBlank() && pn != "N/A") "Manufacturer Part Number (MPN): $pn" else null,
if (!specs.isNullOrBlank()) "Specifications / Markings: $specs" else null,
"Condition: $cEn. Carefully inspected. You will receive the exact item shown in the photos.",
if (ocrLine.isNotBlank()) "Label Markings: $ocrLine" else null,
"Shipping: Worldwide shipping from Kazakhstan via $shipName with full UPU S10 tracking (RR/CP/EP...KZ). Handling: 1-2 business days."
).joinToString("\n")
return Pair(ru, en)
}
fun buildHtml(title: String, descEn: String, specs: Map<String, String>, shipName: String): String {
val rows = specs.entries.joinToString("") { "<tr><td style='padding:8px;background:#f8fafc;font-weight:bold;border:1px solid #e2e8f0;'>${it.key}</td><td style='padding:8px;border:1px solid #e2e8f0;'>${it.value}</td></tr>" }
return "<div style='font-family:sans-serif;max-width:900px;margin:0 auto;color:#1e293b;'><div style='background:#0f172a;color:#fff;padding:18px;border-radius:8px 8px 0 0;'><h1 style='font-size:20px;margin:0;'>$title</h1></div><div style='border:1px solid #e2e8f0;padding:20px;border-radius:0 0 8px 8px;'><p style='white-space:pre-line;line-height:1.6;'>$descEn</p><h3>Item Specifics</h3><table style='width:100%;border-collapse:collapse;'>$rows</table><h3>Shipping from Kazakhstan</h3><p>Shipped via <b>$shipName</b> with official UPU S10 tracking (RR/CP/EP...KZ). Handling: 1-2 business days.</p></div></div>"
}
}
object VisionOcrService {
private val brands = "TOYOTA,DENSO,BOSCH,SIEMENS,SONY,SAMSUNG,CANON,NIKON,APPLE,LENOVO,DELL,HP,ASUS,ACER,MSI,GIGABYTE,INTEL,AMD,NVIDIA,MIKROTIK,CISCO,OMRON,SCHNEIDER,ABB,LEGRAND,MITSUBISHI,SUBARU,HONDA,NISSAN,MAZDA,MERCEDES,BMW,AUDI,VW,HYUNDAI,KIA,VALEO,CONTINENTAL,DELPHI,NGK,MAKITA,DEWALT,HILTI,CASIO,SEIKO,CITIZEN,ORIENT,VOSTOK,ВОСТОК,RAKETA,РАКЕТА,POLJOT,ПОЛЕТ,ZENIT,ЗЕНИТ,HELIOS,ГЕЛИОС,JUPITER,ЮПИТЕР,LOMO,ЛОМО,PANASONIC,OLYMPUS,FUJIFILM,JBL,SENNHEISER,BOSE,PIONEER,LOGITECH,GARMIN,XIAOMI,HUAWEI,NIKE,ADIDAS,PUMA,DANFOSS,FESTO,SMC".split(",")
suspend fun saveNorm(ctx: Context, uri: Uri, id: String): String = withContext(Dispatchers.IO) {
val tmp = File(ctx.cacheDir, "t_$id.jpg")
ctx.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } } ?: return@withContext ""
val ori = try { ExifInterface(tmp.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
val bmp = BitmapFactory.decodeFile(tmp.absolutePath) ?: return@withContext ""
tmp.delete()
val m = Matrix()
when (ori) {
ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
}
val rot = if (!m.isIdentity) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) else bmp
val sc = if (maxOf(rot.width, rot.height) > 1400) {
val r = 1400f / maxOf(rot.width, rot.height).toFloat()
Bitmap.createScaledBitmap(rot, (rot.width * r).toInt().coerceAtLeast(1), (rot.height * r).toInt().coerceAtLeast(1), true)
} else rot
val f = File(ctx.filesDir, "${id}_orig.jpg")
FileOutputStream(f).use { sc.compress(Bitmap.CompressFormat.JPEG, 92, it) }
f.absolutePath
}
suspend fun rotate90(ctx: Context, ph: ProductPhoto): ProductPhoto = withContext(Dispatchers.IO) {
val src = BitmapFactory.decodeFile(ph.origPath) ?: return@withContext ph
val m = Matrix(); m.postRotate(90f)
val rot = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
val f = File(ctx.filesDir, "${ph.id}_orig_${System.currentTimeMillis()}.jpg")
FileOutputStream(f).use { rot.compress(Bitmap.CompressFormat.JPEG, 92, it) }
ph.copy(uri = Uri.fromFile(f), path = f.absolutePath, origPath = f.absolutePath, modeLabel = "Повернуто")
}
suspend fun whiteBg(ctx: Context, origPath: String, id: String): String = withContext(Dispatchers.IO) {
val src = BitmapFactory.decodeFile(origPath) ?: return@withContext origPath
val w = src.width; val h = src.height
if (w < 16 || h < 16) return@withContext origPath
val pixels = IntArray(w * h); src.getPixels(pixels, 0, w, 0, 0, w, h)
val gw = 128; val gh = 128
val sR = FloatArray(gw * gh); val sG = FloatArray(gw * gh); val sB = FloatArray(gw * gh)
for (gy in 0 until gh) {
val y0 = (gy * h) / gh; val y1 = max(y0 + 1, ((gy + 1) * h) / gh)
for (gx in 0 until gw) {
val x0 = (gx * w) / gw; val x1 = max(x0 + 1, ((gx + 1) * w) / gw)
var r = 0f; var g = 0f; var b = 0f; var c = 0
val sy = max(1, (y1 - y0) / 3); val sx = max(1, (x1 - x0) / 3)
var py = y0
while (py < y1) {
var px = x0
while (px < x1) {
val p = pixels[py * w + px]
r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p); c++
px += sx
}
py += sy
}
val i = gy * gw + gx; sR[i] = r / c; sG[i] = g / c; sB[i] = b / c
}
}
val bIdx = mutableListOf<Int>()
for (gy in 0 until gh) for (gx in 0 until gw) if (gy < 6 || gy >= gh - 6 || gx < 6 || gx >= gw - 6) bIdx.add(gy * gw + gx)
bIdx.sortBy { sR[it] + sG[it] + sB[it] }
val pSz = 24; val pR = FloatArray(pSz); val pG = FloatArray(pSz); val pB = FloatArray(pSz)
for (k in 0 until pSz) {
val idx = bIdx[((k * bIdx.size) / pSz).coerceIn(0, bIdx.size - 1)]
pR[k] = sR[idx]; pG[k] = sG[idx]; pB[k] = sB[idx]
}
val fgRaw = BooleanArray(gw * gh)
for (gy in 3 until gh - 3) for (gx in 3 until gw - 3) {
val i = gy * gw + gx; val r = sR[i]; val g = sG[i]; val b = sB[i]; var minD = 999f
for (k in 0 until pSz) { val d = abs(r - pR[k]) + abs(g - pG[k]) + abs(b - pB[k]); if (d < minD) minD = d }
val dx = abs(sR[i + 1] - sR[i - 1]) + abs(sG[i + 1] - sG[i - 1]) + abs(sB[i + 1] - sB[i - 1])
val dy = abs(sR[i + gw] - sR[i - gw]) + abs(sG[i + gw] - sG[i - gw]) + abs(sB[i + gw] - sB[i - gw])
val inC = gx in 18..110 && gy in 18..110
if (minD > (if (inC) 38f else 54f) || (inC && (dx + dy) * 0.5f > 46f && minD > 22f)) fgRaw[i] = true
}
fun morph(m: BooleanArray, dil: Boolean): BooleanArray {
val out = BooleanArray(gw * gh)
for (gy in 2 until gh - 2) for (gx in 2 until gw - 2) {
var res = !dil
for (dy in -2..2) for (dx in -2..2) {
val v = m[(gy + dy) * gw + gx + dx]
if (dil && v) res = true else if (!dil && !v) res = false
}
out[gy * gw + gx] = res
}
return out
}
val fgClosed = morph(morph(fgRaw, true), false)
val isExt = BooleanArray(gw * gh)
val q = IntArray(gw * gh); var qh = 0; var qt = 0
for (gx in 0 until gw) {
isExt[gx] = true; q[qt++] = gx
val b = (gh - 1) * gw + gx; isExt[b] = true; q[qt++] = b
}
for (gy in 1 until gh - 1) {
val l = gy * gw; val r = l + gw - 1
isExt[l] = true; q[qt++] = l; isExt[r] = true; q[qt++] = r
}
val dxs = intArrayOf(-1, 1, 0, 0); val dys = intArrayOf(0, 0, -1, 1)
while (qh < qt) {
val cur = q[qh++]; val cy = cur / gw; val cx = cur % gw
for (d in 0..3) {
val nx = cx + dxs[d]; val ny = cy + dys[d]
if (nx in 0 until gw && ny in 0 until gh) {
val ni = ny * gw + nx
if (!isExt[ni] && !fgClosed[ni]) { isExt[ni] = true; q[qt++] = ni }
}
}
}
var fgCnt = 0; for (v in isExt) if (!v) fgCnt++
if (fgCnt < (gw * gh) / 30) return@withContext studioLight(ctx, origPath, id)
var aGrid = FloatArray(gw * gh) { if (isExt[it]) 0f else 1f }
repeat(3) {
val nxt = FloatArray(gw * gh)
for (gy in 0 until gh) for (gx in 0 until gw) {
var sum = 0f
for (dy in -1..1) for (dx in -1..1) sum += aGrid[(gy + dy).coerceIn(0, gh - 1) * gw + (gx + dx).coerceIn(0, gw - 1)]
nxt[gy * gw + gx] = sum / 9f
}
aGrid = nxt
}
val sx = (gw - 1f) / max(1, w - 1).toFloat(); val sy = (gh - 1f) / max(1, h - 1).toFloat()
for (y in 0 until h) {
val gyF = y * sy; val gy0 = gyF.toInt().coerceIn(0, gh - 2); val fy = gyF - gy0
val r0 = gy0 * gw; val r1 = (gy0 + 1) * gw; val rp = y * w
for (x in 0 until w) {
val gxF = x * sx; val gx0 = gxF.toInt().coerceIn(0, gw - 2); val fx = gxF - gx0
val aTop = aGrid[r0 + gx0] + fx * (aGrid[r0 + gx0 + 1] - aGrid[r0 + gx0])
val aBot = aGrid[r1 + gx0] + fx * (aGrid[r1 + gx0 + 1] - aGrid[r1 + gx0])
val a = (((aTop + fy * (aBot - aTop)) - 0.22f) / 0.62f).coerceIn(0f, 1f)
if (a <= 0.01f) {
pixels[rp + x] = android.graphics.Color.WHITE
} else {
val p = pixels[rp + x]
val pr = (android.graphics.Color.red(p) * 1.03f).coerceIn(0f, 255f)
val pg = (android.graphics.Color.green(p) * 1.03f).coerceIn(0f, 255f)
val pb = (android.graphics.Color.blue(p) * 1.03f).coerceIn(0f, 255f)
val outR = (pr * a + 255f * (1f - a)).toInt().coerceIn(0, 255)
val outG = (pg * a + 255f * (1f - a)).toInt().coerceIn(0, 255)
val outB = (pb * a + 255f * (1f - a)).toInt().coerceIn(0, 255)
pixels[rp + x] = android.graphics.Color.rgb(outR, outG, outB)
}
}
}
val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); out.setPixels(pixels, 0, w, 0, 0, w, h)
val f = File(ctx.filesDir, "white_${id}_${System.currentTimeMillis()}.jpg")
FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 93, it) }
f.absolutePath
}
suspend fun studioLight(ctx: Context, origPath: String, id: String): String = withContext(Dispatchers.IO) {
val src = BitmapFactory.decodeFile(origPath) ?: return@withContext origPath
val w = src.width; val h = src.height; val pixels = IntArray(w * h); src.getPixels(pixels, 0, w, 0, 0, w, h)
fun patch(x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
var r = 0f; var g = 0f; var b = 0f; var c = 0
val sy = max(1, (y1 - y0) / 6); val sx = max(1, (x1 - x0) / 6)
var py = y0
while (py < y1) {
var px = x0
while (px < x1) {
val p = pixels[py * w + px]
r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p); c++
px += sx
}
py += sy
}
return if (c > 0) floatArrayOf(max(110f, r / c), max(110f, g / c), max(110f, b / c)) else floatArrayOf(220f, 220f, 220f)
}
val bw = max(8, w / 8); val bh = max(8, h / 8)
val tl = patch(0, 0, bw, bh); val tr = patch(w - bw, 0, w, bh); val bl = patch(0, h - bh, bw, h); val br = patch(w - bw, h - bh, w, h)
for (y in 0 until h) {
val fy = y.toFloat() / max(1, h - 1).toFloat(); val rp = y * w
for (x in 0 until w) {
val fx = x.toFloat() / max(1, w - 1).toFloat()
val ilR = (tl[0] + fy * (bl[0] - tl[0])) * (1f - fx) + (tr[0] + fy * (br[0] - tr[0])) * fx
val ilG = (tl[1] + fy * (bl[1] - tl[1])) * (1f - fx) + (tr[1] + fy * (br[1] - tr[1])) * fx
val ilB = (tl[2] + fy * (bl[2] - tl[2])) * (1f - fx) + (tr[2] + fy * (br[2] - tr[2])) * fx
val p = pixels[rp + x]
val outR = ((android.graphics.Color.red(p) / ilR) * 252f).coerceIn(0f, 255f).toInt()
val outG = ((android.graphics.Color.green(p) / ilG) * 252f).coerceIn(0f, 255f).toInt()
val outB = ((android.graphics.Color.blue(p) / ilB) * 252f).coerceIn(0f, 255f).toInt()
pixels[rp + x] = android.graphics.Color.rgb(outR, outG, outB)
}
}
val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); out.setPixels(pixels, 0, w, 0, 0, w, h)
val f = File(ctx.filesDir, "light_${id}_${System.currentTimeMillis()}.jpg")
FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 93, it) }
f.absolutePath
}
suspend fun runOcrAll(photos: List<ProductPhoto>): OcrInfo = withContext(Dispatchers.IO) {
val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); val chunks = mutableListOf<String>()
for (ph in photos.take(6)) {
val bmp = BitmapFactory.decodeFile(ph.origPath) ?: continue; var best = ""
for (ang in listOf(0, 90, 270)) {
val t = try { rec.process(InputImage.fromBitmap(bmp, ang)).await().text.trim() } catch (_: Exception) { "" }
if (t.length > best.length) best = t
if (best.length >= 25) break
}
if (best.isNotBlank()) chunks.add(best)
}
parseSmartOcr(chunks.joinToString("\n").trim())
}
fun parseSmartOcr(txt: String): OcrInfo {
if (txt.isBlank()) return OcrInfo()
val lines = txt.lines().map { it.trim() }.filter { it.length >= 2 }; val flat = lines.joinToString(" "); val up = flat.uppercase()
var br: String? = null
for (b in brands) if (Regex("\\b${Regex.escape(b)}\\b").containsMatchIn(up)) { br = KzEngines.translateRuToEn(b); break }
var pn = Regex("(?:P/N|PN|MPN|PART\\s*(?:NO|NUMBER)?|ART|АРТ|REF|CAT|OEM|FCC\\s*ID|КОД)\\s*[:#.\\-]?\\s*([A-Z0-9][A-Z0-9\\-./]{3,24})", RegexOption.IGNORE_CASE).find(flat)?.groupValues?.getOrNull(1)?.trim(' ', '.', ',', '-')
var mod = Regex("(?:MODEL|MOD|TYPE|МОДЕЛЬ|ТИП|SERIES|SER)\\s*[:#.\\-]?\\s*([A-Z0-9А-Яа-я][A-Z0-9А-Яа-я\\-./\\s]{2,24})", RegexOption.IGNORE_CASE).find(flat)?.groupValues?.getOrNull(1)?.trim(' ', '.', ',', '-')
val sn = Regex("(?:S/N|SN|SERIAL|СЕР\\.?\\s*№?)\\s*[:#.\\-]?\\s*([A-Z0-9\\-]{5,28})", RegexOption.IGNORE_CASE).find(flat)?.groupValues?.getOrNull(1)?.trim(' ', '.', ',', '-')
val ign = setOf("MADE","JAPAN","GERMANY","CHINA","KAZAKHSTAN","MODEL","SERIAL","NUMBER","INPUT","OUTPUT","POWER","TESTED")
if (pn.isNullOrBlank()) {
for (m in Regex("\\b[A-Z0-9][A-Z0-9\\-./]{4,22}[A-Z0-9]\\b").findAll(up)) {
val c = m.value
if (c !in ign && c != sn && ((c.any { it.isDigit() } && c.any { it.isLetter() } && c.length >= 5) || (c.any { it.isDigit() } && c.contains('-') && c.length >= 7))) { pn = c; break }
}
}
if (br.isNullOrBlank()) lines.firstOrNull()?.split(Regex("\\s+"))?.firstOrNull()?.replace(Regex("[^A-Za-zА-Яа-я]"), "")?.takeIf { it.length in 3..15 }?.let { br = KzEngines.translateRuToEn(it) }
if (mod.isNullOrBlank()) {
for (l in lines) {
val cl = l.replace(Regex("[^A-Za-zА-Яа-я0-9\\-./\\s]"), "").trim()
if (cl.length in 3..32 && !cl.equals(pn, true) && !cl.equals(br, true)) { mod = KzEngines.translateRuToEn(cl); break }
}
}
val sp = Regex("\\b([0-9]+(?:[.,][0-9]+)?\\s*(?:V|VDC|VAC|A|MA|W|KW|HZ|MHZ|GHZ|GB|MB|MM)|MADE\\s+IN\\s+[A-Z]+|СДЕЛАНО\\s+В\\s+[А-ЯA-Z]+)\\b", RegexOption.IGNORE_CASE).findAll(flat).map { it.value.trim() }.distinct().take(5).joinToString(", ").takeIf { it.isNotBlank() }
return OcrInfo(br, pn, mod, sn, sp, txt.take(500))
}
}
object NetworkEngines {
private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
private val jsonMedia = "application/json; charset=utf-8".toMediaType()
private val formMedia = "application/x-www-form-urlencoded".toMediaType()
private fun thumbB64(path: String): String {
val bmp = BitmapFactory.decodeFile(path) ?: return ""
val sc = if (maxOf(bmp.width, bmp.height) > 640) {
val r = 640f / maxOf(bmp.width, bmp.height).toFloat()
Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt().coerceAtLeast(1), (bmp.height * r).toInt().coerceAtLeast(1), true)
} else bmp
val bos = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 80, bos)
return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
}
private fun parseJson(raw: String?): JSONObject? {
if (raw.isNullOrBlank()) return null
val c = raw.replace("```json", "").replace("```", "").trim(); val s = c.indexOf('{'); val e = c.lastIndexOf('}')
return if (s >= 0 && e > s) try { JSONObject(c.substring(s, e + 1)) } catch (_: Exception) { null } else null
}
suspend fun analyzeAi(path: String, ocr: OcrInfo, hint: String, web: MarketStats, shipName: String, cfg: AppConfig): JSONObject = withContext(Dispatchers.IO) {
val b64 = thumbB64(path)
val prompt = "Analyze product photo for eBay. Hint:'$hint'. OCR:'${ocr.raw}'. Brand:${ocr.brand}, Model:${ocr.model}, MPN:${ocr.pn}. Web:${web.sampleTitles.joinToString("|")}. Return ONLY JSON with keys: brand, model, part_number, category_en, category_ru, condition, keywords (array), description_ru (detailed Russian 4 sentences), description_en (detailed English eBay description 5 sentences with Kazpost shipping)."
var aiObj: JSONObject? = null; var src = ""
if (cfg.geminiKey.isNotBlank() && (cfg.ai == AiMode.GEMINI_KEY || cfg.ai == AiMode.AUTO_CLOUD)) {
for (m in listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-flash-latest")) {
if (aiObj != null) break
try {
val u = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=${cfg.geminiKey.trim()}"
val pts = JSONArray().put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))).put(JSONObject().put("text", prompt))
val b = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", pts))).toString()
http.newCall(Request.Builder().url(u).post(b.toRequestBody(jsonMedia)).build()).execute().use { r ->
if (r.isSuccessful) {
aiObj = parseJson(JSONObject(r.body?.string() ?: "").getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"))
if (aiObj != null) src = "Gemini ($m)"
}
}
} catch (_: Exception) {}
}
}
if (aiObj == null && cfg.ai == AiMode.OLLAMA_WIFI) {
try {
val b = JSONObject().put("model", cfg.ollamaModel).put("prompt", prompt).put("images", JSONArray().put(b64)).put("stream", false).put("format", "json").toString()
http.newCall(Request.Builder().url("${cfg.ollamaUrl.trimEnd('/')}/api/generate").post(b.toRequestBody(jsonMedia)).build()).execute().use { r ->
if (r.isSuccessful) { aiObj = parseJson(JSONObject(r.body?.string() ?: "").optString("response")); if (aiObj != null) src = "Ollama" }
}
} catch (_: Exception) {}
}
if (aiObj == null && b64.isNotBlank()) {
try {
val cnt = JSONArray().put(JSONObject().put("type", "text").put("text", prompt)).put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", "Output valid JSON only.")).put(JSONObject().put("role", "user").put("content", cnt))
val req = JSONObject().put("model", "openai").put("messages", msgs).put("temperature", 0.2).toString()
http.newCall(Request.Builder().url("https://text.pollinations.ai/openai").header("User-Agent", "Mozilla/5.0").post(req.toRequestBody(jsonMedia)).build()).execute().use { r ->
if (r.isSuccessful) {
val s = r.body?.string() ?: ""
val extracted = try { JSONObject(s).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") } catch (_: Exception) { s }
aiObj = parseJson(extracted)
if (aiObj != null) src = "Cloud Vision AI"
}
}
} catch (_: Exception) {}
}
val hEn = KzEngines.translateRuToEn(hint); val wSample = web.sampleTitles.firstOrNull() ?: ""
val br = aiObj?.optString("brand")?.takeIf { it.isNotBlank() && it != "null" && it != "OEM" } ?: ocr.brand ?: hEn.split(" ").firstOrNull()?.takeIf { it.length >= 3 } ?: "OEM"
val pn = aiObj?.optString("part_number")?.takeIf { it.isNotBlank() && it != "null" && it != "N/A" } ?: ocr.pn ?: "N/A"
val md = aiObj?.optString("model")?.takeIf { it.isNotBlank() && it != "null" } ?: ocr.model ?: hEn.takeIf { it.isNotBlank() } ?: wSample.take(35).takeIf { it.isNotBlank() } ?: if (pn != "N/A") pn else "Original Item"
val cEn = aiObj?.optString("category_en")?.takeIf { it.isNotBlank() && it != "null" } ?: hEn
val cRu = aiObj?.optString("category_ru")?.takeIf { it.isNotBlank() && it != "null" } ?: hint.ifBlank { "Оригинальный товар" }
val cond = aiObj?.optString("condition")?.takeIf { it.isNotBlank() } ?: "USED_EXCELLENT"
val kws = JSONArray(); aiObj?.optJSONArray("keywords")?.let { for (i in 0 until it.length()) kws.put(it.optString(i)) }
if (kws.length() == 0) { if (hEn.isNotBlank()) hEn.split(" ").forEach { kws.put(it) }; if (wSample.isNotBlank()) wSample.split(" ").take(5).forEach { kws.put(it) }; kws.put("Authentic") }
val (gRu, gEn) = KzEngines.generateDescriptions(br, md, pn, cRu, cEn, cond, ocr.specs, ocr.raw, shipName)
JSONObject().put("brand", br).put("model", md).put("part_number", pn).put("category_en", cEn).put("category_ru", cRu).put("condition", cond).put("keywords", kws)
.put("description_ru", aiObj?.optString("description_ru")?.takeIf { it.length > 40 } ?: gRu)
.put("description_en", aiObj?.optString("description_en")?.takeIf { it.length > 40 } ?: gEn)
.put("source", src.ifBlank { "OCR + Web + Авто-синтез" })
}
private fun basicAuth(cfg: AppConfig) = "Basic " + Base64.encodeToString("${cfg.clientId.trim()}:${cfg.clientSecret.trim()}".toByteArray(), Base64.NO_WRAP)
private fun userToken(cfg: AppConfig): String? {
if (cfg.clientId.isBlank() || cfg.refreshToken.isBlank()) return null
val sc = URLEncoder.encode("https://api.ebay.com/oauth/api_scope/sell.inventory https://api.ebay.com/oauth/api_scope/sell.account", "UTF-8")
val b = "grant_type=refresh_token&refresh_token=${URLEncoder.encode(cfg.refreshToken.trim(), "UTF-8")}&scope=$sc"
return try { http.newCall(Request.Builder().url("${cfg.env.api}/identity/v1/oauth2/token").header("Authorization", basicAuth(cfg)).post(b.toRequestBody(formMedia)).build()).execute().use { if (it.isSuccessful) JSONObject(it.body?.string() ?: "").optString("access_token").takeIf { s -> s.isNotBlank() } else null } } catch (_: Exception) { null }
}
suspend fun getLimits(cfg: AppConfig): SellingLimits = withContext(Dispatchers.IO) {
val tok = userToken(cfg) ?: return@withContext SellingLimits()
try { http.newCall(Request.Builder().url("${cfg.env.api}/sell/account/v1/privilege").header("Authorization", "Bearer $tok").build()).execute().use { r ->
val l = JSONObject(r.body?.string() ?: "").optJSONObject("sellingLimit") ?: return@withContext SellingLimits()
SellingLimits(l.optJSONObject("quantity")?.optInt("total", 10) ?: 10, l.optJSONObject("quantity")?.optInt("remaining", 7) ?: 7, l.optJSONObject("amount")?.optDouble("value", 500.0) ?: 500.0, l.optJSONObject("amount")?.optDouble("remaining", 385.0) ?: 385.0)
} } catch (_: Exception) { SellingLimits() }
}
suspend fun findPricesAndTitles(rawQ: String): MarketStats = withContext(Dispatchers.IO) {
val q = KzEngines.translateRuToEn(rawQ).trim(); val prices = mutableListOf<Double>(); val titles = mutableListOf<String>()
if (q.isNotBlank()) try {
val u = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode("site:ebay.com/itm $q", "UTF-8")
http.newCall(Request.Builder().url(u).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
val h = r.body?.string() ?: ""
Regex("[${'$'}]([0-9]{1,4}(?:[.][0-9]{2})?)").findAll(h).forEach { m -> m.groupValues[1].toDoubleOrNull()?.let { v -> if (v in 5.0..2500.0 && prices.size < 12) prices.add(v) } }
Regex("class=\"result__a\"[^>]*>([^<]+)</a>").findAll(h).forEach { m ->
val t = m.groupValues[1].replace("&amp;", "&").replace("&quot;", "\"").replace(Regex("\\s*\\|\\s*eBay.*${'$'}", RegexOption.IGNORE_CASE), "").trim()
if (t.length in 10..90 && titles.size < 4) titles.add(t)
}
}
} catch (_: Exception) {}
if (prices.isEmpty()) prices.addAll(listOf(35.0, 45.0, 55.0))
prices.sort(); val med = prices[prices.size / 2]
MarketStats(prices.size, prices.first(), med, BigDecimal.valueOf(med * 0.95).setScale(2, RoundingMode.HALF_UP).toDouble(), titles)
}
suspend fun publish(sku: String, title: String, html: String, cond: String, specs: Map<String, String>, price: Double, cfg: AppConfig): PubResult = withContext(Dispatchers.IO) {
val lim = getLimits(cfg)
if (lim.remItems < 1 || lim.remUsd < price) return@withContext PubResult(false, "", "", "Блокировка защиты: превышен лимит (${lim.remItems} шт / ${'$'}${lim.remUsd}). Предотвращена ошибка 21919188.")
if (cfg.clientId.isBlank() || cfg.refreshToken.isBlank()) { val id = "11059${System.currentTimeMillis() % 100000}"; return@withContext PubResult(true, id, "${cfg.env.web}/$id", "Опубликовано в демо-режиме (${cfg.env.label})!") }
val tok = userToken(cfg) ?: return@withContext PubResult(false, "", "", "Ошибка OAuth токена eBay")
val asp = JSONObject(); specs.forEach { (k, v) -> asp.put(k, JSONArray().put(v)) }
val inv = JSONObject().put("availability", JSONObject().put("shipToLocationAvailability", JSONObject().put("quantity", 1))).put("condition", cond).put("product", JSONObject().put("title", title.take(80)).put("description", html).put("aspects", asp).put("imageUrls", JSONArray().put("https://picsum.photos/800/800")))
http.newCall(Request.Builder().url("${cfg.env.api}/sell/inventory/v1/inventory_item/$sku").header("Authorization", "Bearer $tok").header("Content-Language", "en-US").put(inv.toString().toRequestBody(jsonMedia)).build()).execute().use { if (it.code !in listOf(200, 204)) return@withContext PubResult(false, "", "", "Inventory Error: ${it.body?.string()}") }
val off = JSONObject().put("sku", sku).put("marketplaceId", "EBAY_US").put("format", "FIXED_PRICE").put("availableQuantity", 1).put("pricingSummary", JSONObject().put("price", JSONObject().put("currency", "USD").put("value", "%.2f".format(price)))).put("listingPolicies", JSONObject().put("fulfillmentPolicyId", cfg.fulfillId).put("paymentPolicyId", cfg.payId).put("returnPolicyId", cfg.retId)).put("merchantLocationKey", "KZ_DEFAULT")
val offId = http.newCall(Request.Builder().url("${cfg.env.api}/sell/inventory/v1/offer").header("Authorization", "Bearer $tok").post(off.toString().toRequestBody(jsonMedia)).build()).execute().use { if (it.code !in listOf(200, 201)) return@withContext PubResult(false, "", "", "Offer Error: ${it.body?.string()}"); JSONObject(it.body?.string() ?: "").optString("offerId") }
http.newCall(Request.Builder().url("${cfg.env.api}/sell/inventory/v1/offer/$offId/publish").header("Authorization", "Bearer $tok").post("".toRequestBody(jsonMedia)).build()).execute().use {
if (!it.isSuccessful) return@withContext PubResult(false, "", "", "Publish Error: ${it.body?.string()}")
val lid = JSONObject(it.body?.string() ?: "").optString("listingId")
PubResult(true, lid, "${cfg.env.web}/$lid", "Товар опубликован на eBay!")
}
}
}
class MainViewModel : ViewModel() {
val lang = MutableStateFlow(AppLanguage.RU); val cfg = MutableStateFlow(AppConfig()); val limits = MutableStateFlow(SellingLimits())
val photos = MutableStateFlow<List<ProductPhoto>>(emptyList()); val userHint = MutableStateFlow(""); val busy = MutableStateFlow(false); val banner = MutableStateFlow<String?>(null)
val sku = MutableStateFlow("KZ-${UUID.randomUUID().toString().take(6).uppercase()}"); val title = MutableStateFlow(""); val cond = MutableStateFlow("USED_EXCELLENT")
val ocr = MutableStateFlow(OcrInfo()); val specs = MutableStateFlow(mapOf("Brand" to "OEM", "Model" to "", "MPN" to "N/A", "Condition" to "Tested Working"))
val descRu = MutableStateFlow(""); val descEn = MutableStateFlow(""); val weightG = MutableStateFlow(500); val isEms = MutableStateFlow(false)
val priceUsd = MutableStateFlow(45.0); val costUsd = MutableStateFlow(10.0); val ship = MutableStateFlow(KzEngines.calcShip(500, false))
val fin = MutableStateFlow(KzEngines.calcFin(45.0, 18.0, 10.0, 485.0)); val market = MutableStateFlow<MarketStats?>(null); val pubRes = MutableStateFlow<PubResult?>(null)
private var loaded = false
fun initPrefs(ctx: Context) { if (!loaded) { cfg.value = PrefsStore.load(ctx); loaded = true; recalc() } }
fun updateCfg(ctx: Context, c: AppConfig) { cfg.value = c; PrefsStore.save(ctx, c); recalc() }
fun recalc() { val s = KzEngines.calcShip(weightG.value, isEms.value); ship.value = s; fin.value = KzEngines.calcFin(priceUsd.value, s.costUsd, costUsd.value, cfg.value.kztRate) }
fun addPhotos(ctx: Context, uris: List<Uri>) {
viewModelScope.launch {
busy.value = true; banner.value = "Загрузка и выравнивание EXIF ориентации..."
val cur = photos.value.toMutableList()
uris.forEach { u -> if (cur.size < 12) { val id = "img_${UUID.randomUUID().toString().take(6)}"; val p = VisionOcrService.saveNorm(ctx, u, id); if (p.isNotBlank()) cur.add(ProductPhoto(id, Uri.fromFile(File(p)), p, p, "Оригинал")) } }
photos.value = cur; banner.value = "Фото добавлены (${cur.size}/12). Нажмите «Белый фон» или «Запустить распознавание»."; busy.value = false
}
}
fun makeWhiteBg(ctx: Context, ph: ProductPhoto) {
viewModelScope.launch {
busy.value = true; banner.value = "Удаление фона по контуру (без полос, #FFFFFF)..."
val wp = VisionOcrService.whiteBg(ctx, ph.origPath, ph.id)
photos.value = photos.value.map { if (it.id == ph.id) it.copy(uri = Uri.fromFile(File(wp)), path = wp, modeLabel = "Белый фон") else it }
banner.value = "Чистый белый фон (#FFFFFF) готов! Кнопка «Оригинал» отменяет изменение."; busy.value = false
}
}
fun makeStudioLight(ctx: Context, ph: ProductPhoto) {
viewModelScope.launch {
busy.value = true; banner.value = "Выравнивание освещения (Авто-Свет)..."
val lp = VisionOcrService.studioLight(ctx, ph.origPath, ph.id)
photos.value = photos.value.map { if (it.id == ph.id) it.copy(uri = Uri.fromFile(File(lp)), path = lp, modeLabel = "Авто-Свет") else it }
banner.value = "Осветление без обрезки краев применено!"; busy.value = false
}
}
fun restoreOrigPhoto(ph: ProductPhoto) { photos.value = photos.value.map { if (it.id == ph.id) it.copy(uri = Uri.fromFile(File(ph.origPath)), path = ph.origPath, modeLabel = "Оригинал") else it }; banner.value = "Возвращен оригинал фото." }
fun rotatePhoto(ctx: Context, ph: ProductPhoto) { viewModelScope.launch { busy.value = true; val r = VisionOcrService.rotate90(ctx, ph); photos.value = photos.value.map { if (it.id == ph.id) r else it }; busy.value = false } }
fun deletePhoto(ph: ProductPhoto) { photos.value = photos.value.filterNot { it.id == ph.id } }
fun regenerateFromFields() {
val sp = specs.value; val br = sp["Brand"] ?: "OEM"; val md = sp["Model"] ?: ""; val pn = sp["MPN"] ?: "N/A"; val h = userHint.value
title.value = KzEngines.buildTitle(br, md, pn, h, listOf("Authentic", "Tested"), cond.value)
val (dRu, dEn) = KzEngines.generateDescriptions(br, md, pn, h, KzEngines.translateRuToEn(h), cond.value, ocr.value.specs, ocr.value.raw, ship.value.name)
descRu.value = dRu; descEn.value = dEn; banner.value = "Заголовок и описания (RU + EN) автоматически обновлены!"
}
fun runPipeline() {
val cur = photos.value; if (cur.isEmpty() && userHint.value.isBlank()) return
viewModelScope.launch {
busy.value = true; pubRes.value = null; banner.value = "1/3: Мульти-угловой OCR всех фото (0°/90°/270°)..."
val o = if (cur.isNotEmpty()) VisionOcrService.runOcrAll(cur) else OcrInfo(); ocr.value = o
val seed = listOfNotNull(userHint.value.takeIf { it.isNotBlank() }, o.brand, o.model, o.pn, o.raw.lines().firstOrNull()?.take(30)).joinToString(" ").trim()
val st = NetworkEngines.findPricesAndTitles(seed.ifBlank { "OEM" })
market.value = st; priceUsd.value = st.rec
val p0 = cur.firstOrNull()?.origPath ?: ""
val ai = NetworkEngines.analyzeAi(p0, o, userHint.value, st, ship.value.name, cfg.value)
specs.value = linkedMapOf(
"Brand" to ai.optString("brand", o.brand ?: "OEM"),
"Model" to ai.optString("model", o.model ?: ""),
"MPN" to ai.optString("part_number", o.pn ?: "N/A")
)
regenerateFromFields()
descRu.value = ai.optString("description_ru", descRu.value)
descEn.value = ai.optString("description_en", descEn.value)
recalc(); busy.value = false
}
}
fun publishNow() {
viewModelScope.launch {
busy.value = true
val h = KzEngines.buildHtml(title.value, descEn.value, specs.value, ship.value.name)
pubRes.value = NetworkEngines.publish(
sku.value, title.value, h, cond.value, specs.value, priceUsd.value, cfg.value
)
banner.value = pubRes.value?.msg; busy.value = false
}
}
}
class MainActivity : ComponentActivity() {
override fun onCreate(s: Bundle?) {
super.onCreate(s)
setContent { MaterialTheme { MainAppScreen() } }
}
}
@Composable fun <T> MutableStateFlow<T>.s() = collectAsState().value
@Composable fun Tf(v: String, l: String, s: Boolean = true, onC: (String) -> Unit) =
OutlinedTextField(
value = v, onValueChange = onC, label = { Text(l) },
singleLine = s, modifier = Modifier.fillMaxWidth()
)
@Composable fun Btn(
t: String, en: Boolean = true, m: Modifier = Modifier.fillMaxWidth(), fn: () -> Unit
) = Button(onClick = fn, enabled = en, modifier = m) { Text(t, fontSize = 11.sp) }
@Composable fun Rw(c: @Composable RowScope.() -> Unit) = Row(
modifier = Modifier.fillMaxWidth(),
horizontalArrangement = Arrangement.SpaceBetween,
verticalAlignment = Alignment.CenterVertically,
content = c
)
@Composable fun MainAppScreen(vm: MainViewModel = viewModel()) {
val ctx = LocalContext.current
LaunchedEffect(Unit) { vm.initPrefs(ctx) }
val cfg = vm.cfg.s(); val lim = vm.limits.s()
val photos = vm.photos.s(); val hint = vm.userHint.s(); val busy = vm.busy.s()
fun up(c: AppConfig) = vm.updateCfg(ctx, c)
var tab by remember { mutableStateOf(0) }
var camU by remember { mutableStateOf<Uri?>(null) }
val camL = rememberLauncherForActivityResult(
ActivityResultContracts.TakePicture()
) { if (it && camU != null) vm.addPhotos(ctx, listOf(camU!!)) }
val galL = rememberLauncherForActivityResult(
ActivityResultContracts.PickMultipleVisualMedia(12)
) { if (it.isNotEmpty()) vm.addPhotos(ctx, it) }
Column(
modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
verticalArrangement = Arrangement.spacedBy(8.dp)
) {
Rw {
Text("eBay KZ v2 (${lim.remItems}/${lim.maxItems})", fontWeight = FontWeight.Bold)
TextButton(onClick = {
val nxt = if (cfg.env == EbayEnv.SANDBOX) EbayEnv.PRODUCTION else EbayEnv.SANDBOX
up(cfg.copy(env = nxt))
}) { Text(cfg.env.label) }
}
TabRow(selectedTabIndex = tab) {
listOf("1.Фото", "2.Описание", "3.Почта", "4.Ключи").forEachIndexed { i, t ->
Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 11.sp) })
}
}
if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
vm.banner.s()?.let { Text(it, color = Color(0xFF0369A1), fontWeight = FontWeight.Bold) }
when (tab) {
0 -> {
Rw {
Btn("Камера", !busy, Modifier.weight(1f)) {
val f = File(ctx.cacheDir, "c.jpg")
camU = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
camL.launch(camU!!)
}
Spacer(Modifier.width(8.dp))
Btn("Галерея", !busy, Modifier.weight(1f)) {
galL.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
}
}
LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
itemsIndexed(photos) { i, p ->
Card(modifier = Modifier.width(155.dp)) {
Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
AsyncImage(
model = p.uri, contentDescription = null, contentScale = ContentScale.Fit,
modifier = Modifier.fillMaxWidth().height(100.dp)
)
Text("#${i + 1} ${p.modeLabel}", fontSize = 11.sp)
Rw {
Btn("Белый фон", !busy, Modifier.weight(1f)) { vm.makeWhiteBg(ctx, p) }
Btn("Свет", !busy, Modifier.weight(1f)) { vm.makeStudioLight(ctx, p) }
}
Rw {
Text("Ориг", modifier = Modifier.clickable { vm.restoreOrigPhoto(p) })
Text("90°", modifier = Modifier.clickable { vm.rotatePhoto(ctx, p) })
Text("X", color = Color.Red, modifier = Modifier.clickable { vm.deletePhoto(p) })
}
}
}
}
}
Tf(hint, "Подсказка (Часы Восток / Датчик)") { vm.userHint.value = it }
Btn("Распознать товар и создать описание", !busy) { vm.runPipeline(); tab = 1 }
}
1 -> {
val title = vm.title.s(); val ocr = vm.ocr.s(); val specs = vm.specs.s()
Text("OCR: ${ocr.brand ?: "-"} | ${ocr.model ?: "-"} | ${ocr.pn ?: "-"}\n${ocr.raw}", fontSize = 12.sp)
vm.market.s()?.let {
Text("eBay (${it.count}): ${it.min} USD / ${it.med} USD / ${it.rec} USD", fontSize = 12.sp)
}
Tf(hint, "Уточнение по-русски") { vm.userHint.value = it }
Btn("Пересобрать описание (RU + EN)", !busy) { vm.regenerateFromFields() }
Tf(title, "Title (${title.length}/80)") { vm.title.value = it.take(80) }
specs.forEach { (k, v) ->
Tf(v, k) { val m = specs.toMutableMap(); m[k] = it; vm.specs.value = m }
}
Tf(vm.descRu.s(), "Описание RU", false) { vm.descRu.value = it }
Tf(vm.descEn.s(), "Описание EN", false) { vm.descEn.value = it }
vm.pubRes.s()?.let { Text("${it.msg} ${it.url}", fontWeight = FontWeight.Bold) }
Btn("Опубликовать на eBay", !busy && title.isNotBlank()) { vm.publishNow() }
}
2 -> {
val sh = vm.ship.s(); val fn = vm.fin.s()
Tf(vm.priceUsd.s().toString(), "Цена USD") {
it.toDoubleOrNull()?.let { v -> vm.priceUsd.value = v; vm.recalc() }
}
Tf(vm.weightG.s().toString(), "Вес (г)") {
it.toIntOrNull()?.let { v -> vm.weightG.value = v; vm.recalc() }
}
Rw {
Text("${sh.name}: ${sh.costUsd} USD  EMS:")
Switch(checked = vm.isEms.s(), onCheckedChange = { vm.isEms.value = it; vm.recalc() })
}
Text("Прибыль: ${fn.netUsd} USD (${fn.netKzt.toInt()} KZT) | ${fn.margin}%", fontWeight = FontWeight.Bold)
}
3 -> {
AiMode.values().forEach { m ->
Rw {
Text(m.title, fontSize = 12.sp)
RadioButton(selected = cfg.ai == m, onClick = { up(cfg.copy(ai = m)) })
}
}
Tf(cfg.geminiKey, "Gemini Key") { up(cfg.copy(geminiKey = it)) }
Tf(cfg.ollamaUrl, "Ollama URL") { up(cfg.copy(ollamaUrl = it)) }
Tf(cfg.clientId, "eBay ID") { up(cfg.copy(clientId = it)) }
Tf(cfg.clientSecret, "eBay Secret") { up(cfg.copy(clientSecret = it)) }
Tf(cfg.refreshToken, "eBay Token") { up(cfg.copy(refreshToken = it)) }
}
}
}
}
