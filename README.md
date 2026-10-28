# Seal · 篆书识别 Android

原生 Kotlin 应用，包名 `com.cyeam.seal`，Android 9（API 28）及以上。使用提供的印章图片作为启动图标；Firebase 配置选用提供文件中的同包名客户端。

## 识别流程

- 从系统照片选择器导入图片，或调用系统相机拍照，无需申请全盘相册或相机权限。
- 本机解码并按 EXIF 方向载入原图，长边限制 1800 px；支持横排/竖排阅读顺序、深色/浅色/朱文分字。
- 自动分字后，在原图上同时显示全部字框，可拖动、调整大小、新增、删除、左右拆分、上下拆分和逐次撤销。原图支持缩放查看；每个字框须包含一个完整汉字。
- 点击“确认裁字并识别”即按全部字框自动裁图并逐张识别，无需再逐字确认。最多 80 个字框，每个请求通过 multipart 字段 `image` 提交一张字图到 `https://www.cyeam.com/mo/api/ocr?top_k=10`。
- 兼容网站的 JSON 包装、字符串 JSON、旧数组和 `matches` 格式。整字候选排在局部线索之前，组内按分数排序；分数仅代表相似度。
- 显示参考篆字及 Unicode 字形（支持 SVG）、来源和码位；以下拉列表选择候选，支持反馈采用或不正确、查字典、失败重试及取消。
- 最近 50 条候选列表保存在设备本地，可清空；不保存照片到历史记录。屏幕旋转及语言切换保留当前会话，进程被系统杀死后需重新选图。

识别模型、服务鉴权和服务端调试样本继续由现有网站负责。应用不内置上游模型密钥。请注意：网站现有 OCR 服务可能保存提交字图、识别结果和 IP 用于调试，提交按钮旁说明云端识别，完整数据说明可在设置中查看。

## 手写单字

点击“手写单字”，每次只书写一个完整篆字。画板有米字辅助线，提交时仅导出黑色笔迹和白底；整张画板作为一个字直接识别，不自动分字或进入字框编辑。每次落笔到抬笔算一笔，“撤销上一笔”每点一次移除最后一笔，支持连续撤销，也可清空重写；空白画板不能提交。当前手写草稿在屏幕旋转和语言切换时保留，进程退出后不保留。识别沿用篆书模型，不表示支持普通行草字的通用手写识别。

## 自动发现剪贴板图片

应用进入前台、重新获得焦点或收到剪贴板变化时检测图片。检测到可读取的图片后展示预览，用户确认才替换当前图片并进入本地分字，不会自动开始云端识别。文字和普通网页链接被忽略；同一条剪贴板记录只提示一次，识别或保存反馈期间延后提示。Android 10 及以上受系统焦点限制，回到 Seal 后才可读取，不能持续在后台监听。界面不增加粘贴按钮。

## 多语言与统计

完整界面资源包含英语、简体中文、繁体中文、日语、韩语、法语、德语、西班牙语、葡萄牙语和俄语。默认跟随系统，支持应用内切换与 Android 13+ 系统应用语言设置。

翻译源为 `tools/localize.py`，修改后运行 `python3 tools/localize.py` 生成各语言 `strings.xml`。翻译仍建议由对应母语用户验收。

Firebase Analytics 配置位于 `app/google-services.json`。记录导入、分字数量、识别开始/完成、候选展示/选择、采用/不正确、复制和安装入口事件。自定义参数仅包含计数和排名，不包含图片、识别文字、文件名或 URI。设置中可关闭统计；禁用广告 ID 采集和广告个性化信号。

设备验证 Analytics：

```sh
adb shell setprop debug.firebase.analytics.app com.cyeam.seal
# 在 Firebase Console 的 Analytics / DebugView 检查事件。
adb shell setprop debug.firebase.analytics.app .none.
```

## 构建

需要 JDK 17 或 21、Android SDK 35；Gradle Wrapper 已包含。将 SDK 路径写入本机 `local.properties`（不提交）：

```properties
sdk.dir=/your/Android/sdk
```

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 测试 APK: app/build/outputs/apk/debug/app-debug.apk
```

正式版本通过环境变量设置 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，保留同一签名密钥。未提供签名时 `assembleRelease` 仅生成 unsigned APK。

本地也支持优先读取 `SEAL_KEYSTORE_FILE`、`SEAL_KEYSTORE_PASSWORD`、`SEAL_KEY_ALIAS`、`SEAL_KEY_PASSWORD`，避免影响其他 Android 项目的签名。当前开发机的私有配置位于 `$HOME/.config/cyeam/signing/seal/environment.zsh`（权限 600），通过 `.zshenv` 自动加载到新终端；已打开的终端可手动执行：

```sh
source "$HOME/.config/cyeam/signing/seal/environment.zsh"
```

配置同时保存 `SEAL_RELEASE_KEYSTORE_BASE64`，供恢复 CI Secret 使用。私有配置和密钥均在仓库之外，不提交到 Git。


```sh
./gradlew :app:assembleRelease -PreleaseVersionName=1.0.0 -PreleaseVersionCode=1001
```

## 自动升级与发布

参考 Medicine 的实现，启动自动检查：

`https://www.cyeam.com/api/apps/seal/update?versionCode=<已安装版本>`

