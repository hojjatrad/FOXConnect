# تغییرات FOXConnect

## 0.5.1-phase4-alpha12-panel-import-control — 2026-09-08

- افزودن import یک‌بارهٔ احراز هویت‌شده از Marzban و PasarGuard با
  `POST /api/admin/token`، Bearer token و `GET /api/users` مطابق API رسمی.
- افزودن Hiddify از نشانی شخصی Basic-auth یا مسیر مخفی API مدیر به‌همراه مسیر مخفی
  client؛ واردکردن client path حالت مدیر را صریح انتخاب می‌کند و credential مدیر هرگز
  به endpoint حدسی یا مسیر subscription کاربر منتقل نمی‌شود.
- فعال‌کردن موقت محافظ screenshot/recents اندروید هنگام نمایش فرم credential پنل.
- اعمال HTTPS با گواهی معتبر، ممنوعیت redirect برای درخواست احراز هویت، سقف پاسخ
  ۲ MiB، سقف مجموع ۴ MiB، ۱۰۰ کاربر و ۵۱۲ کانفیگ، cancellation و خطاهای عمومی پاک‌سازی‌شده.
- credentialها فقط در حافظه و برای همان عملیات استفاده و `CharArray` مالک بعد از پایان
  صفر می‌شود؛ password، bearer، URL محرمانه و raw config وارد vault metadata، WorkManager،
  log، crash text، UI summary، APK resource یا CI نمی‌شوند.
- تمام payloadها از parser مشترک `UniversalConfigImporter` عبور و فقط پروفایل معتبر در
  vault رمز‌شده ذخیره می‌شود؛ credential پنل برای refresh پس‌زمینه نگهداری نمی‌شود.
- طراحی مستقل کنترل اتصال لایه‌ای Compose با shadow/rim/face تماماً solid-color و بدون
  gradient یا asset ثالث، عمق press/release و haptic، tick/arc اتصال، ripple محدود حالت
  متصل و shake خطا.
- افزودن وضعیت واقعی `Disconnecting` به مدل، bridge بین‌فرایندی، tile و UI؛ این حالت پیش
  از فرمان teardown منتشر و فقط با گزارش VPN process به `Disconnected` تبدیل می‌شود.
- افزودن regressionهای جریان Marzban/PasarGuard/Hiddify، import ناقص، پاک‌سازی body خطا،
  حفظ cancellation، sanitization خطای غیرمنتظره، عدم انتقال header احراز هویت، رد HTTP،
  redaction نوع‌های حساس و هویت runtime هنگام قطع؛ `versionCode` به 17 ارتقا یافت.
- افزایش heap فقط برای مرحله‌های packaging diagnostic/release در GitHub Actions؛ run نخست
  پس از test/checker/lint با OOM در zipflinger شکست خورد و run اصلاحی PR کاملاً سبز شد.
- PR #4، run نهایی PR `34199330025`، run شاخهٔ main `34199993755` و workflow مستقل
  انتشار `34204205302` سبز شدند؛ pre-release عمومی `diagnostic-v0.5.1-alpha12` با APK
  ARM64 code 17 و SHA-256 برابر `8452b87a2fff43685e00b098546231554e01c6e64809831bb1fda472a1bdaf2c`
  منتشر و دانلود/checksum آن بدون token تأیید شد.
- دریافت APK Connectix 2.7.3 از URL رسمی همچنان در TLS/connect timeout شکست خورد؛ هیچ
  تحلیل باینری ادعا نمی‌شود و هیچ API از marketing یا حدس وارد پیاده‌سازی نشده است.

## 0.5.0-phase4-alpha11-auto-update — 2026-09-07

- فعال‌شدن بررسی خودکار metadata انتشارهای مخزن رسمی GitHub به‌صورت پیش‌فرض؛ اولین
  بررسی واجد شرایط پس از ۱۵ دقیقه و بررسی‌های بعدی هر ۲۴ ساعت با WorkManager و فقط هنگام
  وجود شبکه انجام می‌شود. کاربر همچنان می‌تواند آن را از Settings خاموش کند.
