# FOXConnect — handoff

آخرین به‌روزرسانی: 2026-09-06

## وضعیت فعال

نسخهٔ فعال: **`0.4.6-phase4-alpha7-diagnostic`**، `versionCode=12`.
این build فقط تشخیصی است. تست‌ها و schema gate سبز هستند، اما data path آلفا ۷ هنوز
روی دستگاه تأیید نشده و نباید production-ready معرفی شود.

گزارش فیزیکی آلفا ۶: libbox startup، command server و Android TUN موفق شدند؛ نتیجه
به Failed پایدار «تونل شروع شد، اما عبور امن ترافیک اینترنت تأیید نشد» رسید. این
گزارش blocker را از native startup به مسیر بسته‌ها محدود کرد. audit سپس mismatch
قطعی `auto_route=true` و `auto_detect_interface=false` را یافت که مسیر فراخوانی
`VpnService.protect()` برای سوکت upstream را غیرفعال می‌کرد و امکان loop داخل TUN
را می‌داد. آلفا ۷ این invariant را اصلاح و هر لایهٔ data path را جدا تشخیص می‌دهد.

## یافته‌های قطعی

1. lifecycle قدیمی `START_STICKY` بود و null intent را connect می‌دانست؛ Android می‌توانست
   پس از مرگ process دوباره سرویس را راه بیندازد.
2. libbox و UI در یک process بودند؛ abort بومی UI را هم می‌بست.
3. اتصال اولیه پس از failure بین همهٔ candidateهای subscription گردش می‌کرد و می‌توانست
   مدت زیادی در پس‌زمینه Connecting/Switching بماند.
4. auto-connect به‌صورت پیش‌فرض روشن و `MY_PACKAGE_REPLACED` نیز trigger اتصال بود؛ نصب
   update می‌توانست بدون فرمان تازهٔ کاربر اتصال را آغاز کند.
5. gate واقعی با باینری دقیق sing-box 1.14.0 نشان داد JSON قبلی به‌علت نبود
   `route.default_domain_resolver` رد می‌شود. این یک علت قطعی برای برقرار نشدن اتصال است.
6. DNS امن از مسیر proxy استفاده می‌کرد، در حالی که hostname خود proxy هنوز resolve نشده
   بود؛ چرخهٔ bootstrap بالقوه وجود داشت.
7. sing-box/libbox 1.14 از XHTTP و mKCP پشتیبانی نمی‌کند؛ تولید transport با typeهای
   `xhttp`/`kcp` JSON نامعتبر می‌ساخت.
8. state/event files بین UI و VPN قفل process-wide کامل نداشتند و race ممکن بود observer
   یا diagnostic update را از بین ببرد.
9. آلفا ۵ setup/version/config/network/command/service را در خطای کلی ادغام می‌کرد،
   ترتیب command/network با SFA یکسان نبود، `needFindProcess` را رد می‌کرد و callback
   شبکه تفاوت API/دیررسیدن LinkProperties را کامل پوشش نمی‌داد.
10. آلفا ۶ TUN را با `auto_route=true` می‌ساخت، اما route-level
    `auto_detect_interface=false` بود. بنابراین platform socket control فعال نمی‌شد و
    سوکت پراکسی می‌توانست به‌جای شبکهٔ فیزیکی دوباره توسط همان TUN گرفته شود.

## اصلاح‌های آلفا ۵

- `FoxVpnService` و libbox در process مستقل `:vpn`؛ UI هیچ call بومی اجرا نمی‌کند.
- همهٔ مسیرها `START_NOT_STICKY`؛ null/unknown action قطع و هر authorization قبلی لغو می‌شود.
- هر connect نیازمند marker خصوصی تازه از UI یا boot policy صریح است.
- auto-connect پیش‌فرض خاموش است و app update هرگز trigger اتصال نیست.
- اتصال اولیه دقیقاً یک بار فقط profile انتخاب‌شده را امتحان می‌کند؛ failure اولیه terminal است.
- فقط تونل قبلاً Verified می‌تواند حداکثر یک fallback در هر رخداد health/core-stop بزند.
- crash handler اصلی بدون ذخیرهٔ stack/raw message، authorization را لغو و قطع اضطراری
  سرویس VPN را درخواست می‌کند؛ فرایند بسته نباید تلاش را ادامه دهد.
