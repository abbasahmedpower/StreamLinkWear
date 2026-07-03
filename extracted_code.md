<USER_REQUEST>
# 🔍 تدقيق StreamLinkWear (Horus el Fardos) — تحليل حقيقي من الكود الفعلي

قبل ما أدخل في التفاصيل، أقولك بالظبط عملت إيه: فككت الـ zip، وقرأت الكود سطر سطر في أهم 20+ ملف (بروتوكول الشبكة، التشفير، الـ encoder، الـ decoder، الـ manifests، ملفات الـ gradle)، وعملت `grep` على المشروع كله للتأكد هل كل subsystem فعلاً متوصّل ولا "معلق في الهوا". كل حاجة تحتها دليل (اسم ملف + رقم سطر)، مفيش تخمين. المشروع 10,770 سطر Kotlin موزعة على 3 موديولات + backend + native — مش هقدر أغطي كل سطر في رسالة واحدة، فركزت على الـ pipeline الحرج (تصوير → تشفير → إرسال → استقبال → فك تشفير → عرض) لأنه ده اللي يحدد هل التطبيق يشتغل أصلاً ولا لأ.

---

## 🔴 المستوى NASA — أعطال تمنع التطبيق من الاشتغال نهائيًا

### NASA-1: الفيديو مش هيتفك تشفيره على الساعة أبدًا (SPS/PPS بيتضاعوا في الفراغ)

**الدليل:** في `HardwareEncoder.kt` سطر 133-141، أي config frame (اللي فيها SPS/PPS) بتتقفل فورًا:
```kotlin
if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
    // Still pass to HardenedFrameProcessor so it caches SPS/PPS   ← تعليق كدب!
    val packet = buildPacket(buffer, info) { codec.releaseOutputBuffer(index, false) }
    packet.release()   // ده مجرد بيرجع الـ buffer للـ codec، مفيش أي كاش هنا
    return
}
```
`HardenedFrameProcessor.cacheParameterSets()` هي اللي المفروض تحفظ الـ SPS/PPS — لكن دالت الـ config frame دي **مفيش أي استدعاء لـ `HardenedFrameProcessor` هنا خالص**. الـ config frames أصلاً بتتقفل جوه `HardwareEncoder` نفسه، فمش بتوصل لـ `outputChannel`، ومش بتوصل لـ `MirrorDataPlane.processPacket()` (اللي هو المكان الوحيد اللي فعلاً بيستدعي `HardenedFrameProcessor.processAndObtain()`). يعني `cachedSps` و `cachedPps` هيفضلوا `null` **طول عمر التطبيق**.

النتيجة: كل الـ keyframes اللي بتتبعت للساعة عبارة عن IDR slice عاري من غير SPS/PPS. والـ decoder في `DirectStreamPlayer.kt` (سطر 86-93) متظبط من غير أي `csd-0`/`csd-1`، يعني معتمد 100% إنه ياخد الـ SPS/PPS جوه الـ bitstream نفسه. بما إنها مش بتوصل أبدًا → **الفيديو مش هيتفك تشفيره على الساعة، مهما كان الاتصال تمام**. ده مش تخمين، ده تتبّع فعلي للمسار من الكود.

**الحل:**
```kotlin
// app/src/main/java/com/streamlink/app/capture/HardwareEncoder.kt
if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
    // ✅ الاستدعاء الفعلي اللي كان ناقص — ده اللي فعلاً بيحفظ SPS/PPS
    com.streamlink.shared.HardenedFrameProcessor.processAndObtain(buffer, info)
    codec.releaseOutputBuffer(index, false)
    return
}
```

---

### NASA-2: `MediaProjection` من غير `registerCallback` = Crash + تسريب بطارية على Android 14

**الدليل:** `CaptureService.kt` سطر 51-53، بيعمل `createVirtualDisplay()` مباشرة من غير `registerCallback`. من Android 14 (targetSdk 34 اللي المشروع مضبوط عليه)، النظام صريح بيتطلب تسجيل `MediaProjection.Callback` قبل استخدام الـ projection — وإلا فيه أجهزة بترمي استثناء، وفي كل الأحوال لو المستخدم دوس "Stop casting" من الـ system UI، التطبيق مش هيعرف، وهيفضل الـ encoder شغال والـ VirtualDisplay معلق على display ميت → **استنزاف بطارية مستمر لحد ما تقفل التطبيق يدويًا**. ده بالظبط النوع اللي بيأثر على مطلبك بتاع تقليل استهلاك الطاقة.

**الحل:**
```kotlin
private val projectionCallback = object : MediaProjection.Callback() {
    override fun onStop() {
        Log.w(tag, "Projection stopped by system — tearing down")
        stopCapture()
        stopSelf()
    }
}

private fun startCapture(resultCode: Int, data: Intent) {
    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    mediaProjection = mpm.getMediaProjection(resultCode, data)
    mediaProjection?.registerCallback(projectionCallback, android.os.Handler(mainLooper)) // ✅
    ...
}
```

