---
name: tina-data-security-storage
description: TinaIDE 数据层、安全、权限、路径、敏感信息和文件分享指南。用于修改 Room 数据库、迁移、DataStore/SharedPreferences、ProjectPaths、PathValidator、API key 存储、server HMAC、崩溃日志隐私或 Android 权限。
---

# TinaIDE 数据、安全与存储

## 先读文件

- `core/database/src/main/java/com/wuxianggujun/tinaide/database/user/UserContentDatabase.kt`。
- `feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/bookmark/db/BookmarkDatabase.kt`。
- `core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ProjectPaths.kt`。
- `core/storage/src/main/java/com/wuxianggujun/tinaide/storage/TinaFileProvider.kt`。
- `core/storage/src/main/java/com/wuxianggujun/tinaide/storage/ExternalFileIntents.kt`。
- `core/storage/src/main/java/com/wuxianggujun/tinaide/storage/db/StorageDatabase.kt`。
- `core/security/src/main/java/com/wuxianggujun/tinaide/core/security/PathValidator.kt`。
- `core/config/**`、`core/logging/**`。
- `app/src/main/AndroidManifest.xml`。
- `app/src/main/res/xml/network_security_config.xml`、`backup_rules.xml`、`data_extraction_rules.xml`、`file_paths.xml`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/channel/AiChannelApiKeyStore.kt`。
- `feature/ai/src/main/java/com/wuxianggujun/tinaide/ai/config/AiPreferences.kt`。

## 数据层事实

- `UserContentDatabase` 是主要用户内容 Room 数据库，当前有显式 migrations，例如 2->3、3->4、4->5。
- `BookmarkDatabase` 是编辑器书签数据库。
- `StorageDatabase` 独立管理项目位置映射，数据库名为 `tinaide_storage.db`，当前有 `project_locations` 迁移和唯一索引约束。
- `UserContentDatabase` 与 `BookmarkDatabase` 当前 `exportSchema = false`；新增迁移前先核对现有策略和测试。
- AI 会话通过 `ConversationRepository` 使用 `UserContentDatabase` 的 conversation/message DAO。
- 配置类同时存在 Room、DataStore、SharedPreferences 和加密 SharedPreferences，用前先搜索既有 store。

## 安全与路径事实

- 项目、日志、PRoot、模板等路径应优先通过 `ProjectPaths` 获取。
- 路径输入校验优先复用 `PathValidator` 和相关 validator。
- BYOK API key 使用 `AiChannelApiKeyStore`，基于 `EncryptedSharedPreferences` 和 `MasterKey AES256_GCM`。
- `AiChannelConfig` 不保存 API key。
- `TinaServerEnvironment.initialize()` 会 trim `SERVER_CONFIG_HMAC_SECRET`。
- `TinaServerApi` 使用 `ServerConfigHmacVerifier` 校验 server config；空 secret 会跳过并 warning。
- `CrashLogPrivacyClassifier` 区分 host/user runtime process，主进程 crash 才上传。
- 外部文件分享统一用 `TinaFileProvider` / `ExternalFileIntents` 生成 `content://` URI。
- Manifest 当前 `allowBackup=true` 且备份规则包含 `sharedpref`；不要把密钥类字段放入普通 SharedPreferences。
- Manifest 当前允许明文流量；修改网络安全策略前必须确认 AI BYOK、插件、调试服务和本地开发场景影响。

## Android 权限

- Manifest 包含网络、相机、存储、WAKE_LOCK、VIBRATE、前台服务、安装 APK、FileProvider 等声明。
- 用户可见权限说明、错误提示、Dialog 文案必须走 `core/i18n`。
- 修改权限时同步检查 Android 版本条件、request flow、设置页入口和测试。

## 高风险误区

- 不要自行拼接或信任外部传入路径。
- 不要把 API key、HMAC secret、keystore 密码写入普通日志、Room entity 或 crash 附件。
- 不要把 API key 放进 `AiConfig`、普通 SharedPreferences、导出配置或崩溃上报。
- 不要直接暴露 `file://` 给外部应用；使用 `content://`。
- 不要在数据库结构变更时漏掉 migration 和 DAO/entity 测试。
- 不要把 user runtime 进程 crash 当作 host crash 上传。

## 验证

```powershell
./gradlew :core:database:testDebugUnitTest --console=plain
./gradlew :core:security:testDebugUnitTest --console=plain
./gradlew :core:storage:testDebugUnitTest --console=plain
./gradlew :feature:ai:testDebugUnitTest --tests "com.wuxianggujun.tinaide.ai.channel.*" --console=plain
```

- 数据库变更跑对应 DAO/migration 测试。
- 权限或分享变更跑 app 编译，并人工核对 Manifest/provider 配置。
- 涉及日志导出或上传时，确认是否包含用户源码、路径、命令输出、API key、token，并保留显式用户确认边界。
