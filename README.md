# FOXConnect

> وضعیت: **فاز ۴ آلفا ۱۱ تشخیصی؛ updater خودکار منتشرشده و در انتظار آزمون دستگاه**
> آلفا ۹ در آزمون دستگاه شکست خورد. آلفا ۱۰ route/protect و شرط‌های ضد Connected کاذب را
> اصلاح کرد و CI را پاس کرد، اما data path آن هنوز روی همان دستگاه/شبکه پذیرش فیزیکی نشده است.
> آلفا ۱۱ بدون تغییر engine، اعلان خودکار Releaseهای GitHub و جریان یک‌دکمه‌ای
> دانلود/اعتبارسنجی/بازکردن نصب‌کننده را اضافه می‌کند. هر دو CI الزامی آن سبز و APK تشخیصی
> منتشر شده است؛ این نسخه تا آزمون واقعی updater به‌علاوهٔ browser/app/DNS/upload/download
> و چند failover، production نیست.

FOXConnect یک کلاینت VPN اندروید بدون روت، تبلیغات و telemetry است. رابط فارسی
به‌صورت پیش‌فرض و RTL است و ترجمهٔ انگلیسی LTR نیز دارد. شناسهٔ موقت release
`com.foxconnect.app` و شناسهٔ debug برابر `com.foxconnect.app.debug` است.

## امکانات فعلی

### تونل فاز ۱

- Android 8.0+ (`minSdk 26`)، `targetSdk 35`، `compileSdk 37`
- Kotlin 2.4.10 با AGP Built-in Kotlin، Compose و Material 3
- هستهٔ رسمی sing-box/libbox `v1.14.0` برای `arm64-v8a` و `armeabi-v7a`
- Foreground `VpnService` جداشده در فرایند `:vpn`، Android TUN، اعلان دائمی، lifecycle غیرچسبنده و boot restore فقط با سیاست صریح
- parser VLESS برای TCP، HTTP، WS، QUIC، gRPC، HTTPUpgrade، XHTTP و mKCP؛ هستهٔ
  1.14 پنج transport استاندارد V2Ray را اجرا می‌کند، TCP را بدون wrapper می‌فرستد و
  XHTTP/mKCP را با پیام صریح unsupported نگه می‌دارد
- VMess AEAD و Trojan با TLS/Reality و transportهای اصلی V2Ray
- Shadowsocks با cipherهای AEAD/2022، پراکسی HTTP/HTTPS CONNECT و SOCKS5
- Hysteria 1/2، TUIC v5 و anyTLS با TLS معتبر و UDP/QUIC در هسته
- WireGuard استاندارد چندpeer با endpoint جدید sing-box 1.14، DNS/MTU/keepalive/reserved
- TLS معتبر، Reality، uTLS fingerprint، ALPN و flow؛ بدون trust-all
- نمایش `Connected` فقط بعد از start هسته، TUN، probe واقعی DNS+TLS+HTTPS، مشاهدهٔ
  protected upstream و RX/TX دوطرفه از traffic manager خود libbox
- RX/TX و سرعت از Status API بومی libbox؛ `TrafficStats` سطح UID فقط fallback، و IP/کشور خروجی best-effort

### مدیریت و import فاز ۲

- مخزن چندپروفایلی رمز‌شده با AES-GCM و کلید غیرقابل‌خروج Android Keystore
- انتخاب، ویرایش ساختاریافته، حذف، تکثیر و favorite پروفایل‌ها
- clipboard و Android Share/Deep Link
- متن ساده، Base64 استاندارد/URL-safe و GZIP تو‌در‌تو با محدودیت حجم
- فایل و ZIP چندورودی با حفاظت در برابر archive bomb و path traversal
- اسکن زندهٔ QR داخل خود برنامه با CameraX و ZXing متن‌باز؛ فریم یا secret
  به اپ دوربین یا سرویس شبکه تحویل نمی‌شود
