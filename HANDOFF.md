# FOXConnect — handoff

آخرین به‌روزرسانی: 2026-09-08

## وضعیت فعال

نسخهٔ در حال توسعه: **`0.5.1-phase4-alpha12-panel-import-control`**، `versionCode=17`،
روی branch **`feature/alpha12-panel-import-control`** و base
`ffe5d4a0979bc613825784fc42c47e23e3259cc3` است. feature در commit `fe0cd6c` و اصلاح
حافظهٔ packaging CI در `01c1b2e` روی PR #4 قرار دارند. run نخست `34196691852` بعد از
موفقیت همهٔ test/checker/lint فقط با OOM در `ApkFlinger` شکست خورد؛ heap فقط برای
assemble diagnostic/release افزایش یافت و run اصلاحی `34198554965` کاملاً سبز شد.
آلفا ۱۲ هنوز merge یا منتشر نشده است. این نسخه import یک‌بارهٔ احراز هویت‌شدهٔ Marzban،
PasarGuard و Hiddify، حالت واقعی `Disconnecting` و کنترل اتصال لایه‌ای بدون gradient را
اضافه می‌کند.

اعتبارسنجی نهایی محلی موفق شد: ۲۳ تست app، ۴۳ تست engine با checker واقعی sing-box
1.14.0، ۳۲ تست parser و ۱۳ تست storage، در مجموع ۱۱۱ تست بدون failure/error/skip.
`:app:lintDebug` نیز در اجرای جداگانه کامل و موفق شد. هر دو split debug محلی assemble
شدند، اما چون فقط برای compile از AAR بازسازی‌شده استفاده شد، APKهای محلی release artifact
نیستند و پاک می‌شوند. build/release معتبر باید فقط با AAR واقعی checksum-pinned در
GitHub Actions انجام شود.

همهٔ APK/ZIP/AAR محلی، خروجی‌های build، cache/toolchain موقت و پوشه‌های تحقیق پاک شده‌اند؛
حجم workspace حدود ۳٫۷ MiB است. APK و metadata آلفا ۱۱ فقط در GitHub Release عمومی
نگه‌داری می‌شوند. کلید خصوصی diagnostic تنها استثنا است و هرگز نباید به GitHub، source،
artifact یا log منتقل شود.

آلفا ۱۱ در commit پایهٔ فعلی منتشر شده است و updater خودکار GitHub را دارد. آزمون واقعی
panel import، updater و data path روی دستگاه هنوز انجام نشده است. آلفا ۱۰ در commit
`847db5b2a35f242827c5e514259cbaed1c1da968` و run `34098938303` CI را پاس و در
`diagnostic-v0.4.9-alpha10` منتشر شد؛ پذیرش فیزیکی data path هنوز انجام نشده است.

Pre-release آلفا ۹ (`diagnostic-v0.4.8-alpha9`) از نظر build و CI موفق بود، اما در
آزمون فیزیکی همان دستگاه/شبکه شکست خورد: UI وضعیت `Connected` داشت، endpoint pingها
موفق بودند، ولی مرورگر و برنامه‌ها، دانلود و آپلود اینترنت نداشتند. نام profile در home
پس از failover قدیمی بود و کاربر Connection Event قابل‌مشاهده‌ای ندید. آلفا ۹ قابل قبول نیست.

بررسی history ثابت کرد ادعای قبلی مستندات دربارهٔ اصلاح route در آلفا ۷ نادرست بوده است:
`route.auto_detect_interface=false` از commit اولیه تا APK آلفا ۹ باقی مانده بود. بنابراین
گزارش قدیمی «تأیید ترافیک واقعی آلفا ۷» نباید مبنای پذیرش قرار گیرد.

## تغییرهای آلفا ۱۲ (پیاده‌سازی و PR CI سبز؛ merge/انتشار/دستگاه در انتظار)

- `PanelImportClient` فقط HTTPS، TLS پیش‌فرض معتبر، بدون redirect/userinfo/fragment، با
  timeout و سقف body/user/config/total را می‌پذیرد؛ خطاها بدون URL/token/raw payload
  به reason ثابت تبدیل می‌شوند و `CancellationException` هرگز بلعیده نمی‌شود.
