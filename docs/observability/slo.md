# مستند SLO — أهداف مستوى الخدمة وعقد التنبيه (المرحلة أ)

> **الغرض:** هذا هو المرجع الحاكم لأهداف مستوى الخدمة (SLO) وقواعد التنبيه المرصودة عليها. يُقرأ مع `monitoring/prometheus-rules/marketplace-alerts.yml` (القواعد ككود-مصدر) وحارسه الاختباري `AlertRulesYamlTest` (يُفشل البناء إن نطقت قاعدة بمقياس غير مُثبت الوجود)، ومع `docs/observability/runbooks.md` (خطوات الاستجابة لكل إنذار — المرحلة ج) وحارسه `RunbooksFilesTest` (يفرض التطابق 1:1 بين القواعد وأقسام الـrunbooks).
>
> **الأصل:** المرحلة أ من خارطة إغلاق «تحليل فجوات نحو مستوى Airbnb» (الفجوة ف1: مراقبة مُجهَّزة بلا رقيب — القياسات تُصدَّر ولا تُستهلك). التزاماً بنظام الحوكمة (SYSTEM.md §14.1): كل مقياس هنا يحمل مصدر وجوده `ملف:سطر`، وكل عتبة موسومة بوصفها قيمة أولية.
>
> **قاعدة الصدق:** هذا المستند لا يدّعي ما ليس منفذاً. ما لم يُوصّل بعد (مثيّر Alertmanager، اللوحات) مُسجَّل في §6 بوصفه ديناً معلناً له نقطة إغلاق — لا يُدفن.

---

## 1. النطاق وحدود المسؤولية

- **ما يملكه المستودع:** تعريف SLOs، وقواعد التنبيه كملف إعداد (Prometheus Rules)، ومقاييس التشغيل (موجودة أصلاً وتُصدَّر)، وحارس اختباري يفرض العقد.
- **ما لا يملكه المستودع:** تشغيل Prometheus/Alertmanager/Grafana — قرار جهة التشغيل (كما هو عرف الإطلاق في `docs/release/rollout-strategy.md` والخطة الحاكمة للاستضافة). القواعد صيغة قياسية (Prometheus Rule File) تُحمَّل عبر `rule_files` عند من يقوم بالتشغيل.
- **تصدير المقاييس متاح من اليوم:** `/actuator/prometheus` مكشوف في base وprod (`application.yml:179-188` و`application-prod.yml:128-131`)، والصحة على منفذ إدارة منفصل في prod (`:114-116`).

## 2. SLO-1 — التوفر: 99.5% شهرياً

- **SLI:** نسبة الطلبات الناجحة = `1 − (طلبات 5xx / كل الطلبات)`، من مؤقّت `http.server.requests` (مقياس إطاري معياري من Micrometer/Actuator، مفعّل بـ `management.observations` — `application.yml:202-205`).
- **تنبيهات المرصودة:** `MarketplaceTargetDown` (هدف سحب معيّن مفقود 5 دقائق — `up == 0`) و`MarketplaceTargetAbsent` (الـjob كلّه غائب عن service discovery لمدة 5 دقائق — الفقد الكلي: المتجه الفارغ يُصمت `== 0`، فتنبيه `absent()` مستقل يحمل الإشارة بلا استيفاء `{{ $labels.job }}` لأن الوسم لا يُشتق من مرشّح regex — الفصل حسم ملاحظة المراجعة الجولة 2) و`MarketplaceHttp5xxRateHigh` (نسبة 5xx > 2% لمدة 10 دقائق).
- **ميزانية الخطأ:** 0.5% شهرياً (~219 دقيقة تعطل مكافئ = 0.5% × 43,776 دقيقة لشهر 30.4 يومي — القاعدة الرسمية «error budget is 100% minus the SLO»، SRE Workbook، محفوظ `scripts/pr239-docs/sre-implementing-slos.html`). عتبة التنبيه (2%) أشد من الميزانية عمداً: الهدف الإنذار المبكر قبل استهلاكها لا بعده.
- **الحالة:** قيمة أولية — تُعاير بعد أول ترافيك حقيقي (§6).

## 3. SLO-2 — نجاح الدفع: ≥ 99%

- **SLI:** `marketplace.payments.completed / (marketplace.payments.completed + marketplace.payments.failed)` — المصدر: عدادات `BusinessMetrics.java:31-34` (تُدار بأحداث النطاق عبر `BusinessMetricsEventListener`).
- **تنبيه المرصودة:** `MarketplacePaymentsFailing` — أكثر من 3 عمليات فشل خلال 15 دقيقة (عتبة مطلقة لا نسبية: تتجنب ضجيج النسب عند الترافيك المنخفض وتلتقط الانقطاعات الكاملة فور وجود أي محاولات).
- **الحالة:** قيمة أولية — تُعاير بعد أول دفعات فعلية.

## 4. SLO-3 — سلامة ناقل الأحداث: صفر منشورات متقادمة

