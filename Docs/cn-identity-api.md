# P2 / P3：浮窗与通用号码识别接口

## 本轮边界

用户确认暂时没有腾讯、360、电话邦的授权接口，先完成通用接入。没有内置猜测的私有接口、抓取绕过或默认上传号码。

- P2：来电/去电开关、深色/浅色/自定义主题、联系人控制、字号、背景颜色/透明度、时长、接听后保留、来源/标记/风险展示和原位更新。
- P3：HTTPS JSON 适配器、多源合并、独立缓存、错误退避、总超时和取消、配置备份/恢复及测试入口。
- 原有 Instant Query / PhoneBlock 继续使用原规则，不把新增识别结果自动导入拦截规则或骚扰库。
- 去电和锁屏显示受系统角色、权限及厂商限制；系统未提供详情时不会额外启动电话监听或常驻服务。

## 设置入口

设置 → 归属地、运营商和在线查询 → 识别来源。修改后先保存，再到同组的“查询测试”验证。测试只使用已经保存且启用的来源；空来源列表只查询离线库。来源配置、测试与数据管理均为独立全屏页，自定义 JSON 收入高级配置。添加服务会追加来源，不覆盖其他配置。

每个来源有独立 `enabled` 开关；在线来源默认关闭。来电自动查询不会上传通讯录联系人号码。用户主动测试时，会将输入号码发送至启用来源。

## 通用接口示例

示例域名没有真实号码查询服务，必须换成自己获授权的 HTTPS 接口：

```json
[
  {
    "id": "custom",
    "name": "我的接口",
    "enabled": false,
    "url": "https://example.com/lookup?number={number}",
    "method": "GET",
    "headers": {"Authorization": "Bearer {shared_pref(my_api_token)}"},
    "paths": {
      "name": "data.name",
      "province": "data.province",
      "city": "data.city",
      "carrier": "data.carrier",
      "category": "data.category",
      "risk": "data.riskLevel"
    },
    "riskMapping": {"3": "high", "2": "medium", "1": "low"},
    "successPath": "code",
    "successValue": "0"
  }
]
```

- `id` 必须唯一，最多 6 个来源；网络并发上限 3。
- `{number}` 为标准化 E.164 号码；`{domestic}` 为国内号码。URL 中替换值会 URL 编码。
- 支持 GET / POST。POST 使用 `body` 模板，并在 `headers` 设置所需 Content-Type。
- JSON 路径用点分隔，支持数组数字下标，例如 `data.0.name`。
- `successPath/successValue` 用于校验业务成功状态，建议按接口文档配置，避免 HTTP 200 的鉴权错误被误认为无标记。
- 仅接受 `unknown/low/medium/high` 风险值；其他枚举需使用 `riskMapping` 映射。低风险或无结果不代表安全。
- 请求头支持原工程 `{shared_pref(key)}` / base64 标签；不会记录解析后的请求头、URL、响应正文或密钥。
- 保存的配置与原 API 配置一样纳入备份。不要把密钥硬编码到示例或提交到 Git；含认证信息的备份必须妥善保护。
- 不支持未经文档验证的专有签名协议；需要这类认证的服务应先通过自行授权的网关转换成通用接口。

## CallerInfo / PhoneNumber 兼容

兼容 CallerInfo 使用的 PhoneNumber legacy 参数语义，不复制其实现代码：

- `parameters` 可配置任意 GET 查询参数；值支持 `{number}`、`{domestic}` 和 `{shared_pref(key)}`，例如 `tel={domestic}`、`key={shared_pref(baidu_api_key)}`。参数名和值均由 URI builder 编码。
- 保留旧偏好键 `only_offline_key`（仅离线）和 `api_type`（优先来源 ID）。JSON 来源可选声明 `apiId`；匹配的来源排在合并优先级首位，其他来源仍按 JSON 顺序。`-1` 表示使用 JSON 原顺序。
- API 凭据沿用 `baidu_api_key`、`juhe_api_key` 两个 SharedPreferences 名称，可由自定义来源引用；密钥不写入应用配置备份。
- 兼容 PhoneNumber `CUSTOM.md` 响应：检查 `error_code == 0`；读取 `result.name/province/city/provider/company/info`。`provider` 映射为运营商；公司及附加说明会显示于查号结果和来电浮窗。也可在 `paths` 中显式配置不同结构。
- `cn_identity_ignored_prefixes` 是本应用扩展项，多个前缀可用空格、逗号、分号或换行分隔。命中时仍查询本地离线库，只跳过在线来源。
- 百度/聚合数据旧密钥仅可供用户自己的接口使用；未内置或自动调用未经当前官方文档验证的旧服务端点。

### 聚合数据默认接口预设

识别来源页的“添加服务 → 聚合数据”会追加当前官方手机归属地接口：`https://apis.juhe.cn/mobile/get`，GET 参数为 `phone={domestic}` 与 `key={shared_pref(juhe_api_key)}`，检查 `error_code=0`，读取 `result.province/city/company`。这是 CallerInfo 中聚合数据来源的可替换、HTTPS 通用适配；预设使用 legacy Juhe 来源 ID `1`，可配合 `api_type` 优先级。启用前必须填写用户自己的有效 Juhe API Key。没有密钥时只加载禁用配置，不发起号码查询。未将未核实的旧百度地址写成默认项。

