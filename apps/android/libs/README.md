# 厂商 aar（本地文件依赖）

| 文件 | 包名 | 版本 | minSdk | 备注 |
| --- | --- | --- | --- | --- |
| `protocollibrary_1.0.0.aar` | `com.geesatcom.protocollibrary` | 1.0.0 | 24 | 基础通信。classes.jar 里全是 `io/reactivex/rxjava3/**`，**它不传递依赖**，app 必须自己声明 rxjava 3.1.12 |
| `ephemerislibrary-release.aar` | `com.example.ephemerislibrary` | 1.0.9 | 24（manifest）| 星历。带 `jni/arm64-v8a/libdecodeutil.so`，所以 abiFilters 只能到 arm64；文档要求 minSdk 26，取大的那个 |

来源：时空道宇 2026-10 给的《星历及基础通信SDK资料包》，原始压缩包、28 页规范正文都在仓库内 `vendor/gt1005/`（那里还有一份厂商材料的保密说明）。
换版本时这三件事要重新看一遍，别只换个文件：

1. **`ephemerislibrary` 的 AndroidManifest 里有 `android:usesCleartextTraffic="true"`**，
   merger 会把它并进我们应用。`apps/android/src/main/AndroidManifest.xml` 用
   `tools:replace="android:usesCleartextTraffic"` 顶成 false，明文只走
   `res/xml/network_security_config.xml` 的域名白名单。
   这条由 `./gradlew :app:verifyCleartextPolicy` 在构建产物上把关，不靠人记住。
2. 两个 aar 自带的 `proguard.txt` 都是**空的**，一旦开 minify 反射入口会被裁掉，
   keep 规则在 `apps/android/proguard-rules.pro`。
3. 星历 SDK 必须**主线程调用、主线程回调**，而基础通信那套是 RxJava 异步 ——
   两者的线程模型相反，别在同一个调度器上一起接（附录 E.8）。

## 和〔架构 §附录 E.10 #9〕那条"JVM target 11"不一样的地方

编译目标我们用的是 **17**，不是 11。理由是 Gradle 不允许 `:core`(17) 和 `:app`(11) 混着来
（`Inconsistent JVM-target compatibility detected`），要么两边都降要么都不降；
而 aar 里是 Java 11 的字节码，**17 能加载 11**，反过来才有问题。所以这条约束的真实内容是"不低于 11"，
按 17 走满足它。哪天要降到 11，`:core` 和 `:simulator` 得一起降。
