# تغییرات FOXConnect

## 0.4.6-phase4-alpha7-diagnostic — 2026-09-06

- ثبت نتیجهٔ فیزیکی آلفا ۶: setup، command server و Android TUN با موفقیت شروع شدند،
  اما تمام probeهای ترافیک امن شکست خوردند؛ بنابراین blocker از startup به data path محدود شد
- یافتن نقص قطعی route: با `tun.auto_route=true`، مقدار
  `route.auto_detect_interface=false` مانع فراخوانی کنترل پلتفرم و `VpnService.protect()`
  برای سوکت پراکسی می‌شد و سوکت خروجی دوباره داخل TUN می‌افتاد
- فعال‌کردن `route.auto_detect_interface=true` تا libbox سوکت‌های تونل را از VPN خارج
  و روی شبکهٔ فیزیکی محافظت‌شده هدایت کند؛ بدون bypass برنامه یا تضعیف Kill Switch
- افزودن شمارنده‌های فقط‌دسته‌ای و حافظه‌ای برای آماده‌بودن interface، تعداد سوکت‌های
  protect‌شده و موفقیت DNS bootstrap؛ هیچ endpoint یا credential ثبت نمی‌شود
- تفکیک verification به physical network، bootstrap DNS، socket routing، secure DNS،
  strict TLS، HTTPS response و routed response با پیام و event فارسی/انگلیسی مجزا
- پذیرش هر پاسخ واقعی HTTPS پس از TLS معتبر، به‌جای وابستگی به status code منطقه‌ای
  200/204؛ redirect یا خطای HTTP معتبر دیگر اتصال سالم را کاذب رد نمی‌کند
- افزایش timeout اولین verification به ۸ ثانیه و verification تعویض به ۶ ثانیه؛
  نخستین probe موفق همچنان فوراً نتیجه می‌دهد و بقیه لغو می‌شوند
- افزایش timeout watchdog از ۱٫۵ به ۳ ثانیه با بودجهٔ تشخیص محدود ۹ ثانیه برای جلوگیری
  از failover کاذب روی شبکه‌های کند
- افزودن regression برای invariant مشترک auto-route/auto-detect و تمام طبقه‌بندی‌های data path
- ارتقای versionCode به 12؛ build همچنان تا تأیید مرور، DNS، RX/TX، توقف و failover
  روی دستگاه diagnostic است

## 0.4.5-phase4-alpha6-diagnostic — 2026-09-06

- ثبت نتیجهٔ دستگاه آلفا ۵: درخواست تا سرویس `:vpn` می‌رسید، اما exception داخلی
  setup بومی فقط با کد کلی `native_start_failed` دیده می‌شد؛ اتصال واقعی تأیید نشد
- تفکیک کامل هشت مرز setup، version، checkConfig، command create/start، network
  monitor، service start و post-start با کد و متن localized امن، بدون exception خام
- ثبت category هر شکست start در گزارش رویداد داخل برنامه تا شکست باقی‌مانده روی دستگاه
  بدون endpoint، UUID، کلید، URI، payload، stack trace یا credential قابل تشخیص باشد
- هم‌راستاکردن ترتیب startup با ادغام رسمی Android: locale/setup، OOM draft، command
  server، monitor شبکهٔ فیزیکی و سپس native service
- حذف رد صریح `needFindProcess` و پیاده‌سازی connection-owner lookup در Android 10+
  با fallback procfs هسته در نسخه‌های قدیمی‌تر
- اصلاح پایش شبکه برای callback متناسب API، حذف VPN، fallback امن روی OEMهای ناسازگار،
  دریافت دیرهنگام LinkProperties و جلوگیری از گزارش کاذب نبود شبکهٔ فیزیکی
