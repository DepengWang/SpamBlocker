# SpamBlocker CN 基线审查

日期：2026-10-10

## 基线

- 上游：`https://github.com/aj3423/SpamBlocker.git`
- 基线 commit：`65f61ec64bb8a7bf073e8460dea5928666a45fc8`
- 原始分支：`master`
- 应用：`spam.blocker`，F-Droid flavor
- `minSdk = 29`，`targetSdk = 36`，`compileSdk = 37`
- 原始版本：`versionName 5.18`，`versionCode 518`
- 当前工作区原计划要求的 Git 分支无法创建：`.git` 在本环境中只读，因此变更保留在 `master` 工作树，未伪造分支或提交记录。

## 已确认的现有能力

| 能力 | 实际入口 | 结论 |
| --- | --- | --- |
| 电话筛选 | `spam.blocker.service.CallScreeningService` | 已有原生 `CallScreeningService`；P1 不重写服务。 |
| 来电浮窗 | `spam.blocker.ui.widgets.FloatingWindow`、`ui.setting.quick.CallerID` | 已有唯一浮窗实现，支持拖动、位置、背景色、模板和锁屏显示；P1 复用。 |
| 来电内容 | `CallerID` 模板 | 已支持 `{reason}`、`{geolocation}`、`{carrier}`；未知字段以占位符显示，不增加第二套 UI。 |
| Instant Query | `db.ApiTable`、`service.checker.Checker`、`ui.setting.api` | 已有可配置 API 工作流、并行查询/结果合并链路；P1 保留，后续国内 API 适配单独进入下一阶段。 |
| 归属地/运营商 | `util.Util.numberGeoLocation()`、`numberCarrier()` | 已使用 libphonenumber 离线 geocoder/carrier metadata，但原逻辑没有显式的中国号码标准化和号码类别模型。 |
| 历史记录 | `db.HistoryTable`、`ui.history` | 已有电话/SMS 历史表及 UI，不新增替代数据库。 |
| 规则拦截 | `service.checker.Checker`、`db.RegexTable` | 已有联系人、地理位置、运营商、API 等规则，保持原优先级。 |
| 配置持久化 | `util.SharedPref`、`config.Config` | 已有 Caller ID、Instant Query、主题及导入/导出配置。 |

## P0 验证

1. 基线 APK `assembleFdroidDebug` 构建成功。
2. 基线 APK 已保留数据安装到真机 `2dfa4369`（OPPO CPH2573IN）。
3. 安装后读取到 `versionName=5.18`、`versionCode=518`。

## 风险

- `local.properties` 中的 SDK 路径指向不存在的目录；本机 Android SDK 仍能被 Gradle 解析，但每次构建会给出 SDK location warning。
- Android 16/厂商电话界面仍需在真机通过实际来电、锁屏、接听和挂断验证；静态构建不能替代该验收。
- libphonenumber 的离线库不是实时运营商数据库，结果必须展示为离线来源，不能推断骚扰风险。
