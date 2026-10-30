import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

/**
 * 构建口径（附录 E.10 #9）。三条都不是"顺手配的"，每条背后都是一个会出事的理由：
 *
 *  - **minSdk 26**：基础通信 aar 自己写 24，星历 aar 要 26 —— 取大的那个。
 *    装在 24/25 上不是"星历功能没了"，是 `libdecodeutil.so` 那层直接起不来。
 *  - **两个 aar 走本地文件**，没有 maven 坐标可解，所以 RxJava3 必须我们显式声明：
 *    aar 的 classes.jar 里全是 `io/reactivex/rxjava3/...`，它不传递依赖，漏了就是一次 NoClassDefFoundError。
 *  - **明文只按域名放行**：星历那个 aar 的 manifest 里带着 `usesCleartextTraffic="true"`，
 *    merger 会把它并进我们应用，所以要用 `tools:replace` 顶回去（见 AndroidManifest.xml），
 *    白名单本身在 `res/xml/network_security_config.xml`。
 */
android {
    namespace = "com.carpet.satdemo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carpet.satdemo.app"
        // 星历 SDK 要 26（基础通信 SDK 只要 24），装到 24/25 上是 native 库起不来，不是少个功能
        minSdk = 26
        targetSdk = 34
        versionCode = 11005
        versionName = "1.10.5"

        // 星历 aar 只带 arm64-v8a 的 libdecodeutil.so。真机都是 arm64，
        // 但别在这里加 abiFilters 之外的假设：换了 SoC 就得回来改
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        /**
         * **普通签名**。〔SDK文〕没要求平台签名，我们的 App 也不该要：platform 签名要 AOSP 的
         * 密钥，出了这条链子就等于把系统权限交给一个 demo。密钥从 `keystore.properties` 读，
         * 那个文件不进版本库（见下面的 verifyReleaseSigning）。
         */
        create("release") {
            rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
                val p = Properties().apply { f.inputStream().use { load(it) } }
                p.getProperty("storeFile")?.let { storeFile = rootProject.file(it) }
                p.getProperty("storePassword")?.let { storePassword = it }
                p.getProperty("keyAlias")?.let { keyAlias = it }
                p.getProperty("keyPassword")?.let { keyPassword = it }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    /**
     * §5.2 的技术选型：**Jetpack Compose + Material 3，深色为默认**（车载夜间是主场景）。
     * 之前这一版 App 是纯代码搭的 View —— 那是演示控制台的形状，不是产品的形状。
     * Compose 编译器扩展的版本号**和 Kotlin 版本绑死**（1.9.24 → 1.5.14），
     * 升 Kotlin 必须一起来这里改，否则报的是"compose compiler version not compatible"这种最难猜的错。
     */
    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }

    /**
     * **截图 harness**（V1.10.1）。这台机器上没有手机也没有 emulator，所以"UI 乱不乱"这句
     * 我一直只能靠数学说 —— 而 §5.2 里"一屏密度""看着挤不挤"那半条恰恰是眼睛的活。
     * Robolectric 的 NATIVE 图形模式能在 JVM 里把 Compose 真画出来（不是影子绘制），
     * 于是 `captureToImage()` 出一张真 PNG。要它就必须带上资源：合并后的 manifest 与 R 都由它读。
     */
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        // 白名单还没填真域名，别把"明文"这件事糊过去：让它继续报错，报给我们自己看
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core"))

    // 厂商给的两个 aar：没有 maven 坐标，只能按文件依赖（E.2 那两个包名，`com.example.*` 是原样）
    implementation(files("libs/protocollibrary_1.0.0.aar", "libs/ephemerislibrary-release.aar"))
    // aar 不传递依赖。protocollibrary 的 classes.jar 里全是 rxjava3，漏了它运行期才炸
    implementation("io.reactivex.rxjava3:rxjava:3.1.12")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // §5.2 选型：Compose + Material 3。BOM 统一版本，别在单个 artifact 上写版本号（写了也不生效）
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // 图标一律用官方 Material 图标集，不再自己画 vector ——
    // 自绘的那批既没有官方网格对齐，也撑不起"卫星/围栏/待补发"这些语义
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // 只在 debug 变体里注册一个空 Activity 供 Compose 测试规则宿主使用（release 包不受影响）
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    /**
     * 截图那一套走的是 **JUnit 4**（`AndroidJUnit4` + `createComposeRule` 两个都是 4 的 Rule），
     * 而本模块跑在 JUnit Platform 上，所以必须挂 vintage 引擎，否则测试会被"发现不到"而静默跳过 ——
     * 静默跳过的测试看起来和全绿一模一样，是最坏的一种假阳性。
     */
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register("verifyCleartextPolicy") {
    group = "verification"
    description = "在 merger 产物上确认：明文默认关闭，只有白名单域名例外"
    doLast {
        val merged = fileTree(layout.buildDirectory.asFile.get())
            .matching { include("**/merged_manifest/debug/**/AndroidManifest.xml") }
            .files.firstOrNull()
            ?: error("没找到 merger 输出的 manifest —— 先跑一次 :app:processDebugMainManifest")
        val xml = merged.readText()
        require(!xml.contains("""usesCleartextTraffic="true"""")) {
            """合并后的 manifest 里明文是开的。星历那个 aar 自己带了 usesCleartextTraffic="true"，""" +
                """被它顶掉就等于把"只按域名放行"这条整体作废（E.10 #9）"""
        }
        require(xml.contains("networkSecurityConfig=")) {
            "合并后的 manifest 没挂 networkSecurityConfig，白名单文件成了没人读的摆设"
        }
        require(xml.contains("""minSdkVersion="26"""")) {
            "minSdk 被谁改回去了：星历 SDK 要 26，装在 24/25 上是 libdecodeutil.so 起不来"
        }
        logger.lifecycle("明文策略：默认关闭 + 白名单文件生效，minSdk 26 ✅")
    }
}

// 出包顺带就把这条查了，别指望人记得单独跑
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy("verifyCleartextPolicy") }

/**
 * **这条是给 V1.8 那次闪退补的**。根因是 `:core` 里用了 JDK 11 的 `java.net.http.HttpClient`
 * —— Android 没有这个包，而它在 `Application.onCreate` 的装配链上，一点开就 `NoClassDefFoundError`。
 * 编译、`:core` 单测、桌面彩排台、`:app:lintDebug` 四层全拦不住（前三层跑在 JDK 17 上，
 * lint 不分析依赖模块的 Kotlin 源）。**这道鸿沟只在"打完包"之后才存在**，所以尺子必须挂在出包之后，
 * 拿 APK 的字节去对厂商的 android.jar —— 靠人记得单独跑，就一定会有那天不跑。
 */
val verifyApkAndroidCompat by tasks.registering {
    group = "verification"
    description = "对 APK 字节核 android.jar：包里出现 Android 没有的 java.*/javax.* 就红"
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        require(apk.exists()) { "还没有 debug 包可查：先跑 :app:assembleDebug" }
        val jar = File(android.sdkDirectory, "platforms/android-${android.compileSdk}/android.jar")
        require(jar.exists()) { "找不到 $jar —— 它是这条核验的对照组，缺它就是没查" }
        val script = rootProject.file("tools/dex_vs_android.py")
        // 没 python 的机器（CI、别人笔记本）上不能把出包顶死，但必须喊出来，不能让"跳过"长得像"通过"
        val py = listOf("python", "python3", "py").firstOrNull { name ->
            runCatching {
                project.exec {
                    commandLine(name, "--version")
                    standardOutput = ByteArrayOutputStream()
                }
            }.isSuccess
        }
        if (py == null) {
            logger.warn("!! 这台机器上没有 python，APK↔android.jar 的核验**没跑**。别把它当成过了。")
        } else {
            project.exec { commandLine(py, script.absolutePath, apk.absolutePath, jar.absolutePath) }
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy("verifyApkAndroidCompat") }

/**
 * 出包前把"不许平台签名"这条钉住：`./gradlew :app:verifyReleaseSigning assembleRelease`。
 * 它现在只是注释和约定，而约定这东西在赶工那天最容易破 ——
 * 有人为了拿系统权限去刷 `platform.x509.pem`，改的还是一行配置，review 时最难看出来。
 *
 * 故意不挂在 `check` 上：`check` 要在没密钥的机器（CI、别人笔记本）上也能跑。
 */
val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "确认 release 用的是普通 keystore，不是 AOSP 平台密钥"
    doLast {
        val cfg = android.signingConfigs.getByName("release")
        val file = cfg.storeFile
        require(file != null) {
            "release 没配 keystore.properties —— 宁可不出包，也别出一个用 debug 密钥签的\"正式版\""
        }
        val path = file.absolutePath.lowercase()
        require(!path.contains("platform")) {
            "签名密钥路径里出现 platform：这个 App 不要 AOSP 平台密钥（E.10 #9 的\"普通签名\"）"
        }
        require(file.exists()) { "keystore 不存在：$file" }
        logger.lifecycle("release 签名：${file.name}（普通 keystore）")
    }
}