- Marzban/PasarGuard از token form و user list محدود با Bearer استفاده می‌کنند؛ Bearer
  به subscription URL منتقل نمی‌شود. پاسخ‌های کاربر ناموفق با count محلی گزارش می‌شوند.
- Hiddify یا personal path با Basic auth را مستقیم می‌خواند، یا با پرکردن client path
  صریحاً وارد admin mode می‌شود. در admin mode credential فقط به API مستند همان origin
  می‌رود و UUID subscription بدون header مدیر خوانده می‌شود.
- password مالک در `CharArray` و bodyهای token/user/error، payloadهای تحویلی پس از parse،
  bufferهای transport و payloadهای رهاشده best-effort overwrite می‌شوند. credential
  در vault/WorkManager/log/event/crash text ذخیره نمی‌شود و فرم موقتاً `FLAG_SECURE` است.
- payloadها دوباره از `UniversalConfigImporter` عبور می‌کنند، در سقف ۵۱۲ deduplicate و
  فقط profile معتبر با `ProfileSource.PANEL` در repository رمزگذاری‌شده ذخیره می‌شود.
- `ConnectionState.Disconnecting` در model/runtime/file bridge/controller/service/tile/UI
  واقعی است؛ پیش از teardown منتشر و پس از پایان teardown به Disconnected می‌رود.
- کنترل Home کاملاً original و solid-color است: depth press/release، haptic، orbit/tick
  اتصال، ripple محدود متصل، motion قطع و shake شکست؛ هیچ asset/code/branding کپی نشده است.
- دریافت APK رسمی Connectix 2.7.3 همچنان شکست خورده؛ تحلیل باینری یا استنتاج API ادعا نشود.

## کارهای بعدی الزامی آلفا ۱۲

1. commit مستندات نهایی را push کنید؛ پس از سبزی CI docs-only، PR #4 را merge و run اجباری
   main را با AAR واقعی checksum-pinned تا پایان سبز کنترل کنید.
2. diagnostic ARM64 را با همان هویت diagnostic پایدار stage/verify و به‌صورت prerelease
   منتشر کنید؛ package/version/ABI/hash/signature/16KiB alignment و secret scan باید gate
   شوند. لینک مستقیم APK/checksum عمومی و anonymous updater را تأیید کنید.
3. هیچ APK/ZIP/AAR/cache/build محلی پس از upload باقی نگذارید؛ کلید امضا هرگز upload نشود.
4. روی دستگاه واقعی هر سه نوع panel، حالت Hiddify personal/admin و خطا/partial/cancel را
   تست کنید؛ credential/raw config نباید در log/backup/recents دیده شود.
5. پذیرش data path آلفا ۱۰ به بعد همچنان لازم است: browser/app، DNS، upload/download،
   RX/TX، IP خروجی، چند failover، Kill Switch و updater/install با اقدام صریح کاربر.

## تغییرهای updater آلفا ۱۱ (CI سبز؛ منتشرشده، در انتظار دستگاه)

- policy revision یک‌باره بررسی دوره‌ای را برای نصب‌های موجود فعال می‌کند؛ بعد از migration
  opt-out کاربر حفظ می‌شود.
- default بررسی خودکار روشن است؛ first delay برابر ۱۵ دقیقه و دوره ۲۴ ساعت با flex شش
  ساعت و NetworkType.CONNECTED است. خطای شبکه با exponential backoff پانزده‌دقیقه‌ای retry می‌شود.
- debug channel به‌صورت پیش‌فرض pre-release را می‌بیند؛ release channel stable باقی می‌ماند
  و انتخاب صریح کاربر در revisionهای بعدی حفظ می‌شود.
- worker فقط metadata عمومی repo ثابت را می‌خواند. APK در background دانلود/نصب نمی‌شود.
- notification دارای action «مشاهده و نصب» است. version فقط پس از post موفق notification
  به‌عنوان notified ثبت می‌شود تا نبود permission اعلان را برای همیشه از بین نبرد.
- یک لمس کاربر downloadAndVerify را اجرا و پس از size/hash/package/version/ABI/certificate
  verification، installer سیستم را باز می‌کند؛ تأیید نهایی Android اجباری است.