- فرم دستی VLESS شامل host/port/UUID، TLS/Reality، SNI، fingerprint، ALPN، flow
  و transportهای اصلی
- subscription فقط با HTTPS و TLS معتبر، redirect فقط به HTTPS، سقف ۲ MiB،
  ETag، refresh دستی/خودکار ۲۴ ساعته و حذف اتمیک پروفایل‌های stale
- امکان غیرفعال‌کردن refresh خودکار برای هر subscription
- export/restore قابل‌انتقال با AES-256-GCM، عبارت عبور PBKDF2-HMAC-SHA256
  و جایگزینی اتمیک vault فقط پس از احراز اصالت کامل backup
- deep link برای همهٔ schemeهای اختصاصی فعال و import تمام پروتکل‌ها از مسیرهای
  مشترک clipboard/share/file/ZIP/QR/subscription
- import فایل INI استاندارد WireGuard؛ URI غیراستاندارد `wg://` عمداً پذیرفته نمی‌شود
- cipherهای قدیمی Shadowsocks، VMess legacy، pluginهای پشتیبانی‌نشده، optionهای
  مبهم/ناشناخته در parserهای جدید و هرگونه TLS bypass رد می‌شوند
- ورودی‌های مخرب/بزرگ fail-closed هستند و raw config در log یا notification نوشته نمی‌شود

### سلامت، Subscription و Failover فاز ۴ آلفا ۹ تشخیصی

- نمونهٔ واقعی کاربر در 2026-09-06 با کلاینت تولیدی بررسی شد: HTTP 200، Base64
  معتبر، ۱۷ لینک VLESS، import کامل ۱۷/۱۷ و sync معتبر ۱۷ پروفایل
- درخواست شبکهٔ ساب `identity` است تا اختلاف transparent-gzip میان Android و CDN حذف شود
- اگر Failed با Kill Switch یک TUN مسدودکننده باقی گذاشته باشد، refresh دستی و ping
  ابتدا آن را صریح قطع می‌کنند و تا Disconnected منتظر می‌مانند
- import ساب Base64/Base64URL ساده، چندلایه، JSON-wrapped و متن URI escaped
- ساب ناموفق نیز ذخیره و قابل مشاهده/refresh است؛ دکمهٔ refresh همه در بالای فهرست وجود دارد
- پینگ هم‌زمان همهٔ endpointهای TCP با نتیجهٔ واقعی handshake؛ پروتکل‌های فقط UDP/QUIC
  صریحاً بدون مقدار می‌مانند و این پینگ به‌عنوان تأیید credential یا تونل معرفی نمی‌شود