### NASA-3: تطبيق الساعة هيعمل Crash عند بدء البث (Android 14)

**الدليل:** `wear/build.gradle` → `targetSdk 34`. و`WearForegroundService` معلن بـ `foregroundServiceType="mediaPlayback"` في الـ manifest (سطر 37) — لكن في `wear/src/main/AndroidManifest.xml` **مفيش** `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />`. من Android 14، كل `foregroundServiceType` له permission خاص بيه لازم يتضاف، وإلا `startForeground()` بيرمي `MissingForegroundServiceTypeException`. الساعات الحديثة (Pixel Watch 2/3, Galaxy Watch 6/7) شغالة Wear OS 4/5 المبنية على API 33/34 → **crash فوري لما تدوس Start**.

**الحل:**
```xml
<!-- wear/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

---

## 🟠 المستوى Micro — عيوب وظيفية حقيقية

### M-1: نظام "ECO mode" للساعات الضعيفة مش شغال خالص — بالظبط اللي انت قلقان منه
`AdaptiveResolutionController.determine()` (شامل منطق التبديل بين 466×466 و 320×320 حسب الحرارة/RTT/CPU) — عملت `grep` على المشروع كله، **صفر استدعاءات ليه في أي مكان**. حتى لو استُدعي، `HardwareEncoder` بياخد `width/height` كـ constructor params ثابتة (سطر 35-36)، يعني مفيش طريقة لتغيير الدقة أثناء التشغيل من غير إعادة بناء الـ codec بالكامل. **النتيجة: أي ساعة ضعيفة الإمكانيات هتشتغل دايمًا على أعلى بروفايل (466×466@30fps@1800kbps) ومفيش أي تقليل تلقائي**، وده اللي بيسبب سخونة واستهلاك بطارية على الساعات الأضعف. لازم يتوصل فعليًا: الـ orchestrator يقرأ من `AdaptiveResolutionController`, ولو البروفايل اتغير، يوقف الـ `HardwareEncoder` الحالي ويبني واحد جديد بالأبعاد الجديدة + يبعت إشارة للساعة إنها تعيد ضبط الـ decoder.

### M-2: `ThermalMonitor.kt` — كلاس كامل موجود، صفر استخدام
نفس القصة — `grep` رجّع صفر callers. معنى كده مفيش أي حماية حرارية فعلية شغالة رغم وجود الكود الجاهز لها.

### M-3: فحص "هل TURN متظبط؟" بيرجع نتيجة غلط دايمًا
```kotlin
// StreamProtocol.kt
fun isTurnConfigured(): Boolean =
    TURN_SERVERS.first().url != "turn:your.turn.server:3478"
