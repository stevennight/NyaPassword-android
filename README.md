# NyaPassword Android（android）

NyaPassword 的 Android 客户端：Kotlin + Jetpack Compose（Material 3，跟随系统浅色 / 深色）界面，加密、同步、合并、网址匹配、TOTP、通行密钥等全部由共享的 Rust 核心完成（`../common/crates/npw-ffi`，经 UniFFI 生成 Kotlin 绑定，Gradle 用 cargo-ndk 构建）。设计见 [../common/docs/设计方案.md](../common/docs/设计方案.md)（§4.5 本地解锁、§10.5 Android、§11 自动填充测试矩阵），进度见 [../common/docs/开发计划.md](../common/docs/开发计划.md)（M4）。

- `applicationId`：`app.nya.password`；Android 8.0（API 26）起，`targetSdk` 36；只打包 arm64-v8a 和 x86_64。
- 状态：功能已实现，**尚未在真机、国内 ROM 和具体浏览器上测试**（见文末）。

## 功能

| 模块 | 内容 |
|---|---|
| 登录 / 注册 | 服务器地址、账号、主密码、Secret Key；可扫描紧急恢复包二维码（内容 `{"v":1,"server","login","secret_key"}`，zxing 内置扫码，不依赖 Google Play 服务）；注册后显示紧急恢复包（含二维码） |
| 解锁 | 主密码（离线可用）；指纹 / 面容（BiometricPrompt + Android Keystore AES-GCM 密钥，`setUserAuthenticationRequired`、新增生物特征即失效，包装核心的 quick unlock key）。只在本次进程里输入过主密码、且距上次输入主密码不超过 14 天时提供；密钥失效时清除并改用主密码 |
| 自动锁定 | 空闲 N 分钟（可设 1 分钟–4 小时或从不）、屏幕关闭时（可关）、进程重启 |
| 密码库 | 搜索（核心搜索，支持拼音 / 首字母）、筛选（全部、收藏、冲突、保险库、分类、归档、回收站）、下拉同步 |
| 条目详情 | 复制（Android 13+ 标记敏感内容 `EXTRA_IS_SENSITIVE`，更早版本 `android.content.extra.IS_SENSITIVE`；90 秒后清除）、显示 / 隐藏、TOTP 实时验证码与倒计时、多行密文按行复制、网址、通行密钥、附件下载（系统文件选择器）、历史版本查看 / 恢复、密码历史、同步冲突逐项处理、回收站恢复 / 永久删除 |
| 编辑 | 全部模板、添加字段（含多行密文和预设：恢复码、密保问题等）、分区、网址与匹配方式、标签、备注、生成器、附件上传；**条目内容按 JSON 树编辑，新版本客户端写入的未知字段原样保留** |
| 其他页面 | 密码生成器（随机 / 易记口令 / PIN）、安全检查（弱密码、重复、可开两步验证、长期未改、http）与健康检查、设置（自动锁定、生物识别、设备列表与移除、紧急恢复包、修改主密码、保险库、退出、关于与更新） |
| 同步 | 回到前台时、前台每 5 分钟、编辑后，以及前台时连接事件 WebSocket（OkHttp）收到通知即同步；设备被移除时自动退出 |
| 自动填充 | AutofillService（不使用无障碍服务）：解析 AssistStructure（autofillHints、HTML autocomplete / type、inputType、中英文关键词：账号 / 用户名 / 手机号 / 邮箱 / 密码 / 验证码…）；浏览器按页面域名匹配（浏览器先按签名证书核对，见下），应用按 `androidapp://包名` 匹配；锁定时返回“点按解锁”，解锁后直接给出候选；Android 11+ 输入法内联建议；保存新密码 / 更新密码；没有匹配时“搜索 NyaPassword”，选中后把应用（包名 + 签名证书 SHA-256）记到条目里，之后对 `androidapp://` 网址核对调用方签名证书，防冒名应用 |
| 凭据提供程序 | Android 14+ CredentialProviderService（androidx.credentials）：密码和通行密钥的登录与注册。选择条目后先验证身份（生物识别或主密码，即使已解锁），再由核心签名 / 创建。特权浏览器（Chrome、Edge、Brave、Samsung Internet 等，名单 `app/src/main/assets/privileged_browsers.json` 摘自 Google 的 [privileged apps](https://www.gstatic.com/gpm-passkeys-privileged-apps/apps.json)）用浏览器给出的 origin（有 clientDataHash 时由浏览器生成 clientDataJSON）；普通应用用 `android:apk-key-hash:<证书 SHA-256>`，并要求网站的 Digital Asset Links 授权该应用 |
| 设置引导 | 打开系统自动填充设置、凭据管理器设置；Chrome（“使用其他服务自动填充”）和 MIUI / HyperOS、ColorOS、OriginOS、HarmonyOS 4 的设置路径 |
| 自更新 | 查询 GitHub Releases（仓库由构建时的 `NPW_UPDATE_REPO` 决定，默认占位 `example/NyaPassword-android`），下载 `NyaPassword-Android_<版本>.apk`，校验 `.sha256`（必须有）和 APK 签名证书（必须与已安装的相同），再交给系统安装程序 |
| 安全 | 所有界面 `FLAG_SECURE`；`allowBackup=false` 且数据提取规则全部排除；本地副本、包装后的设备密钥都在 no-backup 目录 |

## 架构

```
app/src/main/java/app/nya/password/
├─ NpwApp.kt            进程级 Vault、前后台与熄屏监听
├─ MainActivity.kt      登录 / 锁定 / 主界面（底部导航；宽屏用侧边导航）
├─ core/                Vault（核心客户端、状态、同步、自动锁定、事件 WebSocket）、Keys（设备密钥、生物识别密钥）、
│                       Models / ItemDoc（JSON 视图与保留未知键的编辑）、Origins（签名证书、apk-key-hash、DAL）、Updater、Prefs / Clipboard
├─ ui/                  Compose 界面（密码库、详情、编辑、生成器、安全、设置、设置引导、解锁）
├─ autofill/            FieldClassifier（纯 Kotlin 规则）、StructureParser、NpwAutofillService、Fill / Saver、AutofillActivity
└─ credentials/         NpwCredentialService、CredentialActivity、Callers（调用方 origin 判定）
build/generated/uniffi/ 由 libnpw_android.so 生成的 Kotlin 绑定（app.nya.password.ffi）
```

核心接口（`npw-ffi`）：一个 `NpwClient` 对象（SQLite 本地副本在 `noBackupFilesDir/replica.sqlite3`），所有调用阻塞、在 IO 线程调用；条目、过滤条件、报告等结构化数据以 JSON 字符串传递（与 `npw-wasm` 给网页版的形状相同），错误带核心的错误码（`locked`、`wrong_password`、`network`…）。

## 构建

需要：JDK 17、Android SDK（compileSdk 36）、NDK `28.2.13676358`（与 `app/build.gradle.kts` 中 `ndkVersionPinned` 一致）、Rust（`rustup target add aarch64-linux-android x86_64-linux-android`）、`cargo install cargo-ndk`。`common` 仓库要检出在旁边（`../common`）。

```powershell
.\gradlew assembleDebug                         # cargo-ndk 构建核心 → 生成 Kotlin 绑定 → 编译
.\gradlew lintDebug testDebugUnitTest            # lint 与 JVM 单元测试
cargo test --workspace                          # Rust 部分（核心的测试在 common：cargo test -p npw-ffi）
```

Gradle 任务顺序：`cargoBuild`（`cargo ndk -t arm64-v8a -t x86_64 --platform 26 build --release --lib`，输出到 `app/build/rustJniLibs`）→ `uniffiBindings`（`cargo run -p uniffi-bindgen -- generate --library …/arm64-v8a/libnpw_android.so --language kotlin`，输出到 `app/build/generated/uniffi/kotlin`）→ 编译。核心总是按 release 构建（debug 版 Argon2 太慢）。

## 发版

版本只在 `VERSION`（`scripts/release.ps1` 同步 `Cargo.toml`），`versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH`。

```powershell
.\scripts\new-android-keystore.ps1                      # 一次性：签名密钥放在 ..\signing\nya-password-android-release.jks（不入库）
.\scripts\new-android-keystore.ps1 -SetGitHubSecrets    # 存为 Actions secret（仓库取自 origin，或用 -Repo OWNER/NyaPassword-android）
.\scripts\release.ps1 0.1.0                             # 写 VERSION / COMMON_REF、提交、打 tag；再 git push origin HEAD v0.1.0
```

推送 tag 后 `release.yml` 恢复签名密钥（`ANDROID_KEYSTORE_BASE64` 等四个 secret）、构建 `NyaPassword-Android_<版本>.apk`、`apksigner verify`、生成 `.sha256` 并发布。CI 检出私有的 common 仓库用 `COMMON_DEPLOY_KEY`。签名密钥丢失后已安装的应用无法升级，务必备份。

## 测试情况

- 已验证：`cargo test -p npw-ffi`（含对本机服务端的端到端测试）、`cargo ndk` 两个 ABI 构建、`gradlew lintDebug testDebugUnitTest assembleDebug`；JVM 单元测试覆盖表单字段识别、JSON 树编辑保留未知键、版本比较与发布解析、应用 origin / 特权浏览器 / Digital Asset Links、恢复包二维码。
- **未测试**：真机、模拟器、国内 ROM（MIUI / HyperOS、ColorOS、OriginOS、HarmonyOS 4）、具体浏览器（Chrome、Edge、国内浏览器的兼容模式）、各 App 的自动填充、通行密钥在真实网站上的注册与登录、输入法内联建议、自更新安装流程。上线前按设计方案 §11 的测试矩阵逐项实测。