- مرز JNI بازتابی حذف شده و تمام ۲۷ callback پلتفرم و ۷ callback سرور فرمان با API دقیق AAR پیاده شده‌اند
- هستهٔ libbox در فرایند `:vpn` است؛ heartbeat خصوصی وضعیت را به UI می‌رساند و مرگ فرایند را بدون ادعای اتصال تشخیص می‌دهد
- session تأییدشده با authorization marker از بسته‌شدن UI مستقل است؛ `START_STICKY` فقط برای intent تهیِ مجاز، config موجود و مجوز معتبر VPN فعال می‌شود و سقف سه restart در پنج دقیقه مانع crash loop است
- intent ناشناخته/بدون authorization همچنان fail-closed است؛ disconnect صریح، لغو مجوز VPN و Android Force Stop بازیابی نمی‌شوند
- gate واقعی CLI پین‌شده همهٔ JSONها را بررسی می‌کند؛ `default_domain_resolver` و DNS bootstrap فیزیکی چرخهٔ اتصال اولیه را شکسته‌اند
- پس از شکست اتصال انتخاب‌شده حداکثر چهار candidate رتبه‌بندی‌شده در هر دور آزموده می‌شود؛ برای session قبلاً تأییدشده، دورهای بازیابی با تأخیر محدود تا اتصال یا قطع صریح ادامه دارند
- setup، نسخه، checkConfig، ساخت/start سرور فرمان، پایش شبکه، start سرویس و post-start مرز تشخیصی مستقل دارند
- ترتیب start با SFA هم‌راستا است: OOM draft و command server پیش از monitor/service؛ lookup مالک اتصال API 29+ نیز پیاده شده است
- مالکیت command server پیش از start ثبت می‌شود و TUN، route/exclude، DNS، package rule و descriptorها چرخهٔ عمر مشخص دارند
- شبکهٔ پیش‌فرض فقط از transport غیر-VPN انتخاب می‌شود؛ callback متناسب نسخهٔ Android، fallback امن OEM و تأخیر LinkProperties پوشش داده شده‌اند
- invariant مسیر داده `tun.auto_route=true` همراه `route.auto_detect_interface=true` است تا libbox برای هر سوکت خروجی `protect()` را فراخوانی و حلقهٔ TUN را قطع کند
- health check چند ارائه‌دهندهٔ مستقل strict-TLS را هم‌زمان می‌سنجد، هر پاسخ HTTPS معتبر را می‌پذیرد و با نخستین موفقیت probeهای باقی‌مانده را لغو می‌کند
- شکست data path به physical interface، bootstrap DNS، socket routing، secure DNS، TLS، HTTPS و route تفکیک می‌شود؛ فقط counter/code امن و بدون مقصد ذخیره می‌شود
- خطاهای permission، TUN، protect، config و هشت مرحلهٔ start بدون متن خام بومی به Failed و event code مجزا تبدیل می‌شوند
- نگهداری رمز‌شدهٔ حداکثر ۳۲ candidate با selected profile در اولویت
- watchdog با strict-HTTPS واقعی و بودجهٔ بدترین‌حالت ۹ ثانیه پس از تأیید شکست همهٔ providerها، پیش از زمان reconnect
- انتخاب fallback بر اساس تازه‌ترین latency تأییدشدهٔ تونل و سپس ping دسترسی endpoint، با cooldown قابل تنظیم ۳۰/۶۰/۱۲۰ ثانیه
- metric پینگ TCP endpoint از latency HTTPS عبوری از تونل جداست و هیچ‌کدام به‌جای دیگری نمایش داده نمی‌شود
- افت پایدار با چهار نمونه، EWMA، حداقل ۳۰ ثانیه اتصال، بهبود معنادار ۲۵۰ ms/۳۵٪ و cooldown سه‌دقیقه‌ای سوییچ می‌شود؛ آستانهٔ ضعیف پیش‌فرض ۱۵۰۰ ms و قابل تنظیم است
- بازگشت دوره‌ای به پروفایل ترجیحی فقط با خاموش‌کردن سوییچ کیفیت و انتخاب کاربر فعال است
- Kill Switch داخلی پیش‌فرض فعال با TUN مسدودکننده هنگام تعویض و فاصلهٔ بازیابی؛ فقط پس از probe واقعی وضعیت Connected منتشر می‌شود
- مقدار latency ناشناخته ساخته نمی‌شود؛ سوییچ بدون فاصلهٔ مطلق تضمین نمی‌شود
- صفحهٔ تنظیمات، گزارش رویداد کد-محور بدون endpoint/credential، اعلان live و QS tile

## Build

پیش‌نیازهای اپ: JDK 17 و Android SDK Platform 37. برای بازتولید هستهٔ بومی:
Go 1.26+، Android NDK r28، Git و دسترسی شبکه لازم است.

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"

./gradlew --no-daemon --max-workers=1 \
  :core:parser:test \
  :core:storage:testDebugUnitTest \
  :core:engine:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

ساخت debug دو APK جدا تولید می‌کند:

