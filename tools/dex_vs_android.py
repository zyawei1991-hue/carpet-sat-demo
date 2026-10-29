# -*- coding: utf-8 -*-
"""
核验：**这个 APK 里出现的每一个 java.* / javax.* 类名，android.jar 里到底有没有。**

为什么要这么一条脚本：V1.8 那个包在两台手机上点开就闪退，根因是 `core/ai/Providers.kt`
引了 JDK 11 的 `java.net.http.HttpClient` —— **Android 至今没有这个包**。而编译器不会报
（`:core` 是纯 JVM 模块，编译目标是 JDK 17），Android Lint 也不会报（`:app:lintDebug` 只分析
app 自己的源码，不看依赖模块的 Kotlin 源），单测和桌面彩排台全绿（都跑在 JDK 17 上）。
四个本该拦住它的地方全漏了，所以只能拿 APK 的字节去对厂商的 android.jar。

认法故意粗：type 描述符在 dex 里就是字面串，直接正则扫 `Lxx/yy;`，再只看 java/javax 开头的那些
（我们自己的类是 `Lcom/carpet/...`，Kotlin 是 `Lkotlin/...`，都不会被误报）。
内部类按外层查 —— `$` 后面的东西 android.jar 的顶层名单里本来就没有。

白名单只收**查过来源、确认运行期不加载**的那几条。加一条就要写下它从哪个库的哪条路径来、
为什么不会执行到；查不到来源的一律当缺。
"""
import re, sys, zipfile

TOKEN = re.compile(rb'L[a-zA-Z0-9_$/]+\;')

# java.lang.instrument.*：只在 classes10.dex 里出现，同一段字符串紧挨着
# `kotlinx.coroutines.debug.internal.ByteBuddyDynamicAttach` —— 那是 kotlinx-coroutines
# 的调试 agent 路径，要靠 -javaagent 挂 ByteBuddy 才会走到；手机上没有 agent，
# 这些类永远不被 verify/link，所以不会 NoClassDefFoundError。
WHITELIST = {
    'java.lang.instrument.ClassFileTransformer',
    'java.lang.instrument.Instrumentation',
}

def main(apk, jar):
    have = set()
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.endswith('.class'):
                have.add(n[:-6].replace('/', '.'))
    names = set()
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if not n.endswith('.dex'):
                continue
            b = z.read(n)
            for t in TOKEN.findall(b):
                s = t[1:-1].decode('ascii')
                if s.startswith('java/') or s.startswith('javax/'):
                    names.add(s)
            del b
    def outer(x):
        return x.split('$')[0].replace('/', '.')

    missing = sorted(x for x in names if outer(x) not in have)
    known = [m for m in missing if outer(m) in WHITELIST]
    unknown = [m for m in missing if outer(m) not in WHITELIST]
    print('APK 里出现的 java/javax 类：%d 个' % len(names))
    print('android.jar 里查不到的：%d 个（其中已查明来源、确认不加载的 %d 个）'
          % (len(missing), len(known)))
    for m in known:
        print('  跳过  L%s;' % m)
    for m in unknown:
        print('  缺    L%s;' % m)
    if unknown:
        print('>>> 这些在手机上会 NoClassDefFoundError，必须换掉')
    return 1 if unknown else 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1], sys.argv[2]))
