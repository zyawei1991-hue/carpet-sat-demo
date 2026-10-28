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
        versionCode = 1
        versionName = "0.1.0"

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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
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
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