```text
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

برای ساخت هستهٔ pin‌شده:

```bash
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/28.0.13004108"
./scripts/build-libbox.sh
./scripts/verify-libbox.sh
```

اسکریپت commit رسمی
`0b8995879f29a9b98ee027bc17b75e101445b238` را enforce می‌کند. AAR و checksum
در `core/engine/libs/` قرار می‌گیرند و خود باینری در Git/آرشیو منبع نگهداری
نمی‌شود. به‌دلیل مرز JNI کاملاً typed، build بدون AAR پین‌شده عمداً متوقف می‌شود.

## روش تست فاز ۴ آلفا ۷ تشخیصی

1. APK متناسب با ABI دستگاه را نصب کنید.
2. از «کانفیگ‌ها» یک ساب Base64 وارد کنید؛ باید رکورد ساب و کانفیگ‌ها دیده شوند.
   سپس «به‌روزرسانی همهٔ ساب‌ها» و «پینگ همه» را آزمایش کنید.
3. برنامه را force-stop و دوباره باز کنید؛ پروفایل‌ها و انتخاب باید از vault
   رمز‌شده بازیابی شوند.
4. یک کانفیگ واقعی انتخاب و مجوز notification/VPN را تأیید کنید. هنگام اتصال، UI
   باید باز و پاسخ‌گو بماند؛ حتی اگر هستهٔ بومی متوقف شود Activity نباید بسته شود.
5. اگر اتصال شکست خورد، باید یک Failed پایدار دیده شود. حداقل ۱۵ ثانیه صبر کنید:
   اعلان/فرایند نباید خودکار دوباره Connecting شود مگر خودتان اتصال را بزنید.
6. VLESS/VMess/Trojan، Shadowsocks، Hysteria 1/2، TUIC، anyTLS، WireGuard، HTTP
   و SOCKS5 را با سرورهای واقعی جداگانه آزمایش کنید.
7. روی همان دستگاه/شبکهٔ گزارش آلفا ۹، پس از Connected مرورگر و یک برنامهٔ دیگر،
   DNS، دانلود و آپلود واقعی را بررسی کنید؛ RX/TX و IP خروجی نیز باید با واقعیت حرکت کنند.
8. نام/protocol نشان‌داده‌شده در home را با candidate واقعاً در حال آزمون و سپس profile
   تأییدشده تطبیق دهید؛ preference اولیه نباید بعد از failover باقی بماند.
9. با حداقل دو profile واقعی چند بار خرابی اجباری بسازید و زمان failover، cooldown،
   anti-flap، حالت Switching، تداوم حفاظت و نبود نشت در فاصلهٔ تعویض را اندازه‌گیری کنید.
10. یک backup رمز‌شده بسازید و restore صحیح/عبارت اشتباه/فایل دست‌کاری‌شده را تست کنید؛
   سپس refresh subscription، process death، reboot، لغو مجوز، قطع شبکه، Settings و QS tile را بیازمایید.

## به‌روزرسانی امن از GitHub

مخزن رسمی ثابت برنامه `https://github.com/hojjatrad/FOXConnect` است. بررسی دستی همیشه
در Settings در دسترس است. از آلفا ۱۱ بررسی و اعلان دوره‌ای به‌صورت پیش‌فرض فعال است:
نخستین کار واجد شرایط پس از ۱۵ دقیقه و سپس هر ۲۴ ساعت با WorkManager و فقط هنگام وجود
شبکه اجرا می‌شود. کاربر می‌تواند آن را خاموش کند. build تشخیصی pre-releaseهای debug را
به‌صورت پیش‌فرض می‌بیند؛ کانال production همچنان stable است مگر کاربر خلاف آن را انتخاب کند.

بررسی پس‌زمینه فقط metadata عمومی GitHub را با HTTPS دریافت و در صورت وجود versionCode
جدید notification همراه دکمهٔ «مشاهده و نصب» نشان می‌دهد؛ APK هرگز در پس‌زمینه دانلود
یا نصب نمی‌شود. پس از لمس دکمهٔ کاربر، APK دانلود می‌شود و updater مخزن/URL رسمی، محدودیت
اندازه، SHA-256 همراه و digest واقعی، versionCode جدیدتر، ABI تک‌معماری، application ID و
گواهی امضای یکسان را fail-closed کنترل می‌کند. فقط بعد از عبور همهٔ gateها نصب‌کنندهٔ
Android باز می‌شود و نصب نهایی همیشه به تأیید خود کاربر در سیستم نیاز دارد.