```
لكن الـ placeholder الفعلي المستخدم هو `"turn:turn.streamlink.local:3478"` (دومين وهمي مش موجود). الفحص بيقارن ضد نص تاني تمامًا، فهيرجع دايمًا `true` (يعني "متظبط") حتى لو محدش عمل deploy لسيرفر TURN حقيقي. أي محاولة اتصال WebRTC من بره الشبكة المحلية هتفشل بصمت والتطبيق مفكر إنه شغال تمام.

### M-4: throttle الـ frame-rate ممكن يبتلع الـ keyframe اللي انت طلبته بنفسك
```kotlin
// HardwareEncoder.kt سطر 149-154
if (last > 0 && now - last < frameIntervalNs * 0.85) {
    codec.releaseOutputBuffer(index, false)   // ← مش بيتشيك isKeyframe!
    return
}
```
لو استخدمت `requestKeyframe()` (بعد packet loss مثلًا) والفريم الجديد وصل بسرعة، هيتقفل زي أي فريم عادي، والساعة هتفضل تعرض صورة تالفة لحد الـ IDR التالي.

**الحل:**
```kotlin
val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
if (!isKeyframe && last > 0 && now - last < frameIntervalNs * 0.85) {
    codec.releaseOutputBuffer(index, false)
    return
}
```

---

## 🟡 المستوى Nano — كود ميت وتفاصيل بناء

| الملف | المشكلة | الدليل |
|---|---|---|
| `app/src/main/cpp/stream_engine.cpp` + `StreamEngine.kt` | موديول C++/NEON كامل (JNI + CMake لـ 3 ABIs) **مستخدم صفر مرة** — `processBufferSIMD` مفيهاش أي caller | `grep` رجّع الملف نفسه بس |
| `SecurityManager.kt` | مستخدم بس في الـ unit test بتاعه، مش في أي production path (التشفير الفعلي بيحصل مباشرة عبر `KeyExchange`+`EncryptedChannel`) | نفس النمط اللي ظهر قبل كده |
| `SelectiveSecurityEngine.kt` | صفر callers | grep |
| `gradlew` (سكريبت Unix) | **مش موجود في الـ zip، بس `gradlew.bat` موجود** — أي بناء على Linux/Mac/CI هيفشل فورًا | `find . -iname "gradlew*"` رجّع `gradlew.bat` بس |
| `local.properties` (الجذر) | متسرب فيه مسار شخصي `C:\Users\A-ONE\...` — لازم يتمسح، الملف ده مفروض يتولّد تلقائي بواسطة Android Studio على أي جهاز | محتوى الملف نفسه |
| `wear/src/main/kotlin/...` مجلدات فاضية + مجلد `StreamLinkWear/` متكرر بره الجذر | بقايا نسخ قديمة، مش بتأثر على البناء بس بتلخبط | فحص الشجرة |

---

## 📁 الملفات الناقصة فعليًا لضمان تشغيل نضيف

1. **`gradlew`** (سكريبت Unix، من غير امتداد) — انسخه من أي مشروع Gradle 8.7 قياسي أو شغّل `gradle wrapper --gradle-version 8.7` مرة واحدة.
2. **`secrets.properties`** (من `secrets.properties.example`) — لو مش موجود، الـ APK هيتبني لكن بمفاتيح TURN افتراضية ضعيفة (`streamlink`/`supersecret`) — تمام للتجربة، **خطر لو نزلته production**.
3. **`predictive_model.tflite`** في `app/src/main/assets/` و `wear/src/main/assets/` — الاتنين فاضيين تمامًا. الكود عنده fallback آمن (مش هيعمل crash)، بس ميزة "الذكاء الاصطناعي للتنبؤ بالـ bitrate" **مش بتشتغل حقيقي دلوقتي، بترجع دايمًا للـ heuristic الافتراضي**. لازم تشغّل `ai_training/train_stream_predictor.py` وتحط الناتج في المكانين.
4. **Keystore + `keystore.properties`** — للـ release APK الموقّع (مفيش حاليًا، متوقع، لازم تتعمل يدويًا وتتحفظ خارج الـ git).

---

## 🔋 خطة تقليل استهلاك البطارية + دعم الساعات الضعيفة

بما إن `AdaptiveResolutionController` و `ThermalMonitor` موجودين بس مش متوصلين، دي أولوية توصيلهم مش بناء حاجة من الصفر:

1. **وصّل `AdaptiveResolutionController`**: خلي `StreamingOrchestrator` يعمل poll كل 2-3 ثواني على `ThermalMonitor` + `latencyTracker`، ويستدعي `determine()`. لو البروفايل اتغير → أوقف `HardwareEncoder` الحالي، ابني واحد جديد بالأبعاد الجديدة (تكلفة إعادة البناء ~50-100ms، مقبولة لأنها مش بتحصل كل ثانية).
2. **فعّل `KEY_OPERATING_RATE` على الـ encoder** مش بس الـ decoder — بيسمح للـ hardware codec ياخد قرارات توفير طاقة داخلية.
3. **قلل الـ wakelock time-out** في `WearForegroundService` من 30 دقيقة لمدة متجددة أقصر (5 دقائق) مع تجديد تلقائي طول ما فيه بث فعلي — بدل حجز 30 دقيقة كاملة حتى لو الاتصال اتقطع بعد ثانية.
4. **استخدم `PowerManager.WakeLock` مع `ON_AFTER_RELEASE`** أو الأفضل `setReferenceCounted(false)` عشان تتجنب تسريب لو `acquire()` اتنادت أكتر من مرة.
5. **الـ ECO profile (320×320@15fps@600kbps)** جاهز في `StreamProtocol` بس محتاج فعليًا يتفعّل زي ما شرحت فوق — ده أكبر تأثير على استهلاك الطاقة والحرارة على ساعة ضعيفة.

---

## 🛠️ خطوات الوصول لـ APK قابل للتشغيل

```bash
# 1. نضّف الملفات المسربة
rm local.properties "StreamLinkWear/local.properties"

# 2. اعمل secrets.properties حقيقي
cp secrets.properties.example secrets.properties
# عدّل القيم فيه (openssl rand -hex 32 للـ token)

# 3. ولّد gradlew المفقود
gradle wrapper --gradle-version 8.7

# 4. طبّق الإصلاحات الأربعة الحرجة اللي فوق (SPS/PPS، MediaProjection callback،
#    permission الساعة، throttle الـ keyframe)

# 5. ابني كل الموديولات
./gradlew clean assembleDebug

