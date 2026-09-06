# خطة استراتيجية العملاء والاستضافة — الباك اند مرساةً
(Client & Hosting Strategy Plan — Backend as Anchor)

| البند | القيمة |
|------|--------|
| الحالة | **معتمدة بأمر المستخدم («اعتمدها» 2026-09-03) — مستند حاكم**؛ مراحلها بوابات إذن (§7)، لا يبدأ منها شيء إلا بكلمة صريحة |
| التاريخ | 2026-09-03 (مسودة) — اعتماد 2026-09-03 |
| الأساس | طلب المستخدم: «الباك اند فيها هو مشروعنا؛ الواجهة وجهة الاستضافة لم تحدد؛ الباك اند قد يتصل مع فلاتر أو Next.js أو أي لغة» + أمر الاعتماد: «اعتمدها» |
| الحوكمة | Source Mandate (اقتباس رسمي → مطابقة → حل) — نفس بروتوكول `docs/security/auth-system-redesign-plan.md` |
| المرجع الحاكم السابق | `docs/security/auth-system-redesign-plan.md` (مراحله 0/1/2/4 مغلقة على main `94287ac`) + `SYSTEM.md` §11 |

> **قاعدة ملزمة:** لا يبدأ تنفيذ أي مرحلة إلا بإذن صريح من المستخدم. أي تعديل توثيقي على
> `ARCHITECTURE.md` يمر عبر PR بجسم صادق، والدمج بكلمة المستخدم فقط.

---

## 1) المرساة — ما هو «مشروعنا» وما هو مثبَت رسميًا (من POM لا من الذاكرة)

الخطة تبدأ من **النواة الثابتة المعروفة**: الباك اند. كل رقم أدناه مقروء من `pom.xml` المدموج:

| المكوّن | الإصدار الفعلي (من POM) | الأساس الرسمي | الحالة |
|---------|------------------------|----------------|--------|
| Spring Boot parent | **4.1.1** (`pom.xml:10`) | docs.spring.io/spring-boot | ✅ مدموج ومستقر |
| Java | **25** (`pom.xml:41`) | openjdk.org/projects/jdk/25 | ✅ |
| Spring Authorization Server | **`spring-security-oauth2-authorization-server` 7.1.1** عبر starter `spring-boot-starter-security-oauth2-authorization-server` (`marketplace-platform-infra/pom.xml:39`)؛ Jackson override موثق (`pom.xml:91` — الاستثناء #11) | وثائق SAS الرسمية + إدارة Boot BOM | ✅ يعمل على main |
| Spring Modulith | **BOM 2.1.0** (استثناء #1 موثق) | docs.spring.io/spring-modulith | ✅ verify() في CI |
| Maven | 3.9.16 + mvnw | maven.apache.org (مرجع إلزامي في ARCHITECTURE.md §Appendix) | ✅ |
| الوحدات | 16 وحدة Maven (L0–L4) | Maven multi-module reactor | ✅ |

**ما هو مغلَّق على main ويسري على كل عميل مستقبلي** (نتيجة #184/#185/#186):
- **PKCE مفروض فعليًا عند التبادل** + حماية downgrade مزدوجة (RFC 9700 §2.1.1 — بايت-كود 7.1.1 مُحقَّق).
- **Consent موجب/سالب** مُختبَر على الصف الفعلي (S3).
- **مفاتيح prod دائمة** بـ fail-fast (`JwkSourceProdHardeningTest` + `keys/README.md`).
- **تسجيل العملاء على المسار الرسمي**: env → DB عند الإقلاع عبر `RegisteredClientRepository.save()` على الـ Jdbc singleton (قرار المستخدم 2026-09-01، PR #183) — لا سر مُودَع في المستودع.
- **INV-2 يسري على أي عميل جديد**: إعدادات كاملة صريحة (`require-proof-key` + `require-authorization-consent`) — لا اعتماد على افتراضيات Builder.

---

## 2) المجهولات المعلنة (ب قرار المستخدم — تُخطَّط كبوابات لا كافتراضات)

| المجهول | نص المستخدم الحرفي | موقف الخطة |
|---------|---------------------|------------|
| تقنية الواجهة/التطبيق | «قد يتصل مع تطبيق مبرمج بفلاتر أو واجهة مبرمجة بأي لغة مثل نيكست جي اس» | بوابة قرار B — مصفوفة الأنماط الجاهزة في §4 |
| جهة الاستضافة | «وجهة الاستضافة لم تحدد بعد» | بوابة قرار C — لا افتراض Cloudflare بعد الآن (§6) |

**أثر صادق على التوثيق الحالي:** `ARCHITECTURE.md` §2/§5 يفترض «React SPA على Cloudflare Pages».
هذا الافتراض **لم يعد صالحًا** بعد كلام المستخدم — يُسجَّل كدين توثيقي يُقفل بـ PR صادق عند حسم القرارين
(لا يُقفل الآن تعسفيًا لأن البديل نفسه غير محدد).

---

## 3) المبدأ الحاكم من الوثائق الرسمية — الخادم لا يعرف تقنية العميل

عقد الباك اند مع أي عميل هو **بروتوكول + تسجيل**، لا تقنية: نقاط نهاية OAuth2/OIDC القياسية
(authorization/token/introspection/revocation/JWKS) + صف `RegisteredClient`. لغة العميل أو إطاره
لا تدخل الباك اند إطلاقًا. الاستشهادات الرسمية الحرفية (SAS — How-to: SPA with PKCE):

> **«A SPA cannot securely store credentials and therefore must be treated as a public client.
> Public clients should be required to use Proof Key for Code Exchange (PKCE).»**

> **«Spring Authorization Server will not issue refresh tokens for a public client.
> We recommend the backend for frontend (BFF) pattern as an alternative to exposing a public client.
> See gh-297 for more information.»**

> **«The requireProofKey setting is important to prevent the PKCE Downgrade Attack.»**

> **«It is recommended that you use a robust client-side library supported by your single-page app
> framework to handle the Authorization Code flow.»**

وبالتالي: **قاعدة التصنيف الرسمية** = *أين يستطيع السر أن يعيش بأمان؟* لا *بأي لغة كُتب العميل؟*

---

## 4) مصفوفة أنماط العملاء (تصنيف بمكان السر — تسري على أي لغة)

| النمط | التصنيف الرسمي | أمثلة واقعية لطلب المستخدم | ما يطلبه من الباك اند (كله إعداد، صفر كود) |
|-------|----------------|---------------------------|---------------------------------------------|
| **(1) BFF / خادمي** | عميل سري confidential + سر من env | Next.js بـ API routes/route handlers كوسيط؛ أي خادم بأي لغة | صف `RegisteredClient` بـ `client_authentication_method: client_secret_basic` + `authorization_code` + `refresh_token`؛ السر عبر مسار #183 (env→DB)؛ cookie جلسة للمتصفح والتوكنات تبقى خادميًا — **النمط الموصى به رسميًا** (اقتباس §3-ب) |
| **(2) SPA صافية (متصفح فقط)** | عميل عام public + PKCE | Next.js/React client-side خالصة | `client_authentication_method: none` + `require-proof-key: true`؛ **بلا توكنات تجديد** (سلوك SAS الرسمي) + CORS بأصول صريحة |
| **(3) تطبيق أصيل Native** | عميل عام public + PKCE | **تطبيق Flutter** (iOS/Android/Desktop/Web) | مثل (2) + redirect URI بمخطط مخصص/https app link (RFC 8252 — مُستشهد به أصلًا في ARCHITECTURE.md §6) |

**ملاحظة أمان جلسات للنمطين (2)/(3) (تُعرض بصدق لا تُخفى):** توكن الوصول الحي TTL=900s
(`TokenSettings` — تصحيح موثق ببوابة B: القيمة الفعلية في كود المُهيّئين والمواصفة
§3-أ هي `accessTokenTimeToLive(Duration.ofSeconds(900))`؛ 300s هي عمر **رمز التفويض**
authorization_code لا توكن الوصول) وبلا refresh tokens للعميل العام — أي أن إعادة المصادقة ستكون متكررة عبر
تدفق المتصفح (تنعّمها الجلسة على الخادم نفسه). الخيارات الرسمية عند الحاجة:
- قبول النمط الرسمي كما هو (المعيار الأعلى أمنًا)، أو
- **TokenSettings لكل عميل** (`accessTokenTimeToLive` لكل `RegisteredClient` — واجهة رسمية في SAS) لعمر أطول للوصول، مع توثيق الأثر الأمني مقابل ذلك.
- القرار في كل الأحوال **للمستخدم** عند اختيار النمط، وليس افتراضًا من الخطة.

**لكل عميل جديد يُضاف (بقرار المستخدم):** صف تسجيل بإعدادات صريحة كاملة (INV-2) عبر مسار #183،
أصل CORS واحد (`CORS_ALLOWED_ORIGINS` موجود ومربوط بالفعل في `application-prod.yml:123`)،
redirect URIs صريحة، واختبار بنمط S2/S3 يحمل الصف الفعلي — **لا كود جديد، لا وحدة جديدة، لا دين**.

---

## 5) ما لا يتغير في الباك اند (صفر دين — هذا جوهر «الباك اند هو مشروعنا»)

1. البنية النمطية (16 وحدة Modulith) لا تتأثر بالعملاء — الحدود محكومة بـ `ApplicationModules.verify()` في CI.
2. سلاسل الأمن الثلاثة (AS بجلسة+CSRF؛ RS بـ STATELESS؛ الافتراضية للنموذج/الدخول) بترتيب `@Order(1..3)` ثابتة (INV-5/6) — إضافة عميل لا تفتح سلسلة.
3. عقد الادعاءات (`aud`, `roles` — RFC 7519/RFC 9068) ثابت (INV-4) — عملاء جدد يستهلكونه كما هو.
4. المفاتيح و سياسة الأسرار كما حُسمت (D6 + #183) — لا عودة.
5. **صفر اعتماديات Maven جديدة** لهذه الخطة: ما يحتاجه أي عميل موجود أصلًا في SAS 7.1.1.

---

## 6) الاستضافة — بوابة قرار مفتوحة (لا افتراض Cloudflare)

**قابلية النقل المثبتة من الوثائق الرسمية (هذا هو الأساس الرسمي لقسم الاستضافة):**

| الحقيقة | الدليل الرسمي | الحالة |
|---------|----------------|--------|
| الصورة حاوية رسمية بطبقات | Dockerfile يستخدم `jarmode=tools` (المرجع الرسمي: spring-boot/reference/packaging/container-images/dockerfiles.html — «java -Djarmode=tools -jar application.jar extract --layers») | ✅ منفذ (مرحلة 0 مغلقة) |
| فحوص صحة/جهوزية رسمية | Actuator `/actuator/health/{liveness,readiness}` (منفذ — application.yml probes) + بوابة نشر المنصة `healthcheckPath` في `railway.toml` [deploy] (دينها الموثق: لا ينعكس في manifests — SYSTEM.md §15)؛ **لا HEALTHCHECK في Dockerfile عمدًا**: وصفة Boot 4.1 الرسمية للحاوية لا تتضمنه والمنصة تملك بوابة النشر | ✅ (مُصوَّب 2026-09-04: النص القديم ادّعى وجود HEALTHCHECK في Dockerfile — لم يوجد قط، تحقق git -S) |
| إعدادات إنتاج بلا افتراضات | `application-prod.yml` fail-fast: `${DB_PASSWORD}`, `${JWT_KEYSTORE_*}`, `${CORS_ALLOWED_ORIGINS}`, `${AUTH_SERVER_ISSUER}` (أُغلق بالمرحلة A: كانت قيمة الأساس `http://localhost:8080` تتسرب للإنتاج) | ✅ |
| **متطلب مشترك لأي مضيف خلف بروكسي TLS** | `server.forward-headers-strategy: FRAMEWORK` في `application-prod.yml` فقط (مرجع Spring Boot الرسمي how-to/webserver «Running Behind a Front-end Proxy Server» + مرجع Spring Security 7.1.1: حد الثقة — خلف بروكسي موثوق فقط) + `server.tomcat.redirect-context-root: false` (وصفة الجافادوك الرسمية) | ✅ **مُغلق بالمرحلة A** (اختبار حارس: `ForwardHeadersProdConfigTest` + دبوس سلوك الفلتر: `ForwardedHeaderFilterBehaviorTest`) |
| قواعد البيانات خارجية مُدارة | PostgreSQL 18 + Redis 8 في المستودع منذ 2026-09-04 (#198 — per postgresql.org/redis.io current stable)؛ الإنتاج الحي على Railway ما زال PostgreSQL 17 + Redis 7 حتى الترقية البوابية الموثقة (SYSTEM.md §15)؛ قابلان للاستبدال بأي مزود مُدار متوافق | ✅ قابل للنقل |

**ترتيب القرارات (سبب منطقي موثق):** القرار (1) تقنية العميل يسبق القرار (2) الاستضافة،
لأن الاستضافة تتبع العميل:
- **Next.js خادمي/BFF** يحتاج مضيف Node للتشغيل (أو حاويتين: Java + Node).
- **Flutter** لا يحتاج استضافة ويب أصلًا (متاجر التطبيقات) — يتبقى استضافة الباك اند وحده.
- **SPA صافية** تحتاج CDN/استضافة ملفات ثابتة فقط.

**المرشحون الموثقون (لا ترتيب تفضيلي — القرار للمستخدم):**

| المرشح | الوثائق المتوفرة | الديون المسجلة |
|--------|------------------|-----------------|
| Cloudflare Containers + Workers | `docs/architecture/ARCHITECTURE.md` §5 (خطة 5 مراحل؛ مرحلة 0 ✅) | المرحلتان 1-2 بيد المستخدم؛ افتراض «React SPA على Pages» في §5 انتهت صلاحيته (§2 أعلاه) |
| Railway | `docs/railway-deployment-reference.md` | 3 روابط مكسورة (موثقة) |
| أي مضيف حاويات آخر | معيار الصورة الرسمية أعلاه | — |

---

## 7) المراحل (بوابات — لا تبدأ إلا بإذن صريح)

| المرحلة | المحتوى | الشرط المسبق | نوع العمل |
|---------|---------|----------------|------------|
| **A — جاهزية مضيفة-محايدة** (اختيارية) ✅ نُفِّذ (2026-09-03، أمر المستخدم «أبدأ A») | قفل دينَّي `forward-headers-strategy` (FRAMEWORK في prod فقط) + تدقيق prod profile النهائي: أُغلق تسرب افتراض issuer (`${AUTH_SERVER_ISSUER}` بلا افتراض) + أُصلح عيب صياغة YAML كامن في نمط تعقيم السجلات | إذن المستخدم ✅ | كود صغير + اختبار (1 PR: هذا) |
| **B — العميلان الأولان ✅ نُفِّذا (2026-09-03، أمر المستخدم: النمطان معًا — Flutter العام + BFF السري)** | **النمط (3) عميل عام:** `OAuth2PublicClientInitializer` — بلا سرّ (`none`) + PKCE إلزامي + `authorization_code` حصرًا (بلا refresh — سلوك gh-297 مُختبر حيًّا: طلب refresh من عميل عام يُرفض 401 قبل تقييم المنحة) + redirect URIs من `OAUTH_PUBLIC_CLIENT_REDIRECT_URIS`؛ **النمط (1) سري:** redirect URIs موجهة بالبيئة (`OAUTH_CLIENT_REDIRECT_URIS`) + fail-fast في prod (يغلق دين redirect الإنتاج المثبّت على ثابت التطوير) + converge بإعادة اشتقاق `withId` | إذن المستخدم ✅ («معا وليس واحد») | إعداد + اختبارات — PR واحد للنمطين معًا (انحراف موثق عن «1 PR لكل عميل»: أمر المستخدم الصريح + بنية التأسيس المشتركة) |
| **C — نشر** | النشر وفق وثائق المضيف المختار الرسمية + قفل دين §5 التوثيقي في ARCHITECTURE.md (استبدال افتراض SPA المنتهي بالقرار الفعلي) | **قرار المستخدم: جهة الاستضافة** (بعد B منطقيًا) | عمليات + PR توثيقي صادق |
| **D9 متعدد العملاء** | يُعاد صياغة D9: كل عميل بتسجيله المستقل؛ إن لم يظهر مستهلك ويب فعليًا يبقى خيار (ج) إزالة العميل الميت قائمًا | يُحسم تلقائيًا مع B | — |

---

## 8) مصفوفة الاتساق الرسمي

تقييم الخطة عند اعتمادها («هل هي متناسقة ومستندة للوثائق والممارسات الرسمية في سبرينغ ومايفن؟»):

| # | عنصر الخطة | الأساس | الحكم |
|---|------------|--------|-------|
| 1 | الباك اند مرساةً (Boot 4.1.1 + Modulith 2.1 + SAS 7.1.1 + Java 25) | أرقام من POM + وثائق رسمية + CI أخضر ×2 على main | ✅ **رسمي ومثبت** |
| 2 | مبدأ «الخادم لا يعرف تقنية العميل» | بنية SAS الرسمية (نقاط نهاية البروتوكول + RegisteredClient) | ✅ **رسمي** |
| 3 | تصنيف العملاء بمكان السر لا باللغة | SAS how-to حرفيًا: «A SPA cannot securely store credentials...» | ✅ **رسمي (نص حرفي)** |
| 4 | توصية BFF للويب الخادمي + منع refresh tokens للعميل العام | SAS how-to حرفيًا: «We recommend the backend for frontend (BFF) pattern...» | ✅ **رسمي (نص حرفي)** |
| 5 | فلاتر = عميل عام + PKCE | SAS how-to (public client + requireProofKey) + RFC 8252 (موثق أصلًا في ARCHITECTURE.md §6) | ✅ **رسمي** |
| 6 | PKCE مفروض + حماية downgrade + consent | RFC 9700 §2.1.1 (اقتباسات verbatim في الخطة الحاكمة) — **منفذ ومختبر على main** | ✅ **منفذ** |
| 7 | سر العميل من البيئة (env→DB) | قرار المستخدم 2026-09-01 على المسار الرسمي (Jdbc singleton + save) — PR #183 مدموج | ✅ **مقرر ومنفذ** |
| 8 | إعدادات صريحة كاملة لكل عميل (INV-2) | نتيجة F-A بالبايت-كود (ClientSettings لا يدمج الافتراضيات عند القراءة من DB) | ✅ **مثبت ومُختبر** |
| 9 | الصورة: jarmode=tools + طبقات | مرجع SB الرسمي container-images/dockerfiles | ✅ **منفذ (مرحلة 0)** |
| 10 | forward-headers-strategy خلف بروكسي | مرجع SB الرسمي (how-to/webserver) + مرجع SS 7.1.1 (حد الثقة) + قضية Boot الرسمية #42804 (NATIVE لا يحترم X-Forwarded-Port) + مصدر Boot 4.1.1 (FRAMEWORK = ForwardedHeaderFilter بترتيب HIGHEST_PRECEDENCE) | ✅ **مُغلق بالمرحلة A** — `FRAMEWORK` في `application-prod.yml` فقط |
| 11 | توكن وصول TTL=900s بلا تجديد للعميل العام (تصحيح بوابة B — كانت 300s خطأ نقل) | سلوك SAS الرسمي (الاقتباس الحرفي في §3) + TokenSettings الرسمية + كود المُهيّئين واختبار S4 الحي | ✅ **رسمي ومنفذ** (الخيارات موثقة بصدق في §4) |
| 12 | **جهة الاستضافة** | — القرار لم يُتخذ بعد من المستخدم | 🔶 **بوابة قرار — الخطة لا تدّعي مرجعية رسمية هنا، بل تحفظ قابلية النقل الرسمية** |
| 13 | CORS بأصول صريحة | SAS how-to (CorsConfigurationSource بأصول محددة) — مربوط env عندنا | ✅ **رسمي ومنفذ** |
| 14 | ممارسات Maven: multi-module + enforcer + CI `mvn clean verify` | maven.apache.org (مراجع إلزامية في ARCHITECTURE.md) + AGENTS.md | ✅ **رسمي ومتبع** |
| 15 | افتراض «React SPA على CF Pages» في ARCHITECTURE.md §5 | — | ❌ **منتهي الصلاحية بموجب كلام المستخدم — دين توثيقي يُقفل عند القرار (§2)** |

**الخلاصة الصادقة:** كل ما يمس **سبرينغ ومايفن** في الخطة (البنود 1-11، 13-14) مستند إلى وثائق رسمية
باقتباسات حرفية أو منفذ ومختبر على main. البندان 12 و15 ليسا «غير رسميين» بل **قرارين مفتوحين
بيد المستخدم** تعلّم الخطة التعامل معهما كبوابات بدل افتراض إجابات — وهذا بحد ذاته موقف
الحوكمة الصحيح. **لا يوجد في الخطة أي بند مخالف لوثيقة رسمية، ولا أي ترقيع.**

---

## 9) سجل المخاطر

| المخاطرة | الأثر | التخفيف (رسمي) |
|----------|------|-----------------|
| عميل عام بلا refresh tokens (سلوك SAS) | UX إعادة مصادقة متكررة لفلاتر/SPA | خيارات TokenSettings الرسمية موثقة في §4 — القرار للمستخدم |
| نطاقات CORS غير معروفة قبل اختيار الواجهة | طلبات مرفوضة عند أول عميل | إعداد فقط: `CORS_ALLOWED_ORIGINS` env fail-fast موجود |
| اقتران الاستضافة بتقنية العميل | قرار استضافة متسرع يُندم عليه | ترتيب البوابات B قبل C (§6) |
| انحراف توثيقي §5 (افتراض SPA منتهي) | قارئ يُضلَّل عن الواقع | قفل صادق بـ PR عند حسم القرار (المرحلة C) |
| عدة عملاء = عدة أسرار | اتساع سطح الأسرار | نفس نمط #183 لكل سر: env فقط، لا قيم مودعة؛ `secrets-policy.md` يسري |

---

## 10) الاستشهادات الرسمية (Verbatim)

> المرجع الأول هو دائمًا الصفحة الرسمية. النسخ النصية الكاملة محفوظة في أرشيف جلسة العمل
> خارج المستودع (`scripts/prod-design-docs/sas-howto-spa-pkce.txt` — أرقام الأسطر هناك لأغراض
> التدقيق الجلسي، ليست جزءًا من المستودع).

| النص | المصدر |
|------|--------|
| «A SPA cannot securely store credentials and therefore must be treated as a public client. Public clients should be required to use Proof Key for Code Exchange (PKCE).» | docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html |
| «Spring Authorization Server will not issue refresh tokens for a public client. We recommend the backend for frontend (BFF) pattern as an alternative to exposing a public client. See gh-297 for more information.» | نفس الصفحة — **النص الحامل لبوابة B للويب** |
| «The requireProofKey setting is important to prevent the PKCE Downgrade Attack.» | نفس الصفحة — متحقق منه منفذًا على main (S2) |
| «It is recommended that you use a robust client-side library supported by your single-page app framework...» | نفس الصفحة — اختيار مكتبة العميل مسؤولية الواجهة وفق إطارها |
| «java -Djarmode=tools -jar application.jar extract --layers --destination extracted» | spring-boot/reference/packaging/container-images/dockerfiles.html — منفذ حرفيًا في `Dockerfile` (مرحلة 0) |
| «If this is not enough, Spring Framework provides a ForwardedHeaderFilter for the servlet stack … You can use them in your application by setting server.forward-headers-strategy to FRAMEWORK.» | Spring Boot 4.1 reference — how-to/webserver «Running Behind a Front-end Proxy Server» (المرحلة A) |
| «Both kinds of headers are supplied by the client unless a proxy overwrites them … Only headers added by a proxy you control should reach the application.» | Spring Security 7.1.1 reference — features/exploits/http (حد الثقة: prod فقط خلف بروكسي موثوق يجرّد القيم الخارجية) (المرحلة A) |
| «When using SSL terminated at a proxy, this property should be set to false.» | جافادوك `TomcatServerProperties#getRedirectContextRoot` في مصدر Boot 4.1.1 الرسمي (المرحلة A) |
| PKCE/public-client/consent (RFC 9700 §2.1.1 و§4.14.2) + الادعاءات (RFC 9068 §2.2/§7.2.1.1، RFC 7519 §4.1.3) | rfc-editor.org — اقتباسات verbatim في `docs/security/auth-system-redesign-plan.md` §7 |
| «Public clients MUST use PKCE [RFC7636]...» / «Authorization servers MUST mitigate PKCE downgrade attacks...» | RFC 9700 §2.1.1 — المرجع المعياري فوق الوثائق، متحقق منفذًا |

**ملاحظة إصدارات صادقة:** الموقع الرسمي يعرض هذا الـ How-to تحت خط إصداراته المستقرة،
بينما المشروع يستهلك SAS عبر starter يديره Boot BOM (البند `spring-security-oauth2-authorization-server`
7.1.1 موثق في تعليق `pom.xml:91`). الإرشادات المستشهد بها (عميل عام/PKCE/BFF) إرشادات سياسة
بروتوكولية ثابتة عبر الخطوط، ومطابقتها الفعلية لسلوك 7.1.1 مُثبتة بالاختبارات S2/S3 على main —
فالاستشهاد ذو وجهين: **نص رسمي + اختبار أخضر محلي**.
