# FOXConnect

> وضعیت: **فاز ۴ آلفا ۷ تشخیصی، آمادهٔ آزمون data path روی ARM64**
> آلفا ۶ روی دستگاه libbox و Android TUN را با موفقیت شروع کرد، اما ترافیک امن برنگشت.
> audit مسیر داده نشان داد `auto_route` فعال و `auto_detect_interface` خاموش بود؛ در
> نتیجه سوکت پراکسی به `VpnService.protect()` نمی‌رسید و می‌توانست دوباره داخل TUN
> حلقه شود. آلفا ۷ این invariant را اصلاح و شکست شبکه/DNS/socket/TLS/HTTPS را جدا
> گزارش می‌کند. تا تأیید مرور و DNS واقعی روی دستگاه production نیست.

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
- نمایش `Connected` فقط بعد از start هسته، TUN و probe واقعی DNS+HTTPS
- RX/TX واقعی سطح UID و IP/کشور خروجی best-effort

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

### سلامت، Subscription و Failover فاز ۴ آلفا ۷ تشخیصی

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
- `START_NOT_STICKY`، authorization marker و رد intent تهی/ناشناخته مانع reconnect پس از crash، stop و تلاش ناموفق می‌شوند
- gate واقعی CLI پین‌شده همهٔ JSONها را بررسی می‌کند؛ `default_domain_resolver` و DNS bootstrap فیزیکی چرخهٔ اتصال اولیه را شکسته‌اند
- اتصال اولیه فقط پروفایل انتخاب‌شده را یک بار امتحان می‌کند؛ failover فقط پس از اتصال تأییدشده و حداکثر یک fallback در هر رخداد است
- setup، نسخه، checkConfig، ساخت/start سرور فرمان، پایش شبکه، start سرویس و post-start مرز تشخیصی مستقل دارند
- ترتیب start با SFA هم‌راستا است: OOM draft و command server پیش از monitor/service؛ lookup مالک اتصال API 29+ نیز پیاده شده است
- مالکیت command server پیش از start ثبت می‌شود و TUN، route/exclude، DNS، package rule و descriptorها چرخهٔ عمر مشخص دارند
- شبکهٔ پیش‌فرض فقط از transport غیر-VPN انتخاب می‌شود؛ callback متناسب نسخهٔ Android، fallback امن OEM و تأخیر LinkProperties پوشش داده شده‌اند
- invariant مسیر داده `tun.auto_route=true` همراه `route.auto_detect_interface=true` است تا libbox برای هر سوکت خروجی `protect()` را فراخوانی و حلقهٔ TUN را قطع کند
- health check چند ارائه‌دهندهٔ مستقل strict-TLS را هم‌زمان می‌سنجد، هر پاسخ HTTPS معتبر را می‌پذیرد و با نخستین موفقیت probeهای باقی‌مانده را لغو می‌کند
- شکست data path به physical interface، bootstrap DNS، socket routing، secure DNS، TLS، HTTPS و route تفکیک می‌شود؛ فقط counter/code امن و بدون مقصد ذخیره می‌شود
- خطاهای permission، TUN، protect، config و هشت مرحلهٔ start بدون متن خام بومی به Failed و event code مجزا تبدیل می‌شوند
- نگهداری رمز‌شدهٔ حداکثر ۳۲ candidate با selected profile در اولویت
- watchdog با strict-HTTPS probe واقعی، دو شکست متوالی و بودجهٔ تشخیص محدود ۹ ثانیه
- یک fallback محدود پس از خرابی تونل تأییدشده، cooldown قابل تنظیم ۳۰/۶۰/۱۲۰ ثانیه و جلوگیری از حلقهٔ retry
- بازگشت دوره‌ای به پروفایل ترجیحی فقط با انتخاب کاربر و به‌صورت پیش‌فرض خاموش
- Kill Switch داخلی پیش‌فرض فعال با TUN مسدودکننده هنگام تعویض یا شکست همهٔ profileها
- ثبت latency واقعی probe و مرتب‌سازی فقط اندازه‌گیری‌های موجود؛ مقدار ناشناخته ساخته نمی‌شود
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
7. فقط پس از عبور strict-TLS از حداقل یک probe مستقل باید وضعیت سبز «متصل» دیده شود.
8. یک backup رمز‌شده بسازید، کانفیگ‌ها را تغییر دهید و با عبارت عبور صحیح restore
   کنید؛ عبارت اشتباه یا فایل دست‌کاری‌شده نباید vault فعلی را تغییر دهد.
9. با حداقل دو profile واقعی، خرابی سرور فعال، انتقال زیر ۱۰ ثانیه، cooldown،
   حالت Switching و عدم نشت در فاصلهٔ تعویض را اندازه‌گیری کنید.