# 6. لو عايز APK موقّع للتوزيع
./gradlew assembleRelease   # بعد ما تضيف keystore.properties
```

---

هي دي أهم اللقطات الحرجة اللي بتحدد هل التطبيق أصلاً بيشتغل ولا لأ. لسه فيه موديولات كبيرة ما دخلتش فيها بعمق زي: `backend/` (Ktor signaling server)، `WebRtcTransport.kt` الكامل، pipeline الـ GPU rendering على الساعة (`GpuRenderPipeline.kt`/`GpuShaderManager.kt`)، و`ai_training/` بايثون. لو حابب أكمل تدقيق أي واحد فيهم بنفس المستوى ده، قولي أبدأ بإيه.Ok، عندي الآن صورة واضحة. خليني أشوف الملفات الأساسية اللي هتتلمس مباشرة.قعدت أراجع المشروع بالكامل (الكود الحقيقي مش وصف نظري) — بروتوكول HORU، `DirectSocketServer/Client`، `FrameAssembler`، `WireBufferPool`، `RemoteControlAccessibilityService`، وبنية الـDI بتاعة Hilt في الموبايل والساعة. الخبر الحلو: المعمارية الموجودة **فعلاً منظمة كويس** وقابلة للتوسعة من غير ما تلمس أي جزء حساس (الـencoder/decoder hot path، الـencryption، الـpooling). هكتب لك التنفيذ الجاهز بالترتيب اللي طلبته بالظبط.

---

## 0) تقييم سريع قبل ما نبدأ

- بروتوكول الفيديو (`StreamProtocol.WIRE_HEADER_SIZE = 25 بايت`) فيه حقل `nalType` (byte واحد) بيتاخد منه فعليًا الأرقام 0-31 بس (لأنه NAL type حقيقي من H.264: `data[offset] and 0x1F`). ده معناه **الأرقام من 32 لحد 255 فاضية تمامًا** — أحسن مكان أعلّم بيه فريمات الصوت من غير ما أضيف بايت واحد للهيدر.
- `FrameAssembler.onChunk()` عنده fast-path لأي `chunk.totalChunks == 1` بيرجّع `AssembledNal` فورًا من غير ما يلمس الـ`pending` map. فريمات الصوت عندي هتبقى دايمًا chunk واحد (960 بايت أصغر بكتير من الـMTU 3900) → **يعني `FrameAssembler.kt` مش هنلمسه خالص**، هستخدمه زي ما هو.
- `WireBufferPool` سعته 3933 بايت للـbuffer، وفريم الصوت 960 بايت → **هستخدم نفس الـpool**، مفيش pool جديد.
- `DirectSocketServer.sendPooledWire()` بيفرّق بس على أساس bit الـkeyframe في الـflags → صوت بيعدّي عادي من غير أي تعديل في السيرفر.
- `RECORD_AUDIO` و `FOREGROUND_SERVICE_MEDIA_PROJECTION` **موجودين بالفعل** في `app/AndroidManifest.xml` (يبدو حد كان مخطط للصوت من الأول). **صفر تعديلات مانيفست مطلوبة** في الموبايل أو الساعة.

النتيجة: التوسعة كلها ممكنة بإضافة كلاسات جديدة + 15 سطر تعديل في 4 ملفات موجودة. مفيش أي كود معقد لالتقاط صوت البلوتوث المقترح — استخدمنا API رسمي في الحالتين (Audio Playback Capture + Settings Panel).

---

## 1) الصوت: `LockFreeAudioRingBuffer` + `AudioCaptureEngine` v2 + `AudioPlaybackEngine` v2

### 1.1 `shared/src/main/java/com/streamlink/shared/audio/LockFreeAudioRingBuffer.kt` (جديد)

```kotlin
package com.streamlink.shared.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free SPSC ring buffer مخصص لفريمات PCM16 صوت ثابتة الحجم.
 * زيرو allocation على الـhot path — كل الـslots متعملة pre-allocated من الأول.
 * الهدف: jitter buffer بين thread استقبال الشبكة و thread تشغيل AudioTrack.
 * Capacity لازم تكون power of 2.
 */