- پنج تست policy جدید اضافه شده‌اند؛ کل suite اکنون ۱۰۱ متد `@Test` دارد.
- PR run `34108267225` و main run `34108620356` سبز شدند. artifact رسمی main با ID
  `10013523726` منبع ARM64 بود؛ SHA-256 ابری پیش از re-sign برابر
  `f10247f6950464f9da3182a987578beb6c5408445bcd88a28095e32832bb7cef` است.
- APK نهایی فقط با گواهی diagnostic ثابت re-sign شد: SHA-256 نهایی
  `83a154c8c3fdfafc4b6cdbaaa9838679ff96d93feb4d1786722cd6c0dd95bfec`، فقط v2،
  ARM64-only و zip/native alignment برابر 16 KiB.

## یافته‌های قطعی تعمیر آلفا ۱۰

1. config معیوب `tun.auto_route=true`، `tun.strict_route=true` و `route.final=proxy` را
   با `route.auto_detect_interface=false` ترکیب می‌کرد. در نتیجه callback پلتفرم برای
   `VpnService.protect(fd)` فعال نمی‌شد و upstream می‌توانست دوباره داخل TUN حلقه شود.
2. integration رسمی SagerNet نیز `usePlatformAutoDetectInterfaceControl()=true` دارد و
   `autoDetectInterfaceControl(fd)` را به `protect(fd)` می‌فرستد؛ FOXConnect همان الگو را
   دارد و باید route-level auto detection را فعال کند.
3. `HomeScreen` همواره repository selection را نشان می‌داد، در حالی که service snapshot
   از ابتدا identity واقعی candidate در Connecting/Switching/Connected را حمل می‌کرد.
4. endpoint TCP ping فقط دسترسی host/port را ثابت می‌کند و هیچ مدرکی برای credential،
   proxy handshake، DNS امن، TLS یا ترافیک داخل TUN نیست.
5. health verifier آلفا ۹ فقط status code دقیق 200/204 را می‌پذیرفت و timeoutهای ۳/۱٫۵
   ثانیه‌ای داشت؛ این رفتار روی شبکهٔ کند false negative می‌ساخت، نه اثبات کامل data path.
6. آمار UI از `TrafficStats` سطح UID می‌آمد که برای byteهای forwardشدهٔ VPN روی همهٔ OEMها
   کامل نیست. traffic manager و Status API خود libbox منبع بومی معتبرتر است.
7. تست `SingBoxNativeSchemaTest` با نبود `SING_BOX_CHECK` skip می‌شد و workflow checker
   نصب نمی‌کرد؛ بنابراین سبزی CI اعتبار JSON نزد هستهٔ دقیق 1.14 را ثابت نمی‌کرد.
8. Connected باید fail-closed باشد: HTTPS unprotected داخل TUN به‌تنهایی با شواهد TUN،
   شبکهٔ فیزیکی، protected upstream و RX/TX دوطرفهٔ native ترکیب می‌شود.

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

## ادعاهای مستندشده برای آلفا ۷ — ابطال‌شده توسط audit history

> هشدار: موارد این بخش release-noteهای قدیمی‌اند و با source/tag واقعی آلفا ۷ تا ۹ منطبق
> نیستند. اصلاح route/data-path واقعاً در آلفا ۱۰ وارد شد.

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

## تعمیرهای آلفا ۱۰ (CI سبز؛ در انتظار دستگاه)

- `SingBoxConfigFactory` اکنون `route.auto_detect_interface=true` تولید می‌کند و regression
  مشترک `auto_route`/`strict_route`/`final=proxy`/auto-detect افزوده شده است.
- `TypedLibboxCore` تعداد سوکت‌های protect‌شده، TUN، availability شبکه و bootstrap DNS
  را فقط به‌صورت counter حافظه‌ای نگه می‌دارد؛ هیچ destination یا credential ثبت نمی‌شود.
- پیش از Connected، service باید HTTPS strict-TLS واقعی را از TUN دریافت کند و سپس
  TUN/physical/protect به‌علاوهٔ RX/TX دوطرفهٔ Status API خود libbox را مشاهده کند.