- heartbeat خصوصی یک‌ثانیه‌ای، timeout هفت‌ثانیه‌ای و کد رویداد امن مرگ process را ثبت می‌کند.
- `TypedLibboxCore` تمام ۲۷ callback پلتفرم و ۷ callback فرمان را با API دقیق AAR اجرا می‌کند.
- command server پیش از start مالک‌گذاری و service/server/TUN/network monitor قطعی بسته می‌شوند.
- TUN آدرس، MTU، DNS، route/exclude و package rules واقعی native را مصرف می‌کند.
- default network فقط `NOT_VPN` است؛ resolver و protected socket به شبکهٔ فیزیکی متصل‌اند.
- `bootstrap-dns` و `route.default_domain_resolver` اضافه شده‌اند؛ DNS امن پس از bootstrap
  با detour صریح `proxy` عبور می‌کند.
- XHTTP/mKCP در vault باقی می‌مانند، اما پیش از service start با پیام localized unsupported
  متوقف می‌شوند و هرگز JSON نامعتبر به JNI نمی‌رود.
- AppCompat locale فقط در process UI اجرا می‌شود؛ service context فارسی/انگلیسی مستقل دارد.
- نسخه و versionCode داخل Settings نمایش داده می‌شوند.

## اصلاح‌های آلفا ۶

- startup به هشت مرز typed تقسیم شده است: setup، version، config check، command
  create، command start، network monitor، service start و post-start.
- فقط code ثابت هر stage به state، notification و event log می‌رود؛ cause بومی، stack
  trace و متن احتمالی دارای endpoint/credential نمایش یا persist نمی‌شود.
- `Libbox.setLocale` و setup pathها پیش از version check اجرا می‌شوند؛ OOM draft و
  command server پیش از monitor و native service، مطابق ترتیب SFA، شروع می‌شوند.
- رد unconditional برای `needFindProcess` حذف شد. Android 10+ از
  `getConnectionOwnerUid` و نسخه‌های قدیمی از procfs داخلی libbox استفاده می‌کنند.
- network monitor روی Android 12+ best-matching callback، روی Android 9–11 درخواست
  صریح non-VPN و روی Android 8 callback شنونده دارد؛ fallback OEM و LinkProperties
  دیرهنگام نیز fail-closed و بدون retry loop پوشش داده شده است.
- HTTP و QUIC استاندارد به transportهای V2Ray اضافه شدند؛ HTTP/WS/QUIC/gRPC/
  HTTPUpgrade با checker دقیق 1.14 پوشش دارند. TCP خام wrapper ندارد. XHTTP/mKCP
  همچنان preserve ولی unsupported و QUIC دارای encryption افزوده صریحاً رد می‌شود.
- health verification با نخستین strict-TLS probe موفق برمی‌گردد و probeهای کند باقی‌مانده
  را لغو می‌کند؛ این تغییر زمان اعلام اتصال سالم را کم می‌کند و TLS را تضعیف نمی‌کند.
- stage classifier و تمام transportهای قابل‌اجرا regression دارند.

## اصلاح‌های آلفا ۷

- `route.auto_detect_interface=true` همراه `tun.auto_route=true` تولید می‌شود. این
  باعث فراخوانی `autoDetectInterfaceControl(fd)` و `VpnService.protect(fd)` برای سوکت
  upstream می‌شود؛ app UID از VPN مستثنا نشده و ترافیک عادی همچنان داخل تونل است.