- migration یک‌باره برای نصب‌های قبلی، اعلان خودکار را فعال می‌کند؛ build تشخیصی به‌صورت
  پیش‌فرض pre-releaseهای debug را می‌بیند و build production همچنان روی stable می‌ماند.
- worker فقط metadata عمومی را می‌گیرد و هیچ APK را در پس‌زمینه دانلود یا نصب نمی‌کند؛
  خطای موقت شبکه با backoff پانزده‌دقیقه‌ای retry می‌شود.
- رفع نقص اعلان: versionCode فقط بعد از انتشار موفق notification ثبت می‌شود؛ نبود permission
  یا غیرفعال‌بودن notification دیگر باعث ازدست‌رفتن دائمی اعلان همان نسخه نمی‌شود.
- افزودن دکمهٔ «مشاهده و نصب» داخل notification؛ لمس آن صفحهٔ update را باز و metadata را
  دوباره از مخزن رسمی بررسی می‌کند.
- دکمهٔ update اکنون با یک اقدام کاربر APK را دانلود، اندازه/SHA-256/package/version/ABI/
  certificate را fail-closed تأیید و نصب‌کنندهٔ Android را باز می‌کند؛ نصب نهایی همچنان فقط
  با تأیید صریح خود Android انجام می‌شود.
- رفتار دستی «اکنون بررسی کن»، opt-out بررسی دوره‌ای، انتخاب pre-release و تمام محدودیت‌های
  HTTPS/redirect/size/repository حفظ شده‌اند؛ هیچ token، telemetry یا دانلود silent افزوده نشده است.
- پنج regression جدید برای default، migration، کانال stable/debug، جلوگیری از اعلان تکراری
  و زمان‌بندی افزوده و `versionCode` به 16 ارتقا یافت. PR #2 و runهای `34108267225` و
  `34108620356` با ۱۰۱ تست JVM، schema gate بومی، lint و هر دو split سبز شدند؛ APK ARM64
  با گواهی تشخیصی ثابت در `diagnostic-v0.5.0-alpha11` منتشر شد. آزمون updater و data path
  روی دستگاه همچنان اجباری است.

## 0.4.9-phase4-alpha10-data-path — 2026-09-07

- ثبت شکست فیزیکی آلفا ۹: برنامه `Connected` نشان می‌داد اما مرورگر/برنامه‌ها، دانلود و
  آپلود اینترنت نداشتند؛ ping endpointها موفق بود، نام پروفایل پس از failover قدیمی می‌ماند
  و رویداد قابل‌مشاهده‌ای گزارش نشد. آلفا ۹ پذیرفته نیست.
- اصلاح علت مستقیم حلقهٔ مسیر: `tun.auto_route=true` و `route.final=proxy` اکنون همیشه با
  `route.auto_detect_interface=true` تولید می‌شوند تا libbox کنترل پلتفرم را فراخوانی و
  هر سوکت upstream را با `VpnService.protect(fd)` به شبکهٔ فیزیکی هدایت کند.
- ممنوع‌شدن قطعی false Connected: علاوه بر HTTPS واقعی از داخل TUN، پیش از انتشار وضعیت
  Connected باید TUN، شبکهٔ فیزیکی، حداقل یک سوکت protect‌شده و RX/TX دوطرفهٔ گزارش‌شده
  توسط traffic manager خود libbox مشاهده شود؛ نبود هر شاهد connection را رد می‌کند.
- health probe بدون `protect`/network binding باقی ماند تا نتواند TUN را دور بزند؛ probe
  strict-TLS هر status معتبر HTTP را می‌پذیرد، یک byte از body را در صورت وجود می‌خواند،
  cache/connection reuse را می‌بندد و timeout اولیه/تعویض را به ۸/۶ ثانیه برمی‌گرداند.