- timeout اولیه/تعویض ۸/۶ ثانیه است. watchdog provider سالم را چرخشی می‌سنجد و failure
  primary را با دو provider دیگر تأیید می‌کند؛ بودجهٔ تشخیص hard failure حداکثر ۹ ثانیه است.
- native Status API منبع اصلی byte/speed است و UID `TrafficStats` فقط fallback مشخص است.
- Android underlying network هنگام start و handover به‌روز می‌شود؛ applicationها bypass
  نمی‌شوند و فقط سوکت‌های native محافظت‌شده به شبکهٔ فیزیکی می‌روند.
- home برای stateهای فعال profile/protocol را از runtime snapshot می‌گیرد؛ repository فقط
  در Disconnected/Failed منبع انتخاب است.
- event codec، پاسخ HTTP معتبر، data-path evidence، route fallback، UI runtime identity،
  classifier و failover budget تست قطعی دارند.
- workflow checker رسمی sing-box 1.14.0 را با SHA-256
  `2375de6999f4f56ab46b4fc5ddf26a6aba1d3e61a0f4e7ddec2f4690457d5f63` و revision
  `0b8995879f29a9b98ee027bc17b75e101445b238` provision می‌کند؛ test دیگر skip نمی‌شود.
- PR #1 و run اصلی `34098938303` سبز شدند. build محلی exact libbox روی sandbox 1.9 GiB
  در `gobind` کمبود حافظه داشت، اما cache پین‌شدهٔ GitHub AAR/API دقیق را compile و verify کرد.
- مرحلهٔ بعد فقط پذیرش همان دستگاه/شبکه است: browser/app، DNS، upload/download، profile
  فعال، چند failover اجباری، Connection Events، no-leak و کارکرد پایدار.

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

## اصلاح‌های آلفا ۹ (CI سبز؛ در انتظار دستگاه)

- `ProfileHealthStore` کلیدهای endpoint TCP آلفا ۸ را بدون relabel حفظ و metricهای جدید
  latency/timestamp تونل تأییدشده را جدا ذخیره می‌کند.
- `FailoverPolicy` ابتدا تازه‌ترین tunnel latency، سپس endpoint latency و در پایان ترتیب
  پایدار unknown را رتبه‌بندی می‌کند؛ cooldown persisted نیز هنگام restart seed می‌شود.
- افت کیفیت فقط پس از چهار نمونهٔ متوالی یک candidate، حداقل ۳۰ ثانیه اتصال، بهبود
  ۲۵۰ ms/۳۵٪ و cooldown سه‌دقیقه‌ای trigger می‌شود. EWMA وزن ۷۵٪ مقدار قبلی دارد.
- هر دور بازیابی حداکثر چهار candidate را می‌آزماید. session تأییدشده پس از تمام‌شدن
  candidateهای فعلی با delay محدود ۵ تا ۶۰ ثانیه و Kill Switch ادامه می‌دهد؛ Connected
  فقط پس از routed HTTPS verification منتشر می‌شود.
- بسته‌شدن process رابط فقط event امن ثبت می‌کند و authorization/VPN ایزوله را قطع نمی‌کند.
  heartbeat stale نیز authorization را لغو نمی‌کند.
- restart تهی Android فقط با authorization، active config، VPN consent و بودجهٔ پایدار
  سه restart در پنج دقیقه به `RECOVER` تبدیل و `START_STICKY` می‌شود. unknown intent و
  restart چهارم fail-closed هستند. Force Stop قابل دورزدن نیست.
- تنظیمات جدید کیفیت/آستانهٔ ۸۰۰، ۱۵۰۰ یا ۲۵۰۰ ms فارسی/انگلیسی هستند؛ پیش‌فرض کیفیت
  روشن و ۱۵۰۰ ms است. return-to-preferred فقط با خاموش‌کردن quality switch قابل انتخاب است.
- تست‌های policy/lifecycle برای ranking، freshness، جداسازی metric، hysteresis، cooldown،
  چهار تلاش و recovery مجاز اضافه شده‌اند و همراه کل suite و lint در CI پاس شدند.

## APK آلفا ۱۱