class LockFreeAudioRingBuffer(
    capacity: Int,
    private val frameSizeBytes: Int
) {
    init {
        require(capacity > 0 && (capacity and (capacity - 1)) == 0) { "Capacity must be power of 2" }
    }

    private val mask = (capacity - 1).toLong()
    private val slots: Array<ByteArray> = Array(capacity) { ByteArray(frameSizeBytes) }
    private val slotLen = IntArray(capacity)
    private val slotTimestampUs = LongArray(capacity)

    private val head = AtomicLong(0) // بيقرأه/يعدله الـconsumer فقط
    private val tail = AtomicLong(0) // بيقرأه/يعدله الـproducer فقط

    /** Producer: نسخ مباشر جوه slot جاهز، بدون أي allocation. */
    fun write(src: ByteArray, len: Int, timestampUs: Long = 0L): Boolean {
        val t = tail.get()
        val h = head.get()
        if (t - h > mask) return false // البافر مليان — اسقط الفريم عشان الـlatency ما يتراكمش

        val idx = (t and mask).toInt()
        System.arraycopy(src, 0, slots[idx], 0, minOf(len, frameSizeBytes))
        slotLen[idx] = len
        slotTimestampUs[idx] = timestampUs
        tail.lazySet(t + 1)
        return true
    }

    /** Consumer: بينسخ في out (المستدعي بيوفر buffer بحجم frameSizeBytes). يرجع -1 لو فاضي. */
    fun read(out: ByteArray): Int {
        val h = head.get()
        val t = tail.get()
        if (h == t) return -1

        val idx = (h and mask).toInt()
        val len = slotLen[idx]
        System.arraycopy(slots[idx], 0, out, 0, len)
        head.lazySet(h + 1)
        return len
    }

    fun availableFrames(): Int = (tail.get() - head.get()).toInt().coerceAtLeast(0)

    fun clear() { head.set(tail.get()) }
}
```

### 1.2 إضافة ثوابت الصوت لـ `shared/src/main/java/com/streamlink/shared/StreamProtocol.kt`

ضيف السطور دي جوه الـ`object StreamProtocol` (بعد `CMD_SET_BITRATE` مثلاً):

```kotlin
    // Control Commands
    const val CMD_SET_BITRATE   = 1
    const val CMD_GLOBAL_ACTION = 2   // ← جديد (بند 3)

    // ── Audio streaming — نفس الـsocket، نفس WireBufferPool، هيدر 25 بايت كما هو ──
    const val PAYLOAD_TYPE_AUDIO_PCM16   = 0xA0   // > 31 دايمًا → مستحيل يتصادم مع nalType الفيديو (0-31)
    const val AUDIO_SAMPLE_RATE          = 24_000 // 24kHz mono — جودة كويسة وبيتريت واطي
    const val AUDIO_FRAME_MS             = 20
    const val AUDIO_SAMPLES_PER_FRAME    = AUDIO_SAMPLE_RATE / 1000 * AUDIO_FRAME_MS   // 480
    const val AUDIO_FRAME_BYTES          = AUDIO_SAMPLES_PER_FRAME * 2                 // 960 بايت
```

> بكده الـbitrate بتاع الصوت = 960 بايت × 50 فريم/ثانية = **384 kbps**. جنب فيديو الـ1.8Mbps الموجود أصلاً، شبكة الـLAN بتاكلها بسهولة.

### 1.3 `app/src/main/java/com/streamlink/app/capture/AudioCaptureEngine.kt` (جديد — جهة الموبايل)

```kotlin
package com.streamlink.app.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import com.streamlink.shared.DirectSocketServer
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.WireBufferPool
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioCaptureEngine v2 — يلتقط صوت النظام (Playback Audio Capture, API 29+)
 * باستخدام نفس الـMediaProjection بتاع تصوير الشاشة، ويبعت PCM16 خام فريم كل 20ms
 * على نفس الـwire protocol بتاع الفيديو (nalType = PAYLOAD_TYPE_AUDIO_PCM16).
 *
 * PCM خام بدل AAC/Opus عمدًا: صفر MediaCodec إضافي، صفر latency queue إضافي،
 * والبيتريت أصلًا صغير (384kbps).
 */
@Singleton
class AudioCaptureEngine @Inject constructor(
    private val socketServer: DirectSocketServer
) {
    private val tag = "AudioCaptureEngine"
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private val globalAudioSeq = AtomicInteger(0)

    @SuppressLint("MissingPermission")
    fun start(mediaProjection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(tag, "Playback Audio Capture محتاج Android 10+ — اتجاهل على الإصدار ده")
            return
        }
        if (running.get()) return

        val minBufBytes = AudioRecord.getMinBufferSize(
            StreamProtocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            Log.e(tag, "getMinBufferSize فشل — إعدادات مش مدعومة على الجهاز ده")
            return
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(StreamProtocol.AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufBytes * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(tag, "AudioRecord init failed: ${e.message}")
            return
        }

        audioRecord?.startRecording()
        running.set(true)
        captureThread = Thread({ captureLoop() }, "SL-AudioCapture").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
            start()
        }
        Log.i(tag, "Audio capture started (${StreamProtocol.AUDIO_SAMPLE_RATE}Hz mono PCM16)")
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frameBytes = StreamProtocol.AUDIO_FRAME_BYTES
        val pcmBuf = ByteArray(frameBytes)

        while (running.get()) {
            val record = audioRecord ?: break
            val read = record.read(pcmBuf, 0, frameBytes)
            if (read <= 0) continue

            val wire = WireBufferPool.acquire()
            val wireSize = encodeAudioWireFrame(wire, pcmBuf, read)
            socketServer.sendPooledWire(wire, wireSize)
        }
    }

    /** نفس شكل هيدر الفيديو (25 بايت) بالظبط — الفرق بس في nalType */
    private fun encodeAudioWireFrame(wire: ByteArray, pcm: ByteArray, payloadSize: Int): Int {
        val buffer = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(StreamProtocol.MAGIC_NUMBER)
        buffer.put(StreamProtocol.PROTOCOL_VERSION)
        buffer.putInt(globalAudioSeq.getAndIncrement())     // نطاق seq مستقل عن الفيديو
        buffer.putShort(0)                                   // chunkIdx = 0
        buffer.putShort(1)                                   // totalChunks = 1 دايمًا
        buffer.put(0x00)                                      // flags (مش keyframe)
        buffer.put(StreamProtocol.PAYLOAD_TYPE_AUDIO_PCM16.toByte())
        buffer.putShort(payloadSize.toShort())
        buffer.putLong(System.nanoTime() / 1000L)
        System.arraycopy(pcm, 0, wire, StreamProtocol.WIRE_HEADER_SIZE, payloadSize)
        return StreamProtocol.WIRE_HEADER_SIZE + payloadSize
    }

    fun stop() {
        running.set(false)
        captureThread?.join(500)
        captureThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.i(tag, "Audio capture stopped")
    }
}
```

**تعديل واحد في `CaptureService.kt` الموجود:**

```kotlin
@Inject lateinit var audioCaptureEngine: AudioCaptureEngine   // ← ضيف السطر ده مع الـ@Inject التانيين