收到更新后使用 DownloadManager 在非计费网络后台下载，下载任务跨进程重启恢复。下载链接限定为同 HTTPS 站点的 `/api/apps/seal/download`，验证 appId、包名、递增 versionCode、过期时间、APK 大小、APK 实际包名/版本和当前签名。验证成功后展示安装按钮，经 Android 安装器确认后升级，不会静默安装。

**服务端尚需配置后才可在线升级**：参考 `deploy/app-updates.example.json`，把真实蒲公英短链接填入后，合并到 `cyeam_web/conf/app_updates.json` 的现有数组并部署网站；不要覆盖其他应用配置。服务端需设置 `PGYER_API_KEY`。本项目不会修改或部署参考仓库。

`.github/workflows/android-release.yml` 支持版本 tag / 手动发布：单元测试、签名 APK、蒲公英发布、GitHub Release。配置仓库 Secrets：

- `RELEASE_KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
- `PGYER_API_KEY`

`versionCode` 使用工作流运行号 + 1000；迁移或重建工作流时须确保高于所有已发布版本。Firebase Android 配置文件属于客户端公开配置；禁止将服务账户私钥或服务端密钥放入应用。

## 验证边界

单元测试覆盖横竖顺序、白文/朱文、空图、兼容响应解析、罕见字符、候选分组、字框拆分与撤销、手写笔画撤销，以及更新包名/版本/域名/路径/过期校验。模拟器可验证导入、分字、裁剪、切换语言和界面。真机相机、线上 Firebase DebugView、正式签名更新链路需要对应设备与发布配置后再验证。

## 初始版本验证（2026-10-28，保留历史记录）

- Debug APK 和开启 R8 的 Release unsigned APK 构建成功。
- 14 个单元测试全部通过；Debug Lint 无错误（19 条提示，主要为依赖升级建议及代码风格提示）。
- 10 种语言的全部 83 条文案和格式参数校验通过。
- Android 16 模拟器：启动、系统照片选择器导入样例、自动分出单字、打开裁剪编辑器、提交线上识别、显示 10 个候选及参考字形已验证。样例结果仅用于验证接入流程，不代表准确率。
- 应用内切换日语成功，切换时保留原图、单字框与识别结果；重装最新 Debug 后日语偏好仍保留。
- Firebase 本机事件日志确认识别开始、结果展示、识别完成事件，Google Analytics 上传返回 HTTP 204；未人工查看 Firebase Console。
- 线上更新接口当前返回 app_not_found；未进行真实升级安装，也未部署任何服务。

## 飞书发布通知

在 GitHub 仓库 Actions Secrets 设置 `LARK_WEBHOOK_URL`（自定义群机器人 Webhook）。发布工作流结束时发送成功、失败或取消通知：包含版本、构建详情，以及已成功发布的蒲公英 / GitHub 下载页。通知失败会显示在 Actions 日志中，不改变 APK 发布结果。Webhook 不写入仓库或日志。

通知脚本验证：`python3 -m unittest discover -s scripts/tests -v`。机器人如开启关键词或签名校验，须相应配置；当前消息包含应用名“Seal 篆书识别”。

## 识别反馈

识别接口返回的 `feedback_token` 仅用于对应的本次识别。点击“采用此字／都不对”后，App 向 `/mo/api/ocr/feedback` 提交选择；图片关联、等待异步上传、候选校验和 ImageKit 扩展信息更新由服务端统一处理。服务端保存成功后才确认选择，失败保留重试入口。旧服务端未返回凭证或凭证过期时提示重新识别。凭证仅保存在当前识别状态中，不写入本地历史或统计。需要部署支持反馈的新服务端后才可线上使用。

## 原图编辑与手写功能本地验证（2026-10-28）

- Debug 构建、22 个单元测试、Debug Lint 及 10 种语言的 107 条文案校验通过。
- Android 16 模拟器：三笔书写后，连续点击撤销，笔画数依次为 2、1、0；空白时提交按钮禁用。
- 模拟器手写“工”整图提交，线上返回候选下拉列表；统计记录单字识别开始和完成（glyph_count=1），没有手写分字事件。此例仅验证流程，不代表准确率。
- 原图字框在模拟器中左右拆分、上下拆分后为 3 框，连续撤销恢复为 1 框。
- 此次改动尚未发布。
