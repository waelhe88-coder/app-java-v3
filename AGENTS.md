# Project-Specific Rules — Backend Java (V3)

Follows the global AGENTS.md at `~/.config/opencode/AGENTS.md`.
This file adds project-specific conventions.

## 0. الإجبار النظامي (System-Mandatory) — شرط مسبق قبل كل إجراء (جبري)

> **هذا القسم جبري (enforced) بأمر المستخدم المستمر: «اعتمد فهمك للبنية في كل إجراء قادم» و«لا تقل فقط بل اجعله جبرياً». لا يُتخَطَّى.** مقتضاه: أي إجراء (تعديل ملف، أمر bash يغيّر شيئاً، إنشاء/دفع/دمج PR، قرار معماري) **لا يبدأ** حتى يُحمَّل الفهم النظامي ويُسنَد إلى الملف الحاكم — وإلا توقف تلقائياً. (هذا القسم مكرر في `skill protocol-enforcer` §0.)

### 0.1 التسلسل الإجباري قبل أي إجراء (Mandatory load order)
```text
1. SYSTEM.md     — الخريطة المرجعية للآلية (§1-§13) + خريطة التعمق §10
2. PROJECT_MAP.md — الحالة الفعلية (ما دُمج، ما مفتوح، ما معلّق)
3. الملف الحاكم للمهمة الحالية — من §11/خريطة المرجع:
     - auth redesign          → docs/security/auth-system-redesign-plan.md
     - client bootstrap       → docs/security/oauth2-client-bootstrap-spec.md
     - client/hosting plan    → docs/security/client-hosting-strategy-plan.md
     - feature expansion      → docs/feature-expansion-roadmap.md
     - إصلاح أخر               → الملف المعني + CODING_STANDARDS.md
4. ثم الشجرة المستهدفة من خريطة §10
```
قاعدة: «الملف الحاكم» = المستند الذي يملك قرارات المهمة. لا يقبَل إجراء بلا إسناد صريح إليه.

### 0.2 قبل أي إجراء، أعلن بنص صريح (لا ضمنياً)
```text
[الملف الحاكم]: <مسار الملف الذي يحكم هذه المهمة>
[النواة المعمارية]: <أي طبقة من SYSTEM.md §1-§9 تمسها (بناء/إقلاع/وحدات/أمن/بيانات/إعدادات)>
[البند من المصفوفة §14.2]: <11 بنود الاتساق أو بوابة A/B/C إن كانت مهمة>
[أثر على الحدود]: <Modulith/SPI/أحداث أم لا>
[دين: لا/نعم - موثق]
```
أي إجراء لا يحمل هذا الإعلان **يُتوقف تلقائياً** قبل التنفيذ.

### 0.3 مبدأ «الباك اند مرساةً» — نطاق الحرية المنضبطة
من خطة العملاء والاستضافة (§1): النواة (Boot/Modulith/SAS/Java) مغلقة على main؛ أي عميل مستقبلي = **إعداد + اختبار** عبر مسار `RegisteredClientRepository.save` (env→DB) + CORS env + اختبار S2/S3 للصف الفعلي — **صفر كود/وحدة/اعتماديات جديدة**. لا عودة عن D6 (مفاتيح prod fail-fast) ولا عن INV-2 (إعدادات صريحة كاملة).

### 0.4 البوابات المفتوحة لا تبدأ بلا كلمة المستخدم
- بوابة B (تقنية العميل ونمطه) → بوابة C (جهة الاستضافة)، بالترتيب (§6 خطة العملاء والاستضافة)، كلٌّ بكلمة صريحة. لا تفترض إجابة.

## Additional Rules

- **CI**: Run `mvn clean verify -pl <module>` before pushing
- **Testing**: All integration tests MUST have `@ActiveProfiles("test")` and `@Testcontainers(disabledWithoutDocker = true)` or `@Container`
- **Flyway**: Any schema change = new V{number} migration file. Never modify existing migrations
- **Envers**: All domain entities MUST have `@Audited`
- **`@ConfigurationProperties` binding**: a nested record section with absent keys binds to `null` (official constructor-binding rule); prime the component with an empty `@DefaultValue` to always bind a non-null defaulted instance (Spring Boot reference — Features › Externalized Configuration › Constructor binding)
- **Protocols enforced**: Planning → Execution → Surgical Editing (see global AGENTS.md)