- انتشار عمومی: `https://github.com/hojjatrad/FOXConnect/releases/tag/diagnostic-v0.5.0-alpha11`
- دانلود مستقیم: `https://github.com/hojjatrad/FOXConnect/releases/download/diagnostic-v0.5.0-alpha11/FOXConnect-v16-debug-arm64-v8a.apk`
- نسخه: `0.5.0-phase4-alpha11-auto-update (16)`
- package: `com.foxconnect.app.debug`
- ABI: فقط `arm64-v8a`
- اندازه: `46,771,799` bytes
- SHA-256: `83a154c8c3fdfafc4b6cdbaaa9838679ff96d93feb4d1786722cd6c0dd95bfec`
- certificate SHA-256: `ffee4a25472705834a1fdb680fbae4ffce31e02d472fa7897765b767c307b709`
- امضا: همان certificate آلفاهای ۷–۱۰، فقط APK Signature Scheme v2
- ZIP/native alignment: 16 KiB؛ همهٔ ELF LOAD alignmentها: `0x4000`
- source commit کد: `5b99457c993ac6e599d0932711bc76aa8f4471c3`
- PR: `https://github.com/hojjatrad/FOXConnect/pull/2`
- PR CI: `https://github.com/hojjatrad/FOXConnect/actions/runs/34108267225`
- main CI: `https://github.com/hojjatrad/FOXConnect/actions/runs/34108620356`
- artifact رسمی: `10013523726`؛ cloud ARM64 SHA-256 پیش از re-sign:
  `f10247f6950464f9da3182a987578beb6c5408445bcd88a28095e32832bb7cef`

## APK آلفا ۱۰

- انتشار عمومی: `https://github.com/hojjatrad/FOXConnect/releases/tag/diagnostic-v0.4.9-alpha10`
- دانلود مستقیم: `https://github.com/hojjatrad/FOXConnect/releases/download/diagnostic-v0.4.9-alpha10/FOXConnect-v15-debug-arm64-v8a.apk`
- نسخه: `0.4.9-phase4-alpha10-data-path (15)`
- package: `com.foxconnect.app.debug`
- ABI: فقط `arm64-v8a`
- اندازه: `46,771,799` bytes
- SHA-256: `80ef15999d6cf135c26c4153d7f35a25ad67525a2c2ead8c3bc44aeabe2184ed`
- certificate SHA-256: `ffee4a25472705834a1fdb680fbae4ffce31e02d472fa7897765b767c307b709`
- امضا: همان certificate آلفاهای ۷–۹، فقط APK Signature Scheme v2
- ZIP/native alignment: 16 KiB؛ libbox LOAD alignment: `0x4000`
- source commit کد: `847db5b2a35f242827c5e514259cbaed1c1da968`
- PR CI: `https://github.com/hojjatrad/FOXConnect/actions/runs/34098538606`
- main CI: `https://github.com/hojjatrad/FOXConnect/actions/runs/34098938303`
- artifact رسمی: `10009796390`؛ cloud ARM64 SHA-256 پیش از re-sign:
  `741fa560854b25d4e8667aeaf56a197343f47b50b002a930f460af581b0d89ca`

## APK آلفا ۹ — شکست‌خورده و فقط برای سابقه

- انتشار عمومی: `https://github.com/hojjatrad/FOXConnect/releases/tag/diagnostic-v0.4.8-alpha9`
- دانلود مستقیم: `https://github.com/hojjatrad/FOXConnect/releases/download/diagnostic-v0.4.8-alpha9/FOXConnect-v14-debug-arm64-v8a.apk`
- نسخه: `0.4.8-phase4-alpha9-reliability (14)`
- package: `com.foxconnect.app.debug`
- ABI: فقط `arm64-v8a`
- اندازه: `46,759,511` bytes
- SHA-256: `5304c826c1554b74c355d9ac2c49eb8a0aeae2d634fd25994900b73e6b9ccdf2`
- certificate SHA-256: `ffee4a25472705834a1fdb680fbae4ffce31e02d472fa7897765b767c307b709`
- امضا: همان certificate آلفا ۷/۸، فقط APK Signature Scheme v2
- ZIP/native alignment: 16 KiB؛ libbox LOAD alignment: `0x4000`
- source commit: `69876c73017913618be02f30580931503f3f1f29`
- CI: `https://github.com/hojjatrad/FOXConnect/actions/runs/34091352252`
- artifact رسمی: `10007017089`؛ ARM64 artifact ابری با کلید persistent diagnostic دوباره امضا شد