قرارداد asset برای نسخهٔ production:

```text
FOXConnect-v<versionCode>-arm64-v8a.apk
FOXConnect-v<versionCode>-arm64-v8a.apk.sha256
FOXConnect-v<versionCode>-armeabi-v7a.apk
FOXConnect-v<versionCode>-armeabi-v7a.apk.sha256
SHA256SUMS
```

کلید Release در Git نگهداری نمی‌شود. workflow فقط از GitHub Secrets با نام‌های
`FOXCONNECT_KEYSTORE_BASE64`، `FOXCONNECT_SIGNING_KEY_ALIAS`،
`FOXCONNECT_SIGNING_KEY_PASSWORD`، `FOXCONNECT_SIGNING_STORE_PASSWORD` و
`FOXCONNECT_SIGNING_CERT_SHA256` استفاده می‌کند و انتشار را با `GITHUB_TOKEN` موقت
و محدود همان اجرا انجام می‌دهد. PAT در APK، سورس یا workflow وجود ندارد. روش ساخت کلید،
Secrets و مهاجرت یک‌باره در [docs/RELEASE.md](docs/RELEASE.md) مستند شده است.

## حریم خصوصی

FOXConnect analytics، تبلیغات یا telemetry ندارد. داده فقط به این مقصدها می‌رود:

1. gateway و subscription HTTPS که خود کاربر وارد کرده است؛
2. ارائه‌دهندهٔ DNS امن انتخاب‌شده و چند endpoint عمومی مستقل با TLS معتبر برای
   health check؛ همچنین سرویس best-effort نمایش IP/کد کشور خروجی؛
3. API عمومی و assetهای Release مخزن ثابت GitHub، فقط هنگام بررسی یا دانلودی که کاربر فعال کرده است.

TLS ناامن، trust-all یا downgrade به HTTP وجود ندارد. profile vault و active
engine config هر دو جداگانه با AES-GCM/Android Keystore رمز می‌شوند. backup
قابل‌انتقال فقط با اقدام کاربر ساخته می‌شود، با AES-256-GCM و کلید مشتق‌شده از
عبارت عبور محافظت می‌شود و هیچ plaintext موقتی روی دیسک نوشته نمی‌شود. cloud
backup/device transfer سیستم برای همهٔ داده‌های اپ غیرفعال است.

## محدودیت‌های فعلی

- آلفا ۹ روی دستگاه با Connected کاذب و نبود کامل ترافیک شکست خورد؛ آلفا ۱۰ CI را پاس کرده اما تا آزمون همان دستگاه تأیید فیزیکی نشده است.
- APK تشخیصی ARM64: [آلفا ۱۱](https://github.com/hojjatrad/FOXConnect/releases/tag/diagnostic-v0.5.0-alpha11)؛ [دانلود مستقیم](https://github.com/hojjatrad/FOXConnect/releases/download/diagnostic-v0.5.0-alpha11/FOXConnect-v16-debug-arm64-v8a.apk)، SHA-256: `83a154c8c3fdfafc4b6cdbaaa9838679ff96d93feb4d1786722cd6c0dd95bfec`. آلفاهای ۹ و ۱۰ فقط سابقهٔ مسیر تشخیصی‌اند و data path هنوز پذیرش فیزیکی نشده است.
- build تشخیصی debug-signed است، نه release-signed؛ مهاجرت یک‌باره به `com.foxconnect.app` با backup رمز‌شده، نصب جدا و restore لازم است.
- فرم ساختاریافتهٔ دستی فعلاً فقط برای VLESS است؛ بقیه از لینک یا فایل WireGuard import می‌شوند.
- پذیرفته‌شدن JSON نمونه توسط CLI پین‌شده جای تست libbox بومی Android و سرور واقعی را نمی‌گیرد.
- refresh زمان‌بندی‌شده و backup/restore روی JVM تست شده‌اند، اما اجرای واقعی
  WorkManager، SAF و Keystore هنوز به تست دستگاه نیاز دارد.
- watchdog، failover و TUN محافظ داخلی روی JVM/compile بررسی شده‌اند، اما زمان زیر
  ۱۰ ثانیه و جلوگیری از نشت فقط پس از آزمون دستگاه قابل تأیید است.
- محافظ داخلی جای lockdown سیستم را نمی‌گیرد؛ کاربر باید «Block connections without
  VPN» اندروید را برای حفاظت در برابر force-stop فعال کند.
- فرم‌های دستی غیر VLESS، split tunnel و UI پیشرفتهٔ DNS/rules هنوز باقی مانده‌اند؛ آلفا ۱۰ تا تکمیل CI و تست دستگاه diagnostic است.

## License

کد FOXConnect تحت **AGPL-3.0** است. sing-box/libbox تحت GPL-3.0 و تابع
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) است.