10. refresh خودکار subscription، process death، reboot، لغو مجوز، قطع شبکه،
   تنظیمات، QS tile و ویرایش/حذف پروفایل انتخاب‌شده را تست کنید.

## به‌روزرسانی امن از GitHub

مخزن رسمی ثابت برنامه `https://github.com/hojjatrad/FOXConnect` است. بررسی دستی از
Settings در دسترس است؛ بررسی دوره‌ای ۲۴ ساعته پیش‌فرض خاموش است و فقط با انتخاب کاربر
فعال می‌شود. کانال پایدار پیش‌فرض است و پیش‌انتشارها نیز اختیاری‌اند.

بررسی پس‌زمینه فقط metadata عمومی GitHub را با HTTPS دریافت و در صورت وجود نسخهٔ
جدید notification نشان می‌دهد؛ APK هرگز در پس‌زمینه دانلود یا نصب نمی‌شود. دانلود فقط
پس از لمس دکمهٔ کاربر انجام می‌شود. پیش از تحویل به نصب‌کنندهٔ Android، updater این
موارد را fail-closed کنترل می‌کند: مخزن/URL رسمی، محدودیت اندازه، versionCode جدیدتر،
ABI تک‌معماری سازگار، فایل SHA-256 همراه، hash واقعی، application ID و گواهی امضای
یکسان با برنامهٔ نصب‌شده. نصب نهایی همیشه به تأیید سیستم Android نیاز دارد.

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

- اتصال واقعی، DNS و عبور ترافیک آلفا ۷ روی دستگاه تأیید شده است؛ آلفا ۸ باید جداگانه روی همان دستگاه regression شود.
- APK فعلی debug-signed است، نه release-signed؛ مهاجرت یک‌باره به `com.foxconnect.app` با backup رمز‌شده، نصب جدا و restore لازم است.
- فرم ساختاریافتهٔ دستی فعلاً فقط برای VLESS است؛ بقیه از لینک یا فایل WireGuard import می‌شوند.
- پذیرفته‌شدن JSON نمونه توسط CLI پین‌شده جای تست libbox بومی Android و سرور واقعی را نمی‌گیرد.
- refresh زمان‌بندی‌شده و backup/restore روی JVM تست شده‌اند، اما اجرای واقعی
  WorkManager، SAF و Keystore هنوز به تست دستگاه نیاز دارد.
- watchdog، failover و TUN محافظ داخلی روی JVM/compile بررسی شده‌اند، اما زمان زیر
  ۱۰ ثانیه و جلوگیری از نشت فقط پس از آزمون دستگاه قابل تأیید است.
- محافظ داخلی جای lockdown سیستم را نمی‌گیرد؛ کاربر باید «Block connections without
  VPN» اندروید را برای حفاظت در برابر force-stop فعال کند.
- فرم‌های دستی غیر VLESS، split tunnel و UI پیشرفتهٔ DNS/rules هنوز باقی مانده‌اند؛ updater آلفا ۸ تا build/CI و تست دستگاه diagnostic است.

## License

کد FOXConnect تحت **AGPL-3.0** است. sing-box/libbox تحت GPL-3.0 و تابع
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) است.

---

## English

FOXConnect is a no-root, ad-free and telemetry-free Android VPN client. Physical
testing of alpha 6 proved that libbox and Android TUN start, but protected traffic did
not return. The data-path audit found `tun.auto_route` enabled while
`route.auto_detect_interface` was disabled, preventing libbox from consistently
passing proxy sockets to `VpnService.protect()` and allowing a routing loop back into
the TUN. Diagnostic alpha 7 repairs that invariant and separately classifies physical
interface, bootstrap DNS, socket routing, secure DNS, strict TLS and HTTPS failures.
Initial connect remains exactly one selected-profile attempt; only a previously
verified tunnel may try one fallback. Auto-connect defaults off, package replacement
never connects, and every service start requires fresh user/explicit-boot authorization.

The supplied subscription path remains regression-tested: GZIP/Base64 decoding,
17 VLESS imports, encrypted persistence and visible refresh are preserved without
including any user endpoint or credential in source or diagnostics. Alpha 7 real
connectivity was physically confirmed.

Alpha 8 adds a token-free updater pinned to `hojjatrad/FOXConnect`: manual checks,
opt-in 24-hour metadata-only checks, optional pre-releases, user-approved downloads,
bounded strict-HTTPS transfers, companion SHA-256 verification, and APK package,
newer-version, single-ABI and installed-certificate verification before Android's
user-controlled installer opens. Production releases are built from GitHub Secrets
and published only with the workflow's ephemeral `GITHUB_TOKEN`; no PAT is embedded.
This remains a **debug-signed diagnostic device-test build, not a production release**
until the new Release-key migration and physical regression are completed. See
[HANDOFF.md](HANDOFF.md).
