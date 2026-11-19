# 不装 Gradle / AGP 的 Compose 构建工具链

`build-compose.sh` 用的是手工链路：自己拉 AAR、自己合并资源、自己生成 `R.java`、自己调 `kotlinc`，
最后一次性交给 `d8`。这里放的就是那几个脚本，以及把它们摆起来的顺序。

为什么不用 Gradle：Android Gradle Plugin 需要 `aapt2`，而 `aapt2` 只有 x86_64 官方包；
在 arm64 的 Linux 上要么跑 qemu（依赖 `libc6:amd64`，多数容器开不了 multiarch），要么放弃 AGP。
`aapt`（v1）+ `d8` + `apksigner` 在 Debian/Ubuntu 的 arm64 仓库里都有，所以走这条。

## 需要什么

| 东西 | 怎么来 |
| --- | --- |
| JDK 21 | 发行版 `openjdk-21-jdk-headless`；解包版注意 `lib/security/cacerts` 可能是悬空链接 |
| `aapt`、`zipalign`、`apksigner` | `apt install aapt zipalign apksigner` |
| `android.jar` | `platforms/android-34/android.jar`（`aapt` 与 `javac` 都要） |
| `d8` | build-tools 34 里的 `lib/d8.jar`，`java -cp d8.jar com.android.tools.r8.D8` |
| R8 8.7+ | build-tools 自带的 8.2 会在 Kotlin 类上抛 NPE，换成 `com.android.tools:r8:8.7.18` |
| kotlinc | `kotlin-compiler-embeddable-2.0.21.jar` 那一组（CLI 版会和 Compose 插件冲突） |
| Compose 编译器插件 | `kotlin-compose-compiler-plugin-embeddable-2.0.21.jar` |

Compose 运行时和 AndroidX 的版本：Compose **1.7.7**、`chrisbanes/haze` **1.3.1**（更低的 Compose 会和 haze 打架）。

## 顺序

一条命令就能把下面 1、2 两步做完（CI 里就是跑它）：

```bash
bash compose/fetch-tools.sh           # → libs/ + jars/ + unpacked/ + 四个工具 jar
```

手动分步：

```bash
# 1. 拉依赖
python3 compose/resolve.py            # → libs/（约 120 个 aar/jar，52MB）
python3 compose/unpack.py             # → jars/（classes.jar）+ unpacked/（res、R.txt）

# 2. 解包 aar
#    每个 aar 解到 unpacked/<名字>/，classes.jar → jars/<名字>.jar

# 3. 合并资源
python3 compose/merge_res.py          # app/res → res-app/，并把各 aar 的 values* 合并进 libs_merged.xml

# 4. 资源表 + 每库 R.java
#    先用 aapt 生成 app 的 R.java（-J 参数），再：
python3 compose/gen_r.py              # 按各库 R.txt 生成 androidx.*.R，喂给 kotlinc

# 5. 依赖去重
python3 compose/fatjar.py             # jars/*.jar → libs-fat.jar

# 6. 编译 + 打包
bash build-compose.sh                 # aapt/缓存 → javac+kotlinc → d8 → zip 打 dex → zipalign → apksigner
```

脚本里的路径都是相对脚本自己算出来的（`HERE` / `COMPOSE`），换机器不用改；
外部工具靠 `ANDROID_SDK`、`BUILD_TOOLS`、`JAVA_HOME` 或 PATH 找，见 `build-compose.sh` 开头。
`gen_r.py` 有个坑：`APP_R` 必须指向**本次构建**生成的 `R.java`，指向旧的会导致新库没有 `R`
（表现为 `R.drawable.xxx` 找不到，比如 haze 的 `haze_noise`）。

## 已知坑

- `Modifier.blur` 在 API < 31 静默不生效；haze 1.3.1 的 `blurEnabled()` 是 `SDK_INT >= 32`。
  两者都要有「预糊位图」兜底。
- Compose 里给卡片垫全屏背景要用 `matchParentSize()`，用 `requiredSize(屏幕尺寸)` 会把卡片撑成整屏。
- `d8` 在 64K 方法上限下会自动切多 dex，必须循环打包全部 `classes*.dex`，只 copy 第一个会漏类。
- 匿名内部类、`Comparator` 这类泛型嵌套类会让 d8 崩（`NullPointerException: Cannot invoke "String.length()"`），
  一律用「具名嵌套类且 static」。