- **SLI:** عدد صفوف `event_publication` غير المنجزة الأقدم من 6 ساعات — نفس استعلام `ModulithEventBusHealthIndicator.java:53-56` (العتبة 21600 ثانية تطابق `spring.modulith.events.staleness.published: 6h` — `application-prod.yml:54-57`).
- **المرآة القياسية (جديد هذا الـ PR):** القيمة نفسها تُنشر الآن كـ gauge باسم `marketplace.eventbus.stale` من داخل المؤشر نفسه — تتحديث ذاتياً بجدولة داخلية كل 60 ثانية (`@Scheduled` في المؤشر نفسه — ضروري لأن فحوص الإنتاج لا تشمله: liveness = ping وreadiness = db,redis,diskSpace، وكتامل /actuator/health لا يفحصه أحد؛ بدونه يتجمّد الgauge ولا يُطلق `MarketplaceEventBusStale` أبداً)، وعند فشل الاستعلام تحتفظ بقيمتها الأخيرة بينما يهبط مؤشر الصحة.
- **تنبيه المرصودة:** `MarketplaceEventBusStale` — أي قيمة > 0 لمدة 5 دقائق (هذا الناقل هو عمود الاتساق: قيود ledger، تأكيد booking، إشعارات notifications — تعثره انجراف صامت بين الوحدات، وهو عين الثغرة التي صنّفها تحليل الفجوات رقم 1).
- **لماذا gauge إضافةً إلى الصحة:** مؤشر الصحة يُستهلك عبر `/actuator/health` (يتطلب فحصاً موجهاً)؛ الـ gauge يجعل الحالة قابلة للاستهلاك من أي خط قياسات — ويحوّل «نصف الحلقة» (يُراقَب ولا يُدار) إلى حلقة كاملة.

## 5. Guardrails — حواجز حراسة (ليست SLOs)

إشارات إنذار مبكرة تسبق كسر أي SLO:

| القاعدة | الشرط | المصدر |
|---|---|---|
| `MarketplaceCacheInvalidationFailures` | > 5 إخفاقات إخلاء مخبأة في 15 دقيقة | `marketplace.cache.invalidation.evict.failure` — `CacheInvalidationMetrics.java` (يُحدّثه `CacheInvalidationRelay`) |
| `MarketplaceCircuitBreakerOpen` | قاطع دائرة مفتوح > 2 دقيقة | `resilience4j.circuitbreaker.state` — يأتي نقلياً (runtime) عبر `resilience4j-spring-boot4` 2.4.0 → `resilience4j-micrometer` (pom على Maven Central)؛ الحالات في `application.yml:246-258` |
| `MarketplaceDbPoolSaturated` | نشط/أقصى > 90% لمدة 5 دقائق | `hikaricp.connections.*` — Micrometer/Hikari (يُصدَّر `hikaricp_connections_active/_max`)؛ البادئة الرسمية `HIKARI_METRIC_NAME_PREFIX = "hikaricp"` — `MicrometerMetricsTracker.java:53,63,65`، المصدر محفوظ `scripts/pr239-docs/hikaricp-MicrometerMetricsTracker.java`؛ إعداد التجمع في `application.yml:23-29` (max 20) |

ملاحظة: `up{job=~".*marketplace.*"}` تفترض أن اسم job عند المشغل يحتوي «marketplace» — إن خالف، يُعدّل المرشّح عند التوصيل (مسجّل هنا صراحة لئلا يُكتشف متأخراً).

## 6. الديون المعلنة (كل واحدة بنقطة إغلاق)

| الدين | نقطة الإغلاق |
|---|---|
| لا Prometheus موصول يحمل القواعد (rule_files — تقييم القواعد يبدأ فقط بتحميله) ولا Alertmanager (توصيل الإشعارات فقط) — بوابتان منفصلتان | قرار جهة التشغيل عند اختيار مستهلك المراقبة (يقترن بالبوابة C — خطة الاستضافة الحاكمة) |
| لا لوحات Grafana — مؤجلة عمداً حتى يوجد المستهلك (لا تُبنى لوحات لأداة غير مختارة) | نفس البوابة |
| لا runbooks تفاعلية للحوادث المذكورة (خطوات «ماذا أفعل عندما يشتغل التنبيه»)) | ~~المرحلة ج من خارطة الإغلاق (الاسترداد والحوادث)~~ → ✅ **مُغلق (PR #245):** `docs/observability/runbooks.md` — 8 أقسام (قسم كامل لكل قاعدة: تثليث/تشخيص/تحقق/تصعيد) بمصادر رسمية مؤرشفة، والحارس `RunbooksFilesTest` يفرض التطابق 1:1 مع ملف القواعد (خمسة أقسام إلزامية + خطورة + مرساة SLO متقاطعين) |
| كل العتبات مبدئية بلا ترافيك مرجعي | المعايرة بعد أول canary (خطة الإطلاق) |

## 7. دورة حياة السجلات (مكمل تشغيلي من نفس المرحلة)

تدوير أُطري بإعدادات Boot الرسمية في `application.yml:233-239`: 50MB للملف، 14 يوماً احتفاظاً، سقف 2GB إجمالاً، تنقيف الأرشيف عند الإقلاع (`logging.logback.rollingpolicy.*` — الوثيقة الرسمية محفوظة: `scripts/pr239-docs/boot-logging-official.html`). يعمل مع الخرج المهيكل (`logging.structured.format.file: logstash` — نفس الملف `:225-229`) لأن كليهما يديره Boot على نفس الملحق الملفي.

## 8. بروتوكول الصيانة

- أي تعديل عتبة أو إضافة قاعدة: يُعدّل ملف القواعد + هذا المستند + `docs/observability/runbooks.md` **معاً** (القيمة ومبررها وخطوات الاستجابة لكل إنذار متأثر)، والحراس `AlertRulesYamlTest` و`RunbooksFilesTest` هما البوابة: إضافة مقياس جديد تتطلب إثبات وجوده أولاً (file:line) ثم إضافته للقائمة البيضاء، وإضافة قاعدة جديدة تتطلب قسم runbook بنفس الاسم (تطابق 1:1) — لا إنذار بلا خطوات استجابة.
- مرجعية الحالة الحية للأطر التشغيلية المفتوحة: SYSTEM.md §11 وPROJECT_MAP (لا يكرّرها هذا المستند — هو التفصيل التقني لطبقة القياس وحدها).