- watchdog سالم فقط یک provider چرخشی را می‌سنجد؛ شکست آن با دو provider مستقل دیگر
  تأیید می‌شود و سپس در بودجهٔ حداکثر ۹ ثانیه failover آغاز می‌شود. این کار سربار سه
  TLS هم‌زمان دائمی را حذف می‌کند، بدون پذیرش شکست تک-endpoint.
- جایگزینی آمار ناقص UID با Status API خود libbox به‌عنوان منبع اصلی RX/TX و سرعت؛
  `TrafficStats` فقط fallback صریح است. probe هویت خروجی همچنان unprotected و داخل TUN است.
- home هنگام Connecting، Switching و Connected نام و protocol را فقط از snapshot runtime
  سرویس می‌گیرد؛ انتخاب قدیمی repository دیگر نمی‌تواند candidate فعال/تأییدشده را بپوشاند.
- شبکهٔ فیزیکی به‌عنوان underlying network به Android اعلام و در handoverها به‌روز می‌شود؛
  این اعلان application bypass ایجاد نمی‌کند و routeهای پیش‌فرض Android 8–12 نیز regression دارند.
- متن ping endpoint صریحاً توضیح می‌دهد که موفقیت TCP هیچ مدرکی برای credential، handshake
  پراکسی، DNS، TLS یا اینترنت داخل تونل نیست. codec گزارش رویداد نیز برای تمام codeها تست شد.
- native schema test دیگر skip نمی‌شود: workflow باینری رسمی sing-box 1.14.0 با SHA-256
  `2375de6999f4f56ab46b4fc5ddf26a6aba1d3e61a0f4e7ddec2f4690457d5f63` و revision
  `0b8995879f29a9b98ee027bc17b75e101445b238` را نصب و نبود/mismatch آن را fail می‌کند.
- regressionهای route/protect evidence، پاسخ HTTPS، candidate runtime، failover budget،
  event persistence و native traffic افزوده و `versionCode` به 15 ارتقا یافت.
- PR #1 و run اصلی GitHub Actions `34098938303` با checker بومی اجباری، ۹۶ متد تست JVM،
  lint و هر دو split تشخیصی سبز شدند. APK ARM64 با گواهی تشخیصی ثابت دوباره امضا و در
  pre-release `diagnostic-v0.4.9-alpha10` منتشر شد؛ پذیرش فقط تا آزمون فیزیکی معلق است.
- **تصحیح سابقه:** توضیحات آلفا ۷ در پایین این فایل ادعا کرده بود auto-detect و چند کنترل
  data path اصلاح شده‌اند؛ history مخزن ثابت کرد آن ادعاها با سورس/باینری آلفا ۷ تا ۹
  منطبق نبودند. این اصلاح‌ها برای نخستین بار در همین آلفا ۱۰ اعمال می‌شوند.

## 0.4.8-phase4-alpha9-reliability — 2026-09-07

- جداسازی کامل latency دسترسی TCP endpoint از latency واقعی HTTPS تأییدشده درون تونل؛
  metricها دیگر با یکدیگر جایگزین یا به‌عنوان مقدار هم‌معنا نمایش داده نمی‌شوند
- رتبه‌بندی candidateهای مجاز بر پایهٔ تازه‌ترین کیفیت تأییدشده و سپس ping دسترسی endpoint،
  همراه با رعایت cooldown و fallback قطعی برای مقدارهای ناشناخته
- تشخیص افت پایدار کیفیت با EWMA، چهار نمونهٔ متوالی، حداقل ۳۰ ثانیه اتصال پایدار،
  بهبود معنادار حداقل ۲۵۰ میلی‌ثانیه/۳۵ درصد و cooldown سه‌دقیقه‌ای برای جلوگیری از flapping