- snapshot تشخیصی data path فقط چهار مقدار دسته‌ای حافظه‌ای دارد: interface آماده،
  تعداد protected socket، تعداد درخواست bootstrap DNS و تعداد موفقیت آن. هیچ مقصد،
  host، IP، نام profile یا credential وارد state/log نمی‌شود.
- شکست verification به physical network، bootstrap DNS، socket routing، secure DNS،
  TLS، HTTPS و route تفکیک و در پیام و Connection events محلی نمایش داده می‌شود.
- هر status معتبر HTTP پس از TLS سخت‌گیرانه به‌عنوان اثبات عبور ترافیک پذیرفته می‌شود؛
  وابستگی غلط به 200/204 منطقه‌ای حذف شده و redirect دنبال نمی‌شود.
- timeout اولین verification به ۸ ثانیه، failover verification به ۶ ثانیه و watchdog
  به ۳ ثانیه رسیده است. نخستین موفقیت همچنان بلافاصله بازمی‌گردد و بودجهٔ watchdog
  با دو شکست روی ۹ ثانیه محدود است.
- regression صریح مانع بازگشت ترکیب معیوب auto-route/auto-detect می‌شود.

## gate قطعی config/native

باینری استفاده‌شده برای schema gate:

- sing-box `1.14.0`
- revision `0b8995879f29a9b98ee027bc17b75e101445b238`
- Go `1.26.7`

تمام خانواده‌های پشتیبانی‌شده، VLESS Reality و transportهای HTTP/WS/QUIC/gRPC/
HTTPUpgrade بعد از اصلاح resolver با `sing-box check` پاس شدند. تست منفی ثابت می‌کند
XHTTP/mKCP پیش از تولید JSON رد می‌شوند. قبل از اصلاح آلفا ۵، همین gate با خطای
missing default domain resolver شکست می‌خورد.

مسیر subscription محفوظ است: fixture ساختگی GZIP → Base64 → ۱۷ VLESS بدون issue/duplicate
import می‌شود و هر ۱۷ profile در repository باقی می‌مانند. هیچ دادهٔ واقعی subscription
در source/test/log/doc وجود ندارد.

## APK آلفا ۷

- مسیر: `/home/user/FOXConnect-artifacts/FOXConnect-alpha7-diagnostic-arm64.apk`
- نسخه: `0.4.6-phase4-alpha7-diagnostic (12)`
- package: `com.foxconnect.app.debug`
- ABI: فقط `arm64-v8a`
- اندازه: `46,684,910` bytes
- SHA-256: `93dd2b7895a13b0262015262032ac0b9300d8251ebaba513ed7bd9190513d3e4`
- امضا: debug، APK Signature Scheme v2
- libbox LOAD alignment: `0x4000` (16 KiB)

metadata همراه: `/home/user/FOXConnect-artifacts/SHA256SUMS` و `VERIFICATION.txt`.
archive منبع در `/home/user/FOXConnect-project.zip` با checksum companion قرار می‌گیرد؛
AAR و build outputs داخل archive نیستند.

## اعتبارسنجی سبز

- ۶۹ تست parser/storage/engine بدون failure یا skip
- parser tests، شامل GZIP/Base64/17 VLESS
- storage tests، شامل persistence هر ۱۷ profile
- engine tests، stage-classifier regressions و lifecycle/retry policy tests
- schema gate واقعی برای همهٔ خانواده‌های پشتیبانی‌شده، VLESS Reality و پنج V2Ray transport
- `:app:compileDebugKotlin`
- `:app:lintDebug`: صفر error، ۱۲ warning و ۲ hint غیرمسدودکننده
- `:app:assembleDebug`
- manifest/APK: VPN service واقعاً `:vpn`، non-exported و فقط `BOOT_COMPLETED`
- ARM64-only، signature v2، ZIP/zipalign 16 KiB و native LOAD align

## تست بعدی دستگاه

