# 厂商两个 aar 自带的 proguard.txt 都是**空的**，所以一旦开 minify，
# 反射/回调进去的入口类就会被裁掉 —— 那是运行期才炸、且炸得很难看的一类问题。
# 现在 release 也还没开 minify（isMinifyEnabled=false），这里先备着，别等出事再补。
-keep class com.geesatcom.protocollibrary.** { *; }
-keep class com.example.ephemerislibrary.** { *; }
-dontwarn io.reactivex.**

# RxJava3 自己的规则
-keep class io.reactivex.rxjava3.** { *; }