API عمومی GitHub، digest، اندازه و retrieval بدون token برای APK/checksum تأیید شدند.
metadata همراه: companion checksum، `SHA256SUMS` و `VERIFICATION.txt`.

## APK آلفا ۸

- انتشار عمومی: `https://github.com/hojjatrad/FOXConnect/releases/tag/diagnostic-v0.4.7-alpha8`
- نسخه: `0.4.7-phase4-alpha8-updater (13)`
- package: `com.foxconnect.app.debug`
- ABI: فقط `arm64-v8a`
- اندازه: `46,743,127` bytes
- SHA-256: `c5d45705025120533d2c955698e7c0f0c52f9f983d0fbc01bdbc42f2a3b40a34`
- امضا: همان debug certificate آلفا ۷، APK Signature Scheme v2
- ZIP/native alignment: 16 KiB؛ libbox LOAD alignment: `0x4000`

metadata همراه: `FOXConnect-v13-debug-arm64-v8a.apk.sha256`، `SHA256SUMS` و
`VERIFICATION.txt`. digest عمومی GitHub و checksum companion تأیید شدند؛ APKهای محلی،
archive قدیمی، AAR، cache و build outputs نگهداری نمی‌شوند.

## اعتبارسنجی

- آلفا ۱۲: app=23، engine=43، parser=32 و storage=13، مجموع 111 test بدون
  failure/error/skip؛ engine checker واقعی sing-box 1.14.0 را اجرا کرد. `:app:lintDebug`
  و assemble هر دو split debug محلی موفق شدند. در GitHub، run `34196691852` تمام
  test/checker/lint را پاس کرد ولی packaging با heap 640 MiB OOM شد؛ پس از محدودکردن
  heap 1536 MiB به assemble diagnostic/release، run `34198554965` با AAR واقعی، test،
  checker، lint، packaging هر دو split و artifact upload کاملاً سبز شد. انتشار هنوز نیست.
- PR #2 با run `34108267225` و main با run `34108620356` برای آلفا ۱۱ موفق؛ checker
  دقیق 1.14.0، ۱۰۱ متد تست JVM، lint و هر دو split diagnostic سبز
- APK نهایی آلفا ۱۱: package/version/ABI، certificate ثابت، v2، zipalign 16 KiB، همهٔ
  ELF LOADها `0x4000`، manifest fail-closed و scan credential تأیید شدند؛ `core/engine`
  نسبت به آلفا ۱۰ تغییر نکرده است
- PR run `34098538606` و main run `34098938303` برای آلفا ۱۰ موفق؛ checker رسمی دقیق
  1.14.0/revision اجباری و بدون skip، ۹۶ متد تست JVM، lint و split assembly سبز
- APK نهایی آلفا ۱۰: package/version/ABI، certificate ثابت، v2، zipalign 16 KiB،
  libbox LOAD `0x4000`، manifest fail-closed و scan credential تأیید شدند
- GitHub Actions run `34091352252` روی commit `69876c73017913618be02f30580931503f3f1f29` موفق
- ۸۳ test case موجود parser/storage/engine/updater، application lint و split assembly موفق
- final ARM64: package/version/ABI، گواهی دقیق آلفا ۸، v2، zipalign 16 KiB، LOAD align
  `0x4000`، manifest و scan credential همگی دوباره تأیید شدند
- انتشار عمومی آلفا ۹: digest GitHub و companion checksum دقیقاً با hash نهایی برابرند
- run تاریخی آلفا ۸ `34086712348` روی commit `5db147ef9ddf973db3e4dae2160fb655a7afb8be` موفق
- ۷۴ تست parser/storage/engine/updater بدون failure یا skip (۶۹ قبلی + ۵ updater)
- parser/storage regressions GZIP/Base64/17 VLESS و lifecycle/routing قبلی محفوظ
- updater tests: repository pin، stable/pre-release، downgrade/equal، channel و URL hostile
- `:app:lintDebug` و `:app:assembleDebug` موفق؛ هر دو split ARM64/ARMv7 ساخته شدند
- manifest/APK: FileProvider محدود، cleartext خاموش، VPN service همان `:vpn` و non-exported
- ARM64-only، certificate برابر آلفا ۷، signature v2، ZIP/zipalign 16 KiB و LOAD align
- scan نهایی: بدون PAT/credential، endpoint fixture یا `OWNER/FOXConnect`
- diff قطعی source در `core/engine/src` نسبت به آلفا ۷: صفر فایل