来源：[聚合数据官方手机归属地 API 文档](https://www.juhe.cn/docs/api/id/11)；CallerInfo/PhoneNumber 的在线来源顺序及凭据机制见上方 legacy README。

### Tmini 聚合号码标记预设

以[新站接口详情](https://api.tmini.net/detail/1)为准。GET `https://api.tmini.net/apis/checkmark`，参数 `key={shared_pref(tmini_api_key)}` 和 `phone={domestic}`；成功码为 `code=200`，通过 `results.*.tags.*.name` 收集各平台标签，并读取 `region`、`certName`。其他状态（包括 `code=0` 的积分余额不足）按接口错误处理，不视为无标记。识别来源页通过“添加服务 → Tmini”追加预设，填入 API Key 并启用、保存；查询号码和 Key 会随 HTTPS 请求发送到 Tmini。

该接口为付费接口，页面注明每次成功调用扣 300 积分。供应商文档明确建议 Key 仅在服务端使用，避免放入客户端代码或日志。当前应用直连模式将 Key 保存在设备私有设置中，但仍不等同于服务端保管，存在设备提取风险；生产使用更推荐由用户自有服务端代理请求。用户只应在了解风险后配置自己的、可单独撤销的 Key。

测试查询遇到 HTTP/业务错误时会显示安全的诊断信息（HTTP 状态、业务码及接口返回的 message/msg），不会显示请求 URL 或 Key。连接异常显示脱敏原因及失败阶段，结果区分本地/缓存/在线并显示耗时。保存配置仅清除通用查询表的错误/超时记录；重新请求失败项也仅跳过错误/超时缓存，成功结果及永久数据仍保留。Tmini 的 `region` 会尽量解析成省、市、运营商；地区补充写入 `marked.db` 工作副本的 `tmini_region_overlay` 表，按完整号码保存，仅在本地 `phone.dat` / libphonenumber 字段缺失时填补，不改写 APK 中不可变的号码段元数据，也不覆盖已有归属地。

调用顺序：先查原始本地 `phone_number` 标记；本地命中则跳过 Tmini。未命中时查同一个应用私有 `marked.db` 工作副本中的 `tmini_mark_cache`；已有记录不论写入时间都跳过付费请求。成功标记和成功无标记均永久保存，不自动过期、不因数量上限淘汰；只有在“数据与备份 → 查询数据”明确确认“删除付费查询数据”才删除；清理失败记录或通用缓存不删除永久数据。删除永久数据会同时清理其归属地补充及该来源通用缓存，保留内置旧标签库。错误不写入标记库，也不会被当成无标记。旧版本已有缓存升级时会转为永久记录。每条缓存保存标签、地区/认证字段及查询时间；不会改写 APK 中的 `res/raw/marked_2016.db` 原始快照。该独立表位于应用私有的可写工作副本，便于未来纳入线上备份/同步；当前尚未实现线上备份。

参考原始文档：[PhoneNumber README](https://github.com/xdtianyu/PhoneNumber/blob/legacy/README.md)（凭据、only-offline、api_type）与 [CUSTOM.md](https://github.com/xdtianyu/PhoneNumber/blob/legacy/CUSTOM.md)（`tel/key` 和 `error_code/result` 响应）。

## 合并与缓存

每个来源保留独立证据（名称、地区、运营商、类别、风险、查询时间、状态），归属地与标记分别处理。

离线归属地/运营商优先；线上补充身份及缺失字段。结果按配置顺序归并，而非按网络返回先后覆盖。不同来源名称、类别或风险冲突时保留证据并提示冲突，不给出安全结论。所有新结果只作提示，不自动挂断。

默认缓存策略，可在设置中填写 JSON 调整：

```json
{"enterpriseHours":168,"markHours":24,"emptyHours":6,"errorMinutes":2}
```

身份/企业结果 7 天，标记/风险 1 天，确认无结果 6 小时，接口错误或超时退避 2 分钟。一个响应同时含身份和风险时使用标记有效期。错误与无结果状态独立。范围会钳制为身份/标记 1–720 小时、无结果 1–168 小时、错误 1–60 分钟。

通用在线来源缓存位于原 SQLite 数据库新增表 `cn_identity_cache`，通过 `onOpen` 幂等建表，无需占用上游 DB_VERSION；不修改历史、规则或 API 表。键包含标准化号码和来源配置 SHA-256 指纹，来源修改后不复用旧缓存；按上方 JSON 策略过期、最多保留 2000 条，支持手动清空。Tmini 的付费标记缓存独立保存在 `phonenumber_marked_20160311.db` 工作副本的 `tmini_mark_cache` 表中，持久化策略见上一节。离线信息每次重新查询，因此随内置号码库版本变更，不长期缓存旧归属地。配置备份不包含可再生成的查询缓存；未来线上备份需单独纳入 Tmini 表，并保护号码数据。

## 时序与生命周期

新增识别不参与系统筛选决定，筛选响应不等待此聚合层。先显示离线数据，然后在原窗口替换在线内容，不重复附加窗口、不重播动画、不重置时长。挂断、默认接听隐藏、计时到期、关闭浮窗、新来电及预览会使旧会话失效并取消旧查询。

自动来电查询总超时默认 3000 ms，可设 250–5000 ms，包括等待并发许可。手动查询/测试使用独立时限，默认 15000 ms，测试页可选 5/15/30 秒，不改变自动查询预算。单次连接/读取时限与当前预算一致，同时受总超时取消约束。响应上限 256 KiB，不自动重试或跨域重定向；取消时断开连接。接口异常不会影响其他来源或离线显示；数据库缓存异常会退化为非缓存查询。

## 待实机人工验收

普通/锁屏/联系人/去电、接听保留、挂断、连续来电、双卡、省电模式及系统电话界面遮挡。自动浮窗测试不能替代真实电话测试；通用接口夹具测试不能替代获授权的供应商联调。

参考：

- Android 官方 [CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService)：筛选角色和来去电回调限制。
- [电话邦官网](https://www.dianhua.cn/)：候选服务，未取得可用授权接口，不代表已经接入。