- حفظ مالکیت صحیح PFD/TUN، socket protection، DNS فیزیکی bootstrap و cleanup قطعی
- بهینه‌سازی probe آغاز اتصال: نخستین strict-TLS موفق پذیرفته و کارهای باقی‌مانده لغو می‌شوند
- تکمیل transportهای واقعاً موجود در sing-box 1.14 با HTTP و QUIC در کنار WS، gRPC
  و HTTPUpgrade؛ TCP خام باقی ماند و XHTTP/mKCP همچنان صریحاً unsupported است
- رد fail-closed رمزنگاری افزودهٔ QUIC که libbox 1.14 نمی‌تواند سازگار بازنمایی کند
- افزودن regressionهای stage classifier، transport parser/config و gate دقیق native 1.14
- ارتقای versionCode به 11؛ این build تا نصب، اتصال، DNS/traffic، توقف و failover واقعی
  روی دستگاه همچنان تشخیصی است

## 0.4.4-phase4-alpha5-diagnostic — 2026-09-06

- اجرای gate واقعی `sing-box check` با باینری دقیق 1.14.0 و revision پین‌شده
- کشف علت قطعی جدید در config: نبود `route.default_domain_resolver` در 1.14 باعث رد
  همهٔ JSONهای دارای endpoint دامنه‌ای می‌شد؛ resolver پیش‌فرض و bootstrap اضافه شد
- شکستن چرخهٔ DNS اولیه با `bootstrap-dns` متصل به Network فیزیکی و انتقال DNS امن
  از مسیر proxy پس از bootstrap
- کشف عدم پشتیبانی واقعی libbox 1.14 از XHTTP و mKCP؛ تولید JSON نامعتبر متوقف و
  به‌جای crash/تلاش بی‌نتیجه، پیام پایدار unsupported نمایش داده می‌شود
- تبدیل اتصال اولیه به دقیقاً یک تلاش روی پروفایل انتخاب‌شده؛ failure اولیه دیگر در
  پس‌زمینه بین همهٔ پروفایل‌های subscription گردش نمی‌کند
- محدودکردن failover یک اتصال قبلاً تأییدشده به حداکثر یک fallback در هر رخداد
- خاموش‌شدن پیش‌فرض auto-connect، حذف کامل trigger زمان package replacement و حفظ
  boot restore فقط در صورت فعال‌سازی صریح کاربر
- اضافه‌شدن authorization marker خصوصی برای رد هر connect بدون فرمان تازهٔ کاربر/boot
- قطع اضطراری fail-closed در صورت crash خود UI و ثبت فقط کد امن رویداد، بدون stack/raw data
- اصلاح file lock بین‌فرایندی state/event bridge و جلوگیری از مرگ observer بر اثر race
- جلوگیری از اجرای AppCompat locale در فرایند VPN و حفظ locale اعلان با context مستقل
- نمایش نسخه و versionCode داخل تنظیمات برای تشخیص قطعی build نصب‌شده
- ارتقای versionCode به 10؛ همهٔ unit testها، schema تمام خانواده‌ها و VLESS Reality،
  compile، lint، assemble، امضای v2، ARM64-only و هم‌ترازی ۱۶ KiB سبز
- این build همچنان تشخیصی است و اتصال واقعی دستگاه باید تأیید شود

## 0.4.3-phase4-alpha4-diagnostic — 2026-09-06