## تست پذیرش دستگاه پس از انتشار آلفا ۱۲

1. روی panel آزمایشی بدون دادهٔ واقعی در source/log، Marzban و PasarGuard را با credential
   معتبر/نامعتبر و پاسخ‌های کامل/ناقص اجرا کنید؛ فقط profileهای معتبر باید وارد vault شوند.
2. Hiddify personal را با client path خالی و admin را با client path پر اجرا کنید؛ در proxy
   قابل‌کنترل ثابت کنید Basic فقط به personal یا API مدیر می‌رود و header مدیر روی UUID `/sub`
   و redirect ارسال نمی‌شود.
3. import را وسط token/user/subscription cancel و Activity را rotate/close کنید؛ VPN جاری و
   repository نباید خراب شود و credential نباید در logcat، crash، backup یا recents باشد.
4. برای response بزرگ، بیش از ۱۰۰ user، URL غیر HTTPS، TLS نامعتبر، redirect و payload
   unsupported، failure محلی درست و بدون URL/token/raw body نمایش داده شود.
5. کنترل Home را در Disconnected/Connecting/Connected/Disconnecting/Failed و Switching
   بررسی کنید؛ state فقط از runtime واقعی باشد، click دوم هنگام Disconnecting نادیده گرفته
   شود و depth/haptic/rings/ripples/shake بدون gradient درست باشند.
6. تمام پذیرش data path/updater فهرست آلفا ۱۱ نیز بدون کاهش اجرا شود.

## تست پذیرش دستگاه پس از انتشار آلفا ۱۱

1. CI باید unit tests، checker بومی اجباری، lint و هر دو split را سبز کند؛ ARM64 cloud
   artifact باید package/version/ABI/signature/alignment/hash gateها را پاس کند.
2. code 15 را با همان debug certificate روی build قبلی نصب و باقی‌ماندن vault/settings را بررسی کنید.
3. روی همان دستگاه/شبکهٔ شکست آلفا ۹، browser و یک app دیگر، DNS، HTTPS، دانلود و آپلود
   واقعی را اجرا کنید. Connected بدون همهٔ اینها failure محسوب می‌شود.
4. RX/TX و سرعت باید با ترافیک واقعی تغییر کنند و IP خروجی باید متعلق به تونل باشد؛ ping
   endpoint به‌تنهایی هیچ معیار پذیرشی نیست.
5. active profile را پس از Verified عمداً از دسترس خارج کنید؛ home باید فوراً candidate
   Switching و سپس نام/protocol profile واقعاً Verified را پایدار نشان دهد.
6. چند failover اجباری با profileهای سالم/خراب و ترتیب‌های متفاوت اجرا کنید؛ recovery دوری،
   cooldown، anti-flap و انتخاب بهترین metric تازه را بررسی کنید.
7. در تمام replacement/recovery با browser و DNS leak test مطمئن شوید Kill Switch مانع
   ترافیک مستقیم است؛ برای Force Stop از Always-on + Block connections without VPN استفاده کنید.
8. UI process و سپس VPN process را جداگانه terminate و recovery rate-limited را بررسی کنید؛
   قطع صریح و لغو مجوز نباید خودکار دوباره وصل شوند.
9. Connection Events باید connect/verified/switch/failure/kill-switch را بدون endpoint یا
   credential نشان دهد. نبود رویداد باید همراه زمان و logcat دسته‌ای ثبت شود.
10. از آلفا ۱۰، pre-release code 16 را بدون token بررسی کنید: metadata background باید
    پس از زمان‌بندی notification بدهد اما APK دانلود نشود؛ action اعلان باید UI را باز کند و
    یک اقدام صریح، دانلود/verification و installer را آغاز کند. نصب نهایی را Android تأیید کند.
