# SpamBlocker CN 分阶段实施方案

日期：2026-10-10

## 实施边界

P0 负责恢复上游工程、完成基线审查和构建；P1 只增加中国号码识别所缺失的适配层，并接入已有 UI/服务。具体策略：

1. 新增 `spam.blocker.util.CnNumberLookup`，不复制 CallerInfo/PhoneNumber 的 GPL 代码。
2. 标准化 `+86`、`0086` 和普通国内拨号格式；带其他国家代码的 `+...` 保持国际号码，不强行改成中国号码。
3. 使用已有 libphonenumber、geocoder、carrier 离线 metadata；不依赖网络，不在筛选回调中增加网络等待。
4. 识别中国手机号、固话、400、800、95/96 特殊服务号码和国际号码；无法解析时只保留原号码，不生成虚假归属地。
5. 让现有 `Util.numberGeoLocation()` / `numberCarrier()` 统一走新适配层，因此现有测试页面、历史页面、规则引擎和 Caller ID 浮窗自动复用。
6. 不新建浮窗服务：当前 `FloatingWindow` 已支持模板、拖动、位置、颜色和锁屏显示；不改变其生命周期和 `CallScreeningService`。
7. 保留现有 Instant Query/API 表和结果流；国内 API 聚合、缓存和可信度策略属于后续阶段，不在本次伪造接口。

## 文件变更

- `app/src/main/java/spam/blocker/util/CnNumberLookup.kt`：P1 离线标准化、分类、归属地/运营商查询和数据库版本标识。
- `app/src/main/java/spam/blocker/util/Util.kt`：两个既有入口改为委托 `CnNumberLookup`。
- `app/src/test/java/spam/blocker/util/CnNumberLookupTest.kt`：标准化、国际号码、服务号码和未知号码测试。
- `app/build.gradle.kts`：启用 JUnit Platform，并补充 launcher，修复本工程原有 JUnit 5 测试任务无法发现测试的问题。
- `docs/cn-baseline-audit.md`、`docs/cn-implementation-plan.md`、`docs/cn-progress.md`：过程与验收记录。

## 验证方案

- 单元测试：`gradlew.bat :app:testFdroidDebugUnitTest`
- APK：`gradlew.bat :app:assembleFdroidDebug`
- 真机：`adb devices -l`，然后 `adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk`
- 安装回读：`adb shell dumpsys package spam.blocker`，核对 versionName/versionCode。
- 待人工验证：应用内号码测试页输入 `+86 13800138000`、`0086 13800138000`、`4001234567`、`+12025550100` 和非法字符串；实际来电场景另行验证浮窗。

## P2 / P3 实施补充

用户确认先补齐 P2、完成 P3 通用接入，再统一测试；国内服务暂时没有授权接口，不擅自使用未验证的私有端点。

- P2 继续复用 `FloatingWindow`，增加主题/去电/来源/风险选项与不重置计时的原位更新。
- `CallerIdentityPresenter` 与 `CallerIdentitySession` 隔离在线查询生命周期，不改变原有筛选判定和后处理工作流。
- `NumberIdentity` 提供来源配置、严格 JSON 解析、证据合并、TTL 策略和限时并发协调。
- `IdentityLookup` 复用现有号码标准化和认证标签，新增有明确 socket 超时、响应上限和取消断连的 HTTPS 适配。原网络助手没有这些界限，因此没有直接把无界下载放入聚合查询。
- `NumberIdentityCache` 使用原数据库新增扩展表，原历史/规则/API 表及上游数据库版本不变。
- `IdentitySettings` 放在既有 Instant Query 分组内；配置借助 `ApiQuery` 备份恢复，新识别结果不自动导入骚扰库或用于拦截。
- 单元测试覆盖标准化、解析、冲突、缓存、超时、失败隔离、并发、会话与旧配置兼容；仅运行新增真机测试，不执行会清空用户数据的上游规则测试。

详细接入与行为约定见 `Docs/cn-identity-api.md`，实际验证结果见 `Docs/cn-progress.md`。真实电话/锁屏验收及授权供应商联调独立记录，不能由夹具测试代替。