---

## English

FOXConnect is a no-root, ad-free, telemetry-free Android VPN client. Alpha 9 failed its
physical test: the app showed `Connected`, while browser/app traffic, download, and
upload did not work. Endpoint TCP pings were successful, which confirms only endpoint
reachability—not credentials, proxy handshake, routed DNS, TLS, or tunneled Internet.
The profile shown on Home also remained stale after failover. Alpha 9 is not accepted.

Alpha 10 repairs the routing invariant by emitting `tun.auto_route=true` together with
`route.auto_detect_interface=true`; libbox can therefore call the typed Android platform
control and `VpnService.protect(fd)` for upstream sockets. Ordinary health and identity
probes remain unprotected and unbound, so they must traverse the VPN rather than bypass
it. `Connected` now additionally requires observed TUN establishment, a physical uplink,
a protected upstream socket, and bidirectional native libbox traffic after a real
strict-TLS HTTPS response. Native libbox status is the primary RX/TX source.

Home uses the service's runtime profile/protocol during Connecting, Switching, and
Connected. Healthy-state checks rotate providers to reduce overhead; a primary failure
is confirmed by the other independent providers before recovery begins. Ranked
continuous recovery, cooldowns, quality hysteresis, Kill Switch behavior, and lifecycle
recovery remain in place.

Alpha 11 enables token-free GitHub update notices by default. WorkManager performs the
first eligible metadata-only check after 15 minutes and then every 24 hours on a connected
network. Diagnostic builds include debug pre-releases by default; production remains on
the stable channel. Background work never downloads or installs APKs. A notification
button opens the verified update flow; one user action downloads the APK, validates its
repository URL, size, SHA-256, package, newer version, ABI, and signing certificate, then
opens Android's package installer for final user approval. Users can disable periodic
checks or pre-releases in Settings.

CI now downloads the official sing-box 1.14.0 Linux checker with a pinned SHA-256 and
revision, and the generated-config schema test fails instead of skipping when the exact
checker is unavailable. Production release publishing still uses only GitHub Secrets and
the workflow's ephemeral `GITHUB_TOKEN`; no PAT is embedded.

Alpha 11 passed both mandatory GitHub CI runs—including the exact native checker, 101 JVM
test methods, lint, and both diagnostic splits—and is available as an
[ARM64 diagnostic pre-release](https://github.com/hojjatrad/FOXConnect/releases/tag/diagnostic-v0.5.0-alpha11)
with [direct APK download](https://github.com/hojjatrad/FOXConnect/releases/download/diagnostic-v0.5.0-alpha11/FOXConnect-v16-debug-arm64-v8a.apk).
It remains **diagnostic, not production**, until the updater and inherited alpha10 data path
are tested on-device: browser/app traffic, DNS, upload, download, displayed active profile,
several forced failovers, no-leak behavior, and sustained operation. See [HANDOFF.md](HANDOFF.md).