11. آزمون چندساعتهٔ پایداری و failover را اجرا کنید؛ دانلود updater و installer فقط پس از
    اقدام کاربر و verification کامل مجازند.

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

## وضعیت updater / GitHub

- مخزن `https://github.com/hojjatrad/FOXConnect` در 2026-09-07 عمومی شد.
- سورس تمیز آلفا ۷ روی `main` با commit `4e2988b5b354e6e1223080e45589862df57a6c93` قرار گرفت.
- Pre-release عمومی آلفا ۷ در `https://github.com/hojjatrad/FOXConnect/releases/tag/v0.4.6-alpha7`
  شامل APK تأییدشده، checksum companion، SHA256SUMS و VERIFICATION است؛ digest منتشرشدهٔ
  GitHub برای APK دقیقاً `93dd2b7895a13b0262015262032ac0b9300d8251ebaba513ed7bd9190513d3e4` است.
- Pre-release عمومی آلفا ۸ در `https://github.com/hojjatrad/FOXConnect/releases/tag/diagnostic-v0.4.7-alpha8`
  با digest دقیق `c5d45705025120533d2c955698e7c0f0c52f9f983d0fbc01bdbc42f2a3b40a34` تأیید شد.
- آلفا ۸ با versionCode 13 updater بدون token، manual + periodic opt-in، stable/pre-release،
  notification، download دستی و verification کامل قبل از Android installer را اضافه می‌کند.
- Pre-release عمومی آلفا ۹ در `https://github.com/hojjatrad/FOXConnect/releases/tag/diagnostic-v0.4.8-alpha9`
  با hash دقیق `5304c826c1554b74c355d9ac2c49eb8a0aeae2d634fd25994900b73e6b9ccdf2` منتشر و
  از API عمومی/checksum بدون token تأیید شد.
- workflow با OAuth scope صحیح push شد و run نهایی سبز است؛ buildهای عادی read-only هستند
  و job انتشار tag به `contents: write` محدود می‌شود و فقط `GITHUB_TOKEN` موقت می‌گیرد.
- libbox رسمی دقیق پس از build و verify در GitHub Actions cache شد؛ buildهای بعدی checksum
  را دوباره کنترل می‌کنند و AAR هرگز در repository/release source قرار نمی‌گیرد.
- کلید Production هنوز باید خارج از chat ساخته و پنج GitHub Secret مستندشده طبق
  `docs/RELEASE.md` تنظیم شود؛ debug alpha8 نباید production معرفی شود.

## قواعد ثابت

- پیش از هر build جدید، APKها و metadata قدیمی، build outputها، cacheها و فایل‌های موقت پاک شوند؛ فقط آخرین APK، checksum و ZIP منبع نگه داشته شود.
- سورس نهایی در repository و APK/checksum در GitHub Releases نگهداری شوند تا workspace انباشته نشود.
- `build/`، cache، `libbox.aar` محلی، keystore، رمز، PAT و هر secret دیگر هرگز commit، archive یا release نشوند.
- مخزن رسمی و عمومی ثابت فقط `https://github.com/hojjatrad/FOXConnect` است؛ update feed عمومی هیچ tokenی ندارد.
- نام `FOXConnect`، شناسه release `com.foxconnect.app` و مجوز AGPL-3.0.
- فارسی پیش‌فرض RTL و انگلیسی LTR؛ همهٔ متن UI localized.
- بدون root، تبلیغات، telemetry، TLS bypass، دادهٔ ساختگی یا ادعای Connected کاذب.
- هیچ token، UUID، host، Reality key، URI، raw payload یا credential واقعی در
  source، fixture، log، docs، notification یا پاسخ وارد نشود.
- هیچ updater نباید repository، certificate، package، ABI، version یا hash gate را قابل‌دورزدن کند.
- ادعای تاریخی تأیید آلفا ۷ با audit ابطال شده است؛ آلفا ۹ فیزیکی شکست خورد و آلفاهای ۱۰/۱۱ تا پذیرش واقعی updater و data path همچنان diagnostic هستند.