1. آلفا ۷ با code 12 را نصب کنید. نسخه در Settings خود برنامه نمایش داده می‌شود.
2. auto-connect باید پیش‌فرض خاموش باشد؛ نصب update نباید خودکار Connecting شود.
3. همان profile پشتیبانی‌شده‌ای را که در آلفا ۶ به verification failure رسید انتخاب و Connect را فقط یک بار بزنید.
4. اگر Connected شد، بلافاصله دو سایت HTTPS، یک دامنهٔ تازه برای آزمون DNS و RX/TX را بررسی کنید؛ سپس Disconnect و اتصال دوباره را تست کنید.
5. اگر failure رخ داد، یکی از دسته‌های physical network/bootstrap DNS/socket routing/secure DNS/TLS/HTTPS/route باید در متن و Connection events دیده شود؛ نباید بین ۱۷ profile گردش کند.
6. UI باید باز بماند و پس از failure یا stop نباید اتصال خودکار تکرار شود.
7. فقط پس از تأیید مرور و DNS، یک failover واقعی را با دو profile آزمایش کنید.
8. اگر باز هم failure بود، نسخهٔ نمایش‌داده‌شده، متن Failed و دو رویداد آخر Logs کافی است؛ logcat یا دادهٔ profile لازم نیست.

## build محلی کم‌حافظه

AAR رسمی pin‌شده باید در `core/engine/libs/libbox.aar` قرار گیرد و checksum آن با
`core/engine/libs/libbox.aar.sha256` تطبیق داده شود.

```bash
export JAVA_HOME=/home/user/.cache/android-build/jdk17
export ANDROID_SDK_ROOT=/home/user/.cache/android-build/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export GRADLE_USER_HOME=/home/user/.cache/gradle
export SING_BOX_CHECK=/home/user/.cache/sing-box-1.14.0/sing-box

./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx640m -XX:MaxMetaspaceSize=256m -Dkotlin.daemon.enabled=false' \
  :core:parser:test :core:storage:testDebugUnitTest :core:engine:testDebugUnitTest \
  :app:compileDebugKotlin

./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx512m -XX:MaxMetaspaceSize=384m -Dkotlin.daemon.enabled=false' \
  :app:lintDebug

./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx640m -XX:MaxMetaspaceSize=256m -Dkotlin.daemon.enabled=false' \
  :app:assembleDebug
```

## قواعد ثابت

- پیش از هر build جدید، APKها و metadata قدیمی، build outputها، cacheها و فایل‌های موقت پاک شوند؛ فقط آخرین APK، checksum و ZIP منبع نگه داشته شود.
- سورس نهایی در repository و APK/checksum در GitHub Releases نگهداری شوند تا workspace انباشته نشود.
- `build/`، cache، `libbox.aar` محلی، keystore، رمز، PAT و هر secret دیگر هرگز commit، archive یا release نشوند.
- مخزن اعلام‌شده `https://github.com/hojjatrad/FOXConnect` در 2026-09-07 از صفحه و API عمومی GitHub پاسخ 404 داشت؛ push/updater تا ایجاد یا Public‌شدن آن قابل نهایی‌سازی نیست.
- نام `FOXConnect`، شناسه release `com.foxconnect.app` و مجوز AGPL-3.0.
- فارسی پیش‌فرض RTL و انگلیسی LTR؛ همهٔ متن UI localized.
- بدون root، تبلیغات، telemetry، TLS bypass، دادهٔ ساختگی یا ادعای Connected کاذب.
- هیچ token، UUID، host، Reality key، URI، raw payload یا credential واقعی در
  source، fixture، log، docs، notification یا پاسخ وارد نشود.
- GitHub updater تا دریافت `OWNER/REPO` واقعی باز است.
- آلفا ۷ تا تأیید اتصال، DNS/traffic، توقف و failover روی دستگاه فقط diagnostic است.