- ثبت صریح نتیجهٔ دستگاه واقعی آلفا ۳: عدم اتصال، بسته‌شدن UI و تلاش دوبارهٔ سرویس
- کشف و حذف lifecycle معیوب `START_STICKY` و تبدیل intent تهی به connect
- انتقال کامل `FoxVpnService` و libbox به فرایند مستقل `:vpn` تا abort بومی UI را نبندد
- اضافه‌شدن bridge وضعیت خصوصی، قفل بین‌فرایندی، heartbeat و تشخیص توقف فرایند بدون اتصال ساختگی
- جایگزینی dynamic proxy/reflection با ۲۷ callback typed پلتفرم و ۷ callback typed سرور فرمان
- ثبت مالکیت `CommandServer` پیش از start و بستن قطعی service/server/TUN/network monitor
- مصرف address، MTU، DNS، route/exclude و package rule واقعی `TunOptions`
- آزادسازی descriptor محافظ دقیقاً پیش از ساخت TUN جایگزین و محافظت socketهای خروجی
- انتخاب شبکهٔ فیزیکی با `NOT_VPN`، DNS متصل به همان Network و interface flags واقعی
- جایگزینی probe تک‌ارائه‌دهنده با چند strict-TLS probe مستقل و موازی
- دسته‌بندی خطای permission/TUN/protect/config/version/core/verification بدون متن خام یا secret
- افزودن تست policy برای رد null/unknown service intent و حفظ عدم retry خودکار
- ارتقای versionCode به 9؛ parser/storage/engine tests، compile، lint، manifest merge، assemble،
  ARM64 ABI، امضای v2 و هم‌ترازی ۱۶ KiB سبز
- این خروجی تشخیصی است؛ نصب و اتصال آلفا ۴ روی دستگاه هنوز تأیید نشده است

## 0.4.2-phase4-alpha3 — 2026-09-06

- بررسی مستقیم نمونهٔ واقعی کاربر: پاسخ HTTPS 200، Base64 معتبر و ۱۷ لینک VLESS معتبر
- تأیید end-to-end همان payload با `SubscriptionClient` و importer تولیدی: ۱۷/۱۷ کانفیگ
- تأیید sync و encode مخزن با همان ۱۷ کانفیگ: ۱۷ پروفایل و بدون خطای validation
- حذف ابهام gzip در Android/CDN با درخواست صریح `Accept-Encoding: identity`
- کشف و اصلاح تداخل Kill Switch: TUN مسدودکنندهٔ باقی‌مانده پس از Failed می‌توانست
  HTTPS به‌روزرسانی ساب و پینگ را نیز عمداً مسدود کند
- آزادسازی کنترل‌شدهٔ محافظ Failed پیش از refresh دستی/پینگ و انتظار تا Disconnected
- قطع خودکار اتصال فعال پیش از refresh سابی که پروفایل انتخاب‌شده به آن تعلق دارد
- ایمن‌سازی خطاهای فرمان disconnect و جلوگیری از crash رابط کاربری
- نمایش علت تفکیک‌شدهٔ خطای شبکه/TLS، HTTP، حجم، parser یا storage برای هر ساب
- اضافه‌شدن regression test دائمی برای ساختار واقعی panel: gzip → Base64 → ۱۷ VLESS
- ارتقای versionCode به 8؛ unit tests، compile، lint و assemble سبز

## 0.4.1-phase4-alpha2 — 2026-09-06

- اصلاح import ساب‌های Base64/Base64URL دارای JSON envelope، newline/slash escape و HTML entity
- نگهداری امن رکورد ساب حتی پس از اولین پاسخ نامعتبر تا خطا دیده و refresh دوباره ممکن باشد
- اضافه‌شدن دکمهٔ «به‌روزرسانی همهٔ ساب‌ها» در بالای صفحهٔ کانفیگ‌ها
- اضافه‌شدن «پینگ همه» با حداکثر ۸ TCP handshake موازی و timeout سه‌ثانیه‌ای
- نمایش صریح ناموفق/پشتیبانی‌نشدن برای UDP/QUIC به‌جای ساخت مقدار latency
- نمایش وضعیت روشن سوییچ خودکار و تعداد candidateها در صفحهٔ کانفیگ‌ها
- اصلاح clipping و جابه‌جایی آیکن‌های پایین صفحه با حذف محدودیت ارتفاع ناسازگار با system inset
- جلوگیری از بسته‌شدن Activity بر اثر خطای config/store/foreground-service و تبدیل آن به Failed
- اصلاح bridge بومی با `Libbox.newCommandServer`، SystemProxyStatus غیر-null و اطلاعات شبکهٔ غیر-null
- اضافه‌شدن `ACCESS_NETWORK_STATE` برای bridge هسته و ارتقای versionCode به 7
- تست‌های parser/storage، compile، lint و assemble سبز؛ اتصال بومی همچنان نیازمند بازآزمایی دستگاه است