- افزودن تنظیمات فارسی/انگلیسی برای سوییچ کیفیت و آستانهٔ latency ضعیف با پیش‌فرض ۱۵۰۰ ms
- افزایش بودجهٔ هر دور بازیابی از یک به چهار candidate و ادامهٔ دورهای رتبه‌بندی‌شده با
  تأخیر محدود پس از شکست همه؛ authorization اتصال تأییدشده هنگام بازیابی لغو نمی‌شود
- حفظ Kill Switch در فاصلهٔ اجتناب‌ناپذیر جایگزینی TUN؛ وضعیت Connected فقط بعد از
  verification واقعی منتشر می‌شود و زمان انتظار با وضعیت Switching/اعلان مسدودبودن نمایش داده می‌شود
- حفظ VPN ایزولهٔ سالم هنگام crash رابط کاربری و حفظ authorization در تشخیص heartbeat stale
- فعال‌کردن `START_STICKY` فقط برای session مجاز دارای config و مجوز VPN، با سقف پایدار سه
  بازیابی فرایند در پنج دقیقه برای جلوگیری از crash loop؛ intent ناشناخته همچنان fail-closed است
- افزودن تست‌های قطعی برای رتبه‌بندی latency، تفکیک endpoint/tunnel، freshness، hysteresis،
  cooldown، دور چهارتایی و recovery مجاز؛ ارتقای `versionCode` به 14
- GitHub Actions run `34091352252` با ۸۳ test case، lint و split assembly پاس شد و
  pre-release تشخیصی منتشر شد؛ آزمون فیزیکی طولانی‌مدت هنوز لازم است، Android Force Stop
  قابل دورزدن نیست و handoff بدون فاصلهٔ مطلق تضمین نمی‌شود

## 0.4.7-phase4-alpha8-updater — 2026-09-07

- ثبت تأیید فیزیکی آلفا ۷: اتصال واقعی، DNS و عبور ترافیک پس از اصلاح route برقرار شد
- عمومی‌شدن مخزن رسمی `hojjatrad/FOXConnect` و انتشار آلفا ۷ به‌صورت Pre-release
  تشخیصی با APK، checksum companion، SHA256SUMS و verification metadata
- افزودن بررسی دستی update و بررسی دوره‌ای ۲۴ ساعتهٔ opt-in؛ worker فقط metadata را
  می‌گیرد و هرگز APK را در پس‌زمینه دانلود یا نصب نمی‌کند
- افزودن کانال پایدار پیش‌فرض و opt-in پیش‌انتشار، UI و خطاهای کامل فارسی/انگلیسی و
  notification قابل‌کنترل از همان switch دوره‌ای
- پین‌کردن updater به repository رسمی، HTTPS سخت‌گیرانه، host/redirect allowlist، سقف
  ۵۱۲ KiB metadata، ۴ KiB checksum و ۱۲۰ MiB APK و محدودیت تعداد release/asset
- الزام versionCode جدیدتر، asset تک‌معماری سازگار، checksum companion، SHA-256 واقعی،
  application ID یکسان و گواهی امضای یکسان با برنامهٔ نصب‌شده پیش از installer handoff
- دانلود فقط با دکمهٔ کاربر، نگهداری موقت در cache محدود FileProvider و نصب فقط از مسیر
  نصب‌کنندهٔ Android؛ دانلود/نصب silent وجود ندارد
- افزودن پیکربندی Release key فقط از environment/GitHub Secrets و workflow fail-closed
  برای build بومی، test/lint، splitهای امضاشده، cert/hash/ABI/alignment gate و انتشار با
  `GITHUB_TOKEN` موقت؛ هیچ PAT یا secret در سورس نیست
- حفظ کامل رفتار connectivity آلفا ۷؛ هیچ فایل engine/routing در این مرحله تغییر نکرد
- ارتقای versionCode به 13؛ build و updater هنوز تا عبور CI و تست دستگاه diagnostic است

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