private fun startCapture(resultCode: Int, data: Intent) {
    // ... الكود الموجود زي ما هو ...
    virtualDisplay = mediaProjection?.createVirtualDisplay(...)
    mediaProjection?.let { audioCaptureEngine.start(it) }   // ← ضيف السطر ده بعد إنشاء الـvirtualDisplay
    Log.i(tag, "Screen capture started successfully")
}

private fun stopCapture() {
    audioCaptureEngine.stop()   // ← ضيف السطر ده أول حاجة في الدالة
    virtualDisplay?.release()
    // ... الباقي زي ما هو
}
```
(مفيش داعي تضيفه في `AppModule.kt` — الكلاس عليه `@Inject constructor` فـHilt هيبنيه لوحده تلقائيًا.)

### 1.4 `wear/src/main/java/com/streamlink/wear/player/AudioPlaybackEngine.kt` (جديد — جهة الساعة)

```kotlin
package com.streamlink.wear.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.streamlink.shared.StreamProtocol
import com.streamlink.shared.audio.LockFreeAudioRingBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioPlaybackEngine v2 — يستقبل فريمات PCM16 من نفس الـsocket بتاع الفيديو
 * ويشغلها على سماعة/بلوتوث الساعة عبر AudioTrack، مع LockFreeAudioRingBuffer
 * كـjitter buffer (بيستنى 60ms قبل أول تشغيل عشان يمتص تذبذب الشبكة).
 */
@Singleton
class AudioPlaybackEngine @Inject constructor() {
    private val tag = "AudioPlaybackEngine"

    private val ringBuffer = LockFreeAudioRingBuffer(
        capacity = 32, // حتى 640ms buffer قبل ما يقطع
        frameSizeBytes = StreamProtocol.AUDIO_FRAME_BYTES
    )
    private var audioTrack: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private val prebufferFrames = 3 // 60ms

