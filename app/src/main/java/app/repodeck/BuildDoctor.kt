package app.repodeck

import android.content.Context
import kotlinx.serialization.json.JsonObject

/** Turns a raw failed-job log into a readable report with likely causes and concrete fixes. */
object BuildDoctor {
    private val ts = Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ")

    fun clean(raw: String): String = raw.lineSequence().joinToString("\n") { it.replaceFirst(ts, "") }

    private class Rule(val re: Regex, val en: String, val ar: String, val fixEn: String, val fixAr: String)

    private fun r(re: String, en: String, ar: String, fixEn: String, fixAr: String) =
        Rule(Regex(re, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)), en, ar, fixEn, fixAr)

    private val rules = listOf(
        r("""gradlew.*Permission denied""", "gradlew is not executable", "ملف gradlew غير قابل للتنفيذ",
            "Add a step before the build: `- run: chmod +x ./gradlew` (uploading from Android drops the executable bit).",
            "أضف خطوة قبل البناء: `- run: chmod +x ./gradlew` (الرفع من أندرويد يُفقد صلاحية التنفيذ)."),
        r("""gradlew: (No such file|not found)|No such file or directory.*gradlew""", "Gradle wrapper is missing", "ملف Gradle wrapper غير موجود",
            "Commit gradlew and gradle/wrapper/*, or use gradle/actions/setup-gradle with `gradle-version` and run `gradle assembleRelease`.",
            "ارفع gradlew ومجلد gradle/wrapper، أو استخدم gradle/actions/setup-gradle مع gradle-version ونفّذ `gradle assembleRelease`."),
        r("""GradleWrapperMain""", "gradle-wrapper.jar is missing or broken", "ملف gradle-wrapper.jar ناقص أو تالف",
            "Commit gradle/wrapper/gradle-wrapper.jar, or drop the wrapper and call `gradle` through setup-gradle.",
            "ارفع gradle/wrapper/gradle-wrapper.jar، أو تجاهل الـ wrapper واستدعِ `gradle` عبر setup-gradle."),
        r("""Unsupported class file major version|requires Java \d+|invalid source release|Unsupported Java|incompatible Java""", "Wrong Java (JDK) version", "إصدار Java (JDK) غير مناسب",
            "Set `java-version: 17` in actions/setup-java (Android Gradle Plugin 8.x needs JDK 17).",
            "اجعل `java-version: 17` في actions/setup-java (إضافة Android Gradle 8.x تحتاج JDK 17)."),
        r("""Minimum supported Gradle version is|requires Gradle \d""", "Gradle is older than the plugin needs", "إصدار Gradle أقدم من المطلوب",
            "Raise `gradle-version` in gradle/actions/setup-gradle (or update gradle-wrapper.properties) to the version in the message.",
            "ارفع `gradle-version` في setup-gradle (أو حدّث gradle-wrapper.properties) إلى الإصدار المذكور في الرسالة."),
        r("""SDK location not found""", "Android SDK path not found", "مسار Android SDK غير موجود",
            "Do not commit local.properties; GitHub runners already have the SDK. Remove the file from the repo.",
            "لا ترفع local.properties؛ أجهزة GitHub فيها الـ SDK جاهز. احذف الملف من المستودع."),
        r("""licen[sc]es? (have )?not (been )?accepted|Failed to install the following Android SDK packages""", "Android SDK licenses not accepted", "لم تُقبل تراخيص Android SDK",
            "Add `- run: yes | sdkmanager --licenses` before the build, or use android-actions/setup-android.",
            "أضف `- run: yes | sdkmanager --licenses` قبل البناء، أو استخدم android-actions/setup-android."),
        r("""Could not resolve|Could not find [\w.:-]+|Could not GET|Could not HEAD""", "A dependency could not be downloaded", "تعذّر تنزيل اعتمادية (Dependency)",
            "Check the group:artifact:version exists, add `google()` and `mavenCentral()` repositories (JitPack if needed), and re-run in case of a network blip.",
            "تأكد أن group:artifact:version موجود، وأضف مستودعات `google()` و`mavenCentral()` (و JitPack عند الحاجة)، ثم أعد التشغيل لو كانت المشكلة شبكة."),
        r("""^e: |Unresolved reference|Type mismatch""", "Kotlin compile error", "خطأ في ترجمة كود Kotlin",
            "Open the file:line shown in the error lines, fix the code or add the missing import/dependency.",
            "افتح الملف والسطر الظاهرين في أسطر الخطأ وصحّح الكود أو أضف الـ import/الاعتمادية الناقصة."),
        r("""error: cannot find symbol|error: package .* does not exist|error: incompatible types""", "Java compile error", "خطأ في ترجمة كود Java",
            "Fix the symbol/package named in the error, or add the missing dependency.",
            "صحّح الرمز/الحزمة المذكورة في الخطأ أو أضف الاعتمادية الناقصة."),
        r("""Manifest merger failed""", "AndroidManifest merge failed", "فشل دمج AndroidManifest",
            "Read the line after the message: typical causes are a missing `android:exported` (required when targetSdk >= 31) or duplicate entries.",
            "اقرأ السطر بعد الرسالة: الأسباب الشائعة غياب `android:exported` (مطلوب مع targetSdk 31+) أو عناصر مكررة."),
        r("""AAPT: error|Android resource linking failed|resource .* not found""", "Android resource error", "خطأ في موارد أندرويد",
            "A referenced drawable/string/style does not exist or is misspelled. Create it or fix the name.",
            "مورد (drawable/string/style) مشار إليه غير موجود أو مكتوب خطأ. أنشئه أو صحّح الاسم."),
        r("""Duplicate class""", "Duplicate classes from two dependencies", "كلاسات مكررة من اعتماديتين",
            "Exclude the duplicate module (`exclude group:`) or align both dependency versions.",
            "استبعد الوحدة المكررة (`exclude group:`) أو وحّد إصدارات الاعتماديتين."),
        r("""OutOfMemoryError|Java heap space|GC overhead limit|Daemon .* disappeared""", "Build ran out of memory", "نفدت الذاكرة أثناء البناء",
            "Add `org.gradle.jvmargs=-Xmx4g` to gradle.properties.",
            "أضف `org.gradle.jvmargs=-Xmx4g` إلى gradle.properties."),
        r("""Lint found|lintVital|lintAnalyze\w*.*FAILED""", "Android Lint blocked the release build", "فحص Lint أوقف بناء الإصدار",
            "Fix the reported lint errors, or relax it: `android { lint { abortOnError = false; checkReleaseBuilds = false } }`.",
            "أصلح أخطاء Lint المذكورة، أو خفّف الفحص: `android { lint { abortOnError = false; checkReleaseBuilds = false } }`."),
        r("""android\.useAndroidX|Jetifier""", "AndroidX is not enabled", "AndroidX غير مفعّل",
            "Add `android.useAndroidX=true` to gradle.properties.",
            "أضف `android.useAndroidX=true` إلى gradle.properties."),
        r("""compile against version \d+|requires .* compileSdk""", "compileSdk is too low", "قيمة compileSdk منخفضة",
            "Raise `compileSdk` (and targetSdk if needed) in app/build.gradle to the version in the message.",
            "ارفع `compileSdk` (وtargetSdk عند الحاجة) في app/build.gradle إلى الإصدار المذكور."),
        r("""Namespace not specified""", "Missing `namespace`", "غياب `namespace`",
            "AGP 8 requires `namespace \"your.package\"` inside the android {} block of each module.",
            "AGP 8 يتطلب `namespace \"your.package\"` داخل android {} لكل وحدة."),
        r("""Plugin .* was not found|Plugin with id .* not found""", "Gradle plugin not found", "إضافة Gradle غير موجودة",
            "Add `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }` in settings.gradle and check the plugin id/version.",
            "أضف `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }` في settings.gradle وتحقق من معرّف وإصدار الإضافة."),
        r("""Keystore was tampered with|password was incorrect|Cannot recover key|keystore.*not found""", "Signing key / keystore problem", "مشكلة في مفتاح التوقيع (Keystore)",
            "Check the KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD secrets, or remove the release signing config.",
            "تحقق من أسرار KEYSTORE_BASE64 و KEYSTORE_PASSWORD و KEY_ALIAS و KEY_PASSWORD، أو احذف إعداد توقيع الإصدار."),
        r("""Resource not accessible by integration""", "Workflow token lacks permission", "توكن الـ workflow بلا صلاحية كافية",
            "Add `permissions: contents: write` to the workflow and set Settings > Actions > Workflow permissions to Read and write.",
            "أضف `permissions: contents: write` للـ workflow واجعل Settings > Actions > Workflow permissions على Read and write."),
        r("""already_exists|tag .* already exists""", "Release tag already exists", "وسم الإصدار موجود بالفعل",
            "Use a unique tag (for example v1.1.${'$'}{{ github.run_number }}) or delete the old tag/release.",
            "استخدم وسمًا فريدًا (مثل v1.1.${'$'}{{ github.run_number }}) أو احذف الوسم/الإصدار القديم."),
        r("""ERESOLVE""", "npm dependency conflict", "تعارض اعتماديات npm",
            "Use `npm install --legacy-peer-deps` or align the conflicting package versions.",
            "استخدم `npm install --legacy-peer-deps` أو وحّد إصدارات الحزم المتعارضة."),
        r("""missing script: |Cannot find module""", "npm script or module missing", "سكربت أو وحدة npm ناقصة",
            "Add the script to package.json (`scripts`) or install the missing module with `npm install <name>`.",
            "أضف السكربت إلى package.json (`scripts`) أو ثبّت الوحدة الناقصة بـ `npm install <name>`."),
        r("""ModuleNotFoundError|No matching distribution found""", "Python package missing", "حزمة Python ناقصة",
            "Add the package to requirements.txt (check name and version).",
            "أضف الحزمة إلى requirements.txt (تحقق من الاسم والإصدار)."),
        r("""Unable to resolve action|Missing download info for actions""", "A GitHub Action name/version is wrong", "اسم أو إصدار Action غير صحيح",
            "Check the `uses: owner/action@version` line for typos and that the version exists.",
            "راجع سطر `uses: owner/action@version` من حيث الإملاء ووجود الإصدار."),
        r("""No space left on device""", "Runner ran out of disk space", "نفدت مساحة القرص على الجهاز",
            "Delete large build outputs earlier in the job or reduce what is built.",
            "احذف ملفات البناء الكبيرة مبكرًا أو قلّل ما يتم بناؤه."),
        r("""There were failing tests|Tests? failed|\d+ tests? completed, \d+ failed""", "Unit tests failed", "فشلت الاختبارات",
            "Open the failing test named above, fix the code or the test, or run the build without tests (`-x test`).",
            "افتح الاختبار الفاشل المذكور وأصلح الكود أو الاختبار، أو ابنِ بدون اختبارات (`-x test`).")
    )

    fun analyze(ar: Boolean, job: String, step: String, rawLog: String): String {
        val text = clean(rawLog)
        val lines = text.lines()
        val sb = StringBuilder()
        sb.appendLine(if (ar) "تقرير تشخيص فشل البناء" else "Build failure report")
        sb.appendLine("Job: $job")
        if (step.isNotBlank()) sb.appendLine((if (ar) "الخطوة الفاشلة: " else "Failed step: ") + step)
        sb.appendLine()
        val errRe = Regex("""(?i)\b(error|exception|failure)\b\s*[:!]""")
        val errLines = lines.filter { l ->
            l.contains("##[error]") || l.startsWith("e: ") || l.contains("What went wrong") || errRe.containsMatchIn(l)
        }.map { it.replace("##[error]", "").trim() }.filter { it.isNotEmpty() }.distinct().take(15)
        sb.appendLine(if (ar) "== أسطر الخطأ ==" else "== Error lines ==")
        if (errLines.isEmpty()) sb.appendLine(if (ar) "(لا يوجد)" else "(none found)")
        else errLines.forEach { sb.appendLine("\u2022 " + it.take(300)) }
        sb.appendLine()
        sb.appendLine(if (ar) "== الأسباب المحتملة والحلول ==" else "== Likely causes and fixes ==")
        val hits = rules.filter { it.re.containsMatchIn(text) }
        if (hits.isEmpty()) {
            sb.appendLine(
                if (ar) "لم يطابق أي نمط معروف. راجع أسطر الخطأ وآخر أسطر السجل بالأسفل."
                else "No known pattern matched. Read the error lines and the last log lines below."
            )
        } else {
            hits.take(6).forEachIndexed { i, h ->
                sb.appendLine("${i + 1}. " + (if (ar) h.ar else h.en))
                lines.filter { h.re.containsMatchIn(it) }.take(2).forEach { sb.appendLine("   > " + it.trim().take(220)) }
                sb.appendLine("   " + (if (ar) "الحل: " else "Fix: ") + (if (ar) h.fixAr else h.fixEn))
                sb.appendLine()
            }
        }
        sb.appendLine(if (ar) "== آخر أسطر السجل ==" else "== Last log lines ==")
        lines.filter { it.isNotBlank() }.takeLast(25).forEach { sb.appendLine(it.take(300)) }
        return sb.toString()
    }

    suspend fun diagnose(ctx: Context, base: String, jobs: List<JsonObject>): String {
        val failed = jobs.firstOrNull { it.s("conclusion") == "failure" }
            ?: jobs.firstOrNull { it.s("conclusion").isNotEmpty() && it.s("conclusion") != "success" && it.s("conclusion") != "skipped" }
            ?: return ctx.getString(R.string.diag_none)
        val step = failed.list("steps").firstOrNull { it.s("conclusion") == "failure" }?.s("name").orEmpty()
        val log = Graph.api.send("GET", "$base/jobs/${failed.n("id")}/logs").body
        val ar = ctx.resources.configuration.locales[0].language == "ar"
        return analyze(ar, failed.s("name"), step, log)
    }
}