## 0.4.0-phase4-alpha1 — 2026-09-06

- ذخیرهٔ رمز‌شدهٔ selected profile و حداکثر ۳۱ fallback با مهاجرت payload قبلی
- اضافه‌شدن watchdog strict-HTTPS با دو شکست متوالی و بودجهٔ تشخیص ۶ ثانیه
- اضافه‌شدن rotation، cooldown قابل تنظیم و بازگشت اختیاری به profile ترجیحی
- اضافه‌شدن TUN محافظ داخلی پیش‌فرض فعال در زمان switching و شکست نهایی
- تفکیک صریح محافظ داخل سرویس از lockdown سیستم Android در رابط کاربری
- ثبت latency واقعی، cooldown و مرتب‌سازی اندازه‌گیری‌های موجود بدون دادهٔ ساختگی
- اضافه‌شدن تنظیمات auto-connect/failover/cooldown/return/Kill Switch
- اضافه‌شدن event log کد-محور بدون profile/endpoint/config/credential
- اضافه‌شدن آمار live در notification و Quick Settings tile
- اضافه‌شدن تست‌های انتخاب، cooldown، بازگشت و بودجهٔ failover
- ارتقای versionCode به 6؛ زمان failover و leak prevention نیازمند تست دستگاه است

## 0.3.0-phase3-alpha2 — 2026-09-06

- اضافه‌شدن VMess نسخهٔ ۲ با الزام AEAD، `alter_id=0` و cipherهای مدرن
- اضافه‌شدن Hysteria 1/2 با TLS معتبر، bandwidth و obfuscation محدود و معتبر
- اضافه‌شدن TUIC v5 با congestion control و UDP relay معتبر
- اضافه‌شدن anyTLS با password و TLS سخت‌گیرانه
- اضافه‌شدن import فایل استاندارد WireGuard با چند Peer، CIDR، DNS، MTU، keepalive
  و reserved و تولید endpoint جدید sing-box 1.14 به‌جای outbound حذف‌شده
- ردکردن URI غیراستاندارد WireGuard، optionهای ناشناخته/تکراری، VMess legacy و
  همهٔ درخواست‌های certificate bypass
- اضافه‌شدن deep link برای VMess، Hysteria 1/2، TUIC و anyTLS
- تأیید schema کامل هر ۱۱ خانواده با CLI رسمی sing-box 1.14.0 از commit pin‌شده
- اضافه‌شدن تست‌های parser/import/engine برای پروتکل‌های جدید
- ارتقای versionCode به 5؛ خروجی همچنان debug-signed و نیازمند تست دستگاه است

## 0.3.0-phase3-alpha1 — 2026-09-05

- اضافه‌شدن مدل، parser سخت‌گیرانه، ذخیره/بازیابی و config هسته برای Trojan
- پشتیبانی Trojan از TLS/Reality و transportهای TCP، WebSocket، gRPC و HTTPUpgrade
- اضافه‌شدن Shadowsocks با قالب SIP002/legacy و cipherهای مدرن AEAD/2022
- ردکردن cipherهای ضعیف، pluginهای Shadowsocks و هرگونه TLS certificate bypass
- اضافه‌شدن SOCKS5 با احراز هویت اختیاری username/password
- اضافه‌شدن HTTP/HTTPS CONNECT با احراز هویت، path و TLS معتبر
- dispatch مشترک import/restore/selection و تولید outbound برای هر پنج پروتکل فعال
- اضافه‌شدن deep link برای Trojan، Shadowsocks و SOCKS؛ HTTP/HTTPS برای جلوگیری از
  تصاحب لینک‌های عادی فقط از clipboard/share/file/QR/subscription import می‌شود