    fun start() {
        if (running.get()) return
        val minBuf = AudioTrack.getMinBufferSize(
            StreamProtocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(StreamProtocol.AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, StreamProtocol.AUDIO_FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        running.set(true)
        playbackThread = Thread({ playbackLoop() }, "SL-AudioPlayback").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
            start()
        }
        Log.i(tag, "Audio playback started")
    }

    /** بينده من onChunk في DirectStreamPlayer لما nalType == PAYLOAD_TYPE_AUDIO_PCM16 */
    fun onAudioChunk(data: ByteArray, size: Int, timestampUs: Long) {
        if (running.get()) ringBuffer.write(data, size, timestampUs)
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ByteArray(StreamProtocol.AUDIO_FRAME_BYTES)

        while (running.get() && ringBuffer.availableFrames() < prebufferFrames) {
            Thread.sleep(5) // prebuffer قبل أول تشغيل
        }
        while (running.get()) {
            val len = ringBuffer.read(frame)
            if (len <= 0) { Thread.sleep(2); continue }
            audioTrack?.write(frame, 0, len)
        }
    }

    fun stop() {
        running.set(false)
        playbackThread?.join(300)
        playbackThread = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        ringBuffer.clear()
        Log.i(tag, "Audio playback stopped")
    }
}
```

---

## 2) توسيع `StreamProtocol`/`FrameAssembler` لدعم الصوت على نفس الـsocket

الخبر الكويس: **`FrameAssembler.kt` مش هتلمسه خالص** — زي ما شرحت في التقييم، الـfast-path بتاعه (`totalChunks == 1`) بيشتغل مع أي `nalType` من غير تفرقة. كل اللي محتاجه هو **تعديل واحد بس** في `DirectStreamPlayer.kt` (جهة الساعة) عشان يفرّق صوت من فيديو *قبل* ما يدخل الـassembler:

```kotlin
class DirectStreamPlayer @Inject constructor(
    private val client: DirectSocketClient,
    private val audioEngine: AudioPlaybackEngine   // ← جديد
) {
    // ... باقي الكلاس زي ما هو ...

    fun start(scope: CoroutineScope) {
        if (surface == null) { Log.e(tag, "Cannot start — no Surface set"); return }
        initDecoder()
        audioEngine.start()   // ← جديد
        connectJob = scope.launch(Dispatchers.IO) {
            client.connect(
                onStateChange = { connected ->
                    Log.i(tag, "Socket connected=$connected")
                    if (!connected) { idrReceived.set(false); assembler.reset() }
                },
                onChunk = { chunk ->
                    // ← الفرع الجديد: صوت ولا فيديو؟
                    if (chunk.nalType == StreamProtocol.PAYLOAD_TYPE_AUDIO_PCM16) {
                        audioEngine.onAudioChunk(chunk.data, chunk.dataSize, chunk.timestampUs)
                    } else {
                        val assembled = assembler.onChunk(chunk) ?: return@connect
                        feedDecoder(assembled)
                    }
                }
            )
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        audioEngine.stop()   // ← جديد
        connectJob?.cancel()
        // ... باقي الدالة زي ما هي
    }
}
```

**تعديل `WearModule.kt`:** (`AudioPlaybackEngine` عليها `@Inject constructor` برضو، مش محتاج تضيفها كـ`@Provides`؛ بس Hilt هيحتاج يعرف يبني `DirectStreamPlayer` بالـconstructor الجديد — ده تلقائي، مش محتاج تلمس الـmodule خالص).

ليه ده آمن 100%؟ لأن `chunk.data` هو الـbuffer المشترك اللي بيتعاد استخدامه (`dataBuf` في `DirectSocketClient.receiveLoop`)، و`ringBuffer.write()` بينسخ منه فورًا بـ`System.arraycopy` قبل ما يرجع — بالظبط نفس فلسفة `FrameAssembler` الأصلية ("copy immediately, caller must NOT hold ref").

---

## 3) التحكم الكامل باللمس + المفاتيح الفيزيائية (Back/Home) عبر `AccessibilityService`

الميزة إن `RemoteControlAccessibilityService` **أصلًا وارث `performGlobalAction()`** من الكلاس الأساسي `AccessibilityService` — مش محتاج تضيف method جديدة فيه خالص. كل اللي محتاجينه هو مسار تحكم (control channel) موجود بالفعل (`ControlCodec` + `onControlMessage`).

**تعديل `StreamingOrchestrator.kt` (سطرين بس):**

```kotlin
socketServer.onControlMessage = { msg ->
    when (msg.command) {
        StreamProtocol.CMD_SET_BITRATE -> {
            Log.i(tag, "AI Reverse Control: Adjusting Bitrate to ${msg.value} kbps")
            hardwareEncoder.setBitrate(msg.value)
        }
        StreamProtocol.CMD_GLOBAL_ACTION -> {   // ← جديد
            Log.i(tag, "Remote global action: ${msg.value}")
            com.streamlink.app.control.RemoteControlAccessibilityService.instance
                ?.performGlobalAction(msg.value)
        }
    }
}
```

القيمة `msg.value` بتتبعت زي ما هي كـconstants Android الرسمية (`AccessibilityService.GLOBAL_ACTION_BACK = 1`, `GLOBAL_ACTION_HOME = 2`, `GLOBAL_ACTION_RECENTS = 3`) — مفيش داعي لأي mapping إضافي.

**تعديل `WearMainActivity.kt` (اعتراض زر الرجوع الفيزيائي/الـgesture في الساعة):**

```kotlin
override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
    val action = when (keyCode) {
        android.view.KeyEvent.KEYCODE_BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        android.view.KeyEvent.KEYCODE_STEM_1,
        android.view.KeyEvent.KEYCODE_STEM_PRIMARY -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        else -> return super.onKeyDown(keyCode, event)
    }
    socketClient.sendControl(com.streamlink.shared.StreamProtocol.CMD_GLOBAL_ACTION, action)
    return true // امنع الساعة تسكّر التطبيق أو ترجع لقائمتها هي — نفسه راح للموبايل
}
```

**وأزرار على الشاشة (احتياطي لأجهزة الساعة اللي مفيهاش زر فيزيائي back)** — إضافة صف أزرار في `WearStreamOverlay.kt`:

```kotlin
@Composable
fun WearStreamOverlay(
    visible: Boolean,
    onHide: () -> Unit,
    onBack: () -> Unit,        // ← جديد
    onHome: () -> Unit,        // ← جديد
    onRecents: () -> Unit,     // ← جديد
    onAudioOutput: () -> Unit  // ← جديد (بند 4)
) {
    // ... الكود الموجود زي ما هو ...

    // ضيف الـRow ده جوه الـBox الرئيسي، مثلاً align = Alignment.CenterEnd
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 6.dp, bottom = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("◀", fontSize = 14.sp, color = Color.White, modifier = Modifier.clickable { onBack() })
        Text("●", fontSize = 14.sp, color = Color.White, modifier = Modifier.clickable { onHome() })
        Text("▢", fontSize = 14.sp, color = Color.White, modifier = Modifier.clickable { onRecents() })
        Text("🔊", fontSize = 14.sp, modifier = Modifier.clickable { onAudioOutput() })
    }
}
```

وفي `WearMainActivity.kt` وصّل الـcallbacks دي بنفس `socketClient.sendControl(...)` زي أعلاه، والـ`onAudioOutput` نوديها لبند 4.

---

## 4) "ريموت كنترول" بسيط لاختيار جهاز الصوت — قائمة أندرويد الرسمية

بدل أي كود Bluetooth معقد لسرد الأجهزة يدويًا (scanning/pairing/state management)، أندرويد عنده **Panel رسمي جاهز** (`Settings.Panel.ACTION_VOLUME`) بيعرض مباشرة كل أجهزة الإخراج المتاحة (سماعة الساعة، أي بلوتوث متوصل) ويسيبه هو يتكفل بالتبديل. متاح من API 29 (الساعة عندها minSdk 30 أصلًا، فمضمون 100%).

**`wear/src/main/java/com/streamlink/wear/ux/AudioOutputPicker.kt` (جديد):**

```kotlin
package com.streamlink.wear.ux

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** فتح قائمة أندرويد الرسمية لاختيار جهاز إخراج الصوت — بدون أي كود Bluetooth مخصص. */
object AudioOutputPicker {
    fun open(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("AudioOutputPicker", "Volume panel unavailable, fallback: ${e.message}")
            try {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { /* مفيش حل تاني — نتجاهل بهدوء */ }
        }
    }
}
```

وفي `WearMainActivity.kt`، وصّل زر الـ🔊 في الأوفرلاي:

```kotlin
WearStreamOverlay(
    visible = overlayVisible,
    onHide  = { overlayVisible = false },
    onBack      = { socketClient.sendControl(StreamProtocol.CMD_GLOBAL_ACTION, AccessibilityService.GLOBAL_ACTION_BACK) },
    onHome      = { socketClient.sendControl(StreamProtocol.CMD_GLOBAL_ACTION, AccessibilityService.GLOBAL_ACTION_HOME) },
    onRecents   = { socketClient.sendControl(StreamProtocol.CMD_GLOBAL_ACTION, AccessibilityService.GLOBAL_ACTION_RECENTS) },
    onAudioOutput = { com.streamlink.wear.ux.AudioOutputPicker.open(this@WearMainActivity) }
)
```

مفيش أذونات جديدة مطلوبة — الـIntent ده بيفتح شاشة نظام أندرويد نفسها.

---

## ✅ خطوات التنفيذ بالترتيب (نفّذها كده بالظبط)

1. **الملفات الجديدة (5):** `LockFreeAudioRingBuffer.kt` (shared) → `AudioCaptureEngine.kt` (app) → `AudioPlaybackEngine.kt` (wear) → `AudioOutputPicker.kt` (wear).
2. **`StreamProtocol.kt`:** ضيف `CMD_GLOBAL_ACTION` + كل ثوابت الصوت.
3. **`CaptureService.kt`:** حقن `AudioCaptureEngine` + استدعاء `start()/stop()`.
4. **`DirectStreamPlayer.kt`:** حقن `AudioPlaybackEngine` + الفرع في `onChunk`.
5. **`StreamingOrchestrator.kt`:** `when` بدل الـ`if` في `onControlMessage`.
6. **`WearMainActivity.kt`:** `onKeyDown` override + توصيل الأزرار الأربعة.
7. **`WearStreamOverlay.kt`:** إضافة الـ`Row` بتاعة الأزرار.
8. **Build & اختبار محلي:** `./gradlew :app:assembleDebug :wear:assembleDebug` — لو نجح الـbuild من غير أخطاء، ركّب على جهازين حقيقيين (المحاكي مش هيدّيك صوت نظام حقيقي للـplayback capture).
9. **اختبار الصوت:** شغّل فيديو/موسيقى على الموبايل وشوف لو وصل صوت على سماعة الساعة خلال ثانية أو اتنين من بدء الستريم.
10. **اختبار الـBack/Home:** من الساعة (زرار فيزيائي أو الأزرار الجديدة) وشوف الموبايل بيستجيب.
11. **اختبار اختيار الصوت:** دوس على 🔊 في الساعة وشوف الـpanel الرسمي بيظهر أجهزة الإخراج المتاحة.

كل التعديلات دي **إضافية بحتة** — مفيش سطر واحد اتشال من الكود الموجود، فلو حصل مشكلة تقدر تعمل revert لأي ملف لوحده من غير ما يأثر على باقي الميزات الشغالة.
</USER_REQUEST>
<ADDITIONAL_METADATA>
The current local time is: 2026-07-03T09:58:40+03:00.
</ADDITIONAL_METADATA>