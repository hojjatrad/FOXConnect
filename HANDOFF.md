# FOXConnect — handoff

آخرین به‌روزرسانی: 2026-09-07

## وضعیت فعال

نسخهٔ در حال توسعه: **`0.4.8-phase4-alpha9-reliability`**، `versionCode=14`.
تغییرهای سورس و تست‌های آلفا ۹ آمادهٔ مرور محلی هستند، اما هنوز commit/push نشده‌اند،
GitHub Actions اجرا نشده، APK ساخته/امضا/منتشر نشده و آزمون دستگاه انجام نشده است.
آخرین release عمومی و قابل نصب همچنان آلفا ۸ code 13 است.

کاربر اتصال واقعی، DNS و عبور ترافیک آلفا ۷ را پس از اصلاح mismatch قطعی
`auto_route=true` و `auto_detect_interface=false` تأیید کرد. آلفا ۸ engine را تغییر
نداد و updater را افزود. آلفا ۹ برای گزارش قطع‌شدن اتصال در استفادهٔ طولانی، failover
رتبه‌بندی‌شده و بازیابی lifecycle را تغییر می‌دهد؛ علت دقیق exit دستگاه بدون log هنوز قطعی نیست.

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

## اصلاح‌های آلفا ۹ (در انتظار CI و دستگاه)

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
  چهار تلاش و recovery مجاز اضافه شده‌اند؛ نتیجهٔ واقعی آن‌ها تا CI نامعلوم است.

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

## اعتبارسنجی سبز

- GitHub Actions run `34086712348` روی commit `5db147ef9ddf973db3e4dae2160fb655a7afb8be` موفق
- ۷۴ تست parser/storage/engine/updater بدون failure یا skip (۶۹ قبلی + ۵ updater)
- parser/storage regressions GZIP/Base64/17 VLESS و lifecycle/routing قبلی محفوظ
- updater tests: repository pin، stable/pre-release، downgrade/equal، channel و URL hostile
- `:app:lintDebug` و `:app:assembleDebug` موفق؛ هر دو split ARM64/ARMv7 ساخته شدند
- manifest/APK: FileProvider محدود، cleartext خاموش، VPN service همان `:vpn` و non-exported
- ARM64-only، certificate برابر آلفا ۷، signature v2، ZIP/zipalign 16 KiB و LOAD align
- scan نهایی: بدون PAT/credential، endpoint fixture یا `OWNER/FOXConnect`
- diff قطعی source در `core/engine/src` نسبت به آلفا ۷: صفر فایل

## تست بعدی دستگاه پس از انتشار آلفا ۹

1. ابتدا CI، lint و هر دو split را سبز کنید؛ ARM64 cloud artifact را با همان certificate
   آلفا ۷/۸ امضا و همهٔ package/version/ABI/signature/alignment/hash gateها را دوباره اجرا کنید.
2. code 14 را روی آلفا ۸ نصب کنید و باقی‌ماندن vault/settings را تأیید کنید.
3. اتصال، DNS، HTTPS، RX/TX و Disconnect عادی را regression کنید.
4. active config را پس از Verified عمداً از دسترس خارج کنید؛ Kill Switch باید در فاصلهٔ
   replacement ترافیک را ببندد و candidate سالم با کمترین metric تازه انتخاب شود.
5. چهار candidate نخست را خراب کنید و سالمی را در دور بعد قرار دهید؛ recovery نباید پس
   از یک fallback terminate شود و نباید Connected کاذب نشان دهد.
6. latency تونل فعال را به‌طور پایدار بالای threshold ببرید؛ یک spike نباید switch کند،
   اما چهار نمونهٔ متوالی با alternative معنادار باید switch کند. سپس cooldown سه‌دقیقه‌ای
   باید از رفت‌وبرگشت سریع جلوگیری کند.
7. UI را swipe-away و در صورت امکان process UI را جداگانه terminate کنید؛ VPN سالم باید
   در process `:vpn` بماند. سپس process VPN را terminate کنید و recovery rate-limited را بسنجید.
8. Android Force Stop باید همچنان session را متوقف کند. پوشش این حالت فقط با Always-on VPN
   و «Block connections without VPN» سیستم است.
9. updater آلفا ۸ باید pre-release code 14 را بدون token بیابد؛ دانلود و installer فقط پس
   از تأیید صریح کاربر و verification کامل انجام شوند.
10. آزمون طولانی‌مدت چندساعته اجرا و در صورت تکرار exit، زمان رویداد و logcat دسته‌بندی‌شده
    جمع‌آوری شود؛ علت دقیق exit فعلی هنوز فقط با دستگاه قابل اثبات است.

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
- آلفا ۷ از نظر اتصال/DNS/traffic روی دستگاه تأیید شد؛ آلفا ۸ updater منتشرشده و diagnostic است؛ آلفا ۹ هنوز ساخته/منتشر نشده و همهٔ تغییرهای reliability آن نیازمند CI و آزمون دستگاه‌اند.