- اضافه‌شدن تست parser و JSON هسته و عبور کامل parser/storage/engine، compile و lint
- ارتقای versionCode به 4؛ خروجی فعلی ARM64 و debug-signed و نیازمند تست دستگاه است

## 0.2.0-phase2-alpha2 — 2026-09-05

- اضافه‌شدن refresh خودکار ۲۴ ساعتهٔ subscriptionهای فعال با WorkManager
- محدودکردن اجرای background sync به شبکهٔ متصل و backoff برای خطاهای موقت
- امکان فعال/غیرفعال‌کردن refresh خودکار برای هر subscription
- ثبت fail-closed نتیجهٔ آخرین refresh بدون ذخیرهٔ URL یا credential در log
- اضافه‌شدن export/restore قابل‌انتقال و رمز‌شدهٔ همهٔ پروفایل‌ها و subscriptionها
- استفاده از PBKDF2-HMAC-SHA256 با ۶۰۰٬۰۰۰ iteration و AES-256-GCM
- احراز header، tag، schema، referenceها و محدودیت‌ها پیش از جایگزینی اتمیک vault
- پاک‌سازی best-effort آرایه‌های plaintext، کلید مشتق‌شده و عبارت عبور
- اضافه‌شدن تست round-trip، salt تصادفی، رمز اشتباه، tamper، سقف حجم و restore مخزن
- ارتقای versionCode به 3 و به‌روزرسانی مستندات فاز ۲

## 0.2.0-phase2-alpha1 — 2026-09-05

- اضافه‌شدن vault چندپروفایلی رمز‌شده با AES-GCM و Android Keystore
- اضافه‌شدن انتخاب، ویرایش ساختاریافته، حذف، تکثیر و favorite کانفیگ
- import دفاعی متن، Base64/Base64URL، GZIP و ZIP با سقف‌های ضد archive bomb
- import از clipboard، Share/VIEW و Android file picker
- اضافه‌شدن اسکن زندهٔ QR داخل برنامه با CameraX و ZXing متن‌باز و بدون telemetry
- اضافه‌شدن فرم دستی VLESS برای TLS/Reality و transportهای اصلی
- اضافه‌شدن subscription فقط-HTTPS با TLS معتبر، ETag، refresh و sync اتمیک
- پایدارشدن شناسهٔ اتصال VLESS مستقل از نام و ترتیب query
- سخت‌گیری بیشتر روی security، Reality public key و short ID
- بازیابی boot از selected profile رمز‌شده در نبود active engine config
- پاک‌سازی active config قدیمی هنگام disconnect یا تغییر پروفایل
- درخواست notification permission در Android 13+
- مهاجرت به AGP Built-in Kotlin
- تولید APKهای جداگانهٔ ARM64 و ARMv7
- اضافه‌شدن تست‌های importer، formatter، vault codec، repository و subscription sync

## 0.1.0-phase1 — 2026-09-05

- ایجاد ساختار Gradle چندماژوله و هویت release با `com.foxconnect.app`
- اضافه‌شدن تم تیره، پالت دقیق و کنسول اتصال بدون اسکرول
- پیاده‌سازی پنج وضعیت و motionهای اصلی دایرهٔ اتصال
- اضافه‌شدن مدل و parser دفاعی VLESS
- تولید کانفیگ sing-box برای VLESS، TLS، Reality و transportهای اصلی
- ساخت و ادغام libbox رسمی v1.14.0 برای ARM64 و ARMv7
- اضافه‌شدن `VpnService` پیش‌زمینه، Android TUN، اعلان و boot recovery
- اعتبارسنجی نسخه/schema توسط libbox و health check واقعی DNS/HTTPS
- رمزنگاری active config با AES-GCM و Android Keystore
