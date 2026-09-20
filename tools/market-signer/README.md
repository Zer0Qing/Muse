# Muse 插件市场签名工具

把插件源码打成 App 可安装的签名包，并生成可被 App 验签的在线目录。
所有产物都由 App 自身的验签器验收（见 `app/src/test/java/io/zer0/muse/data/plugin/market/MarketSigningFixtureTest.kt`），
因此本工具与 App 的算法不会漂移。

## 依赖

- Python 3.8+（仅用标准库）
- OpenSSL 可执行文件（`openssl version` 能跑即可）

## 目录结构

```
market-signer/
├── sign.py                 # 工具本体
└── plugins/                # 插件源码（每个子目录一个插件）
    ├── text-toolkit/       # manifest.json + main.js
    └── time-toolkit/
```

## 三件私钥相关的事（务必先读）

1. 私钥只存在本机，**绝不入库、绝不外发**。默认放在 `C:\Users\21192\.muse-market\keys\`。
2. **目录根私钥**决定 App 是否接受整个目录；**发行者私钥**决定用户在设备上看到的发行者身份。
   建议长期保持两把分开（当前已按此配置）。
3. 私钥丢失 = 无法再更新已发布目录（用户端信任根绑定的公钥不会变），请立即备份到离线介质。
   泄露 = 必须作废重签，并把新公钥推给所有客户端。

## 常用命令

```bash
# 1) 首次：生成两把 P-256 私钥（只需一次）
python sign.py keygen --key "C:/Users/21192/.muse-market/keys/catalog-key.pem"
python sign.py keygen --key "C:/Users/21192/.muse-market/keys/publisher-museai.pem"

# 2) 打签名插件包（每个插件一次）
python sign.py pack \
  --src plugins/text-toolkit \
  --key "C:/Users/21192/.muse-market/keys/publisher-museai.pem" \
  --publisher-id museai \
  --out-dir app/build/market-signing/out/packages

# 3) 生成签名目录（扫描上一步产出的全部包）
python sign.py catalog \
  --packages-dir app/build/market-signing/out/packages \
  --key "C:/Users/21192/.muse-market/keys/catalog-key.pem" \
  --key-id museai-catalog-1 \
  --base-url "https://museai.ltd/muse-market/packages" \
  --catalog-id museai-official \
  --out app/build/market-signing/out/signed-catalog.json
```

产物：

- `app/build/market-signing/out/signed-catalog.json` —— 目录文件
- `app/build/market-signing/out/packages/*.muse-plugin` —— 插件包
- `app/build/market-signing/out/public-keys.json` —— 公钥与指纹（非机密，供配置与核对）

## 发布到服务器

打包与签名全部插件（在 `1muse/` 下执行）：

```bash
KEYS="C:/Users/21192/.muse-market/keys"
OUT="app/build/market-signing/out"

for plugin in tools/market-signer/plugins/*/; do
  python tools/market-signer/sign.py pack \
    --src "$plugin" \
    --key "$KEYS/publisher-museai.pem" \
    --publisher-id museai \
    --out-dir "$OUT/packages"
done

python tools/market-signer/sign.py catalog \
  --packages-dir "$OUT/packages" \
  --key "$KEYS/catalog-key.pem" \
  --key-id museai-catalog-1 \
  --base-url "https://museai.ltd/muse-market/packages" \
  --catalog-id museai-official \
  --out "$OUT/signed-catalog.json"
```

上传并做公网一致性校验：

```bash
ssh -i <你的SSH私钥> root@117.72.212.102 'mkdir -p /var/www/muse-market/packages'
scp -i <你的SSH私钥> "$OUT/signed-catalog.json" root@117.72.212.102:/var/www/muse-market/
scp -i <你的SSH私钥> "$OUT/packages"/*.muse-plugin root@117.72.212.102:/var/www/muse-market/packages/

curl -sS --compressed https://museai.ltd/muse-market/signed-catalog.json | sha256sum
sha256sum "$OUT/signed-catalog.json"
```

服务器 nginx 已把 `/muse-market/` 别名到 `/var/www/muse-market/`（见 `museai.ltd` 站点配置）。
App 侧内置了同一个地址与信任根（`PluginMarketDefaults`），用户开箱即用、无需配置。

## 每次发布前的验收（务必先跑）

```bash
# 1) 插件行为：用宿主同样的调用契约跑遍每个工具
node tools/market-signer/verify-plugins.js

# 2) App 侧验收：用 App 自己的验签器检查产物，并确认发布用的密钥与内置信任根一致
./gradlew :app:testDebugUnitTest --tests "io.zer0.muse.data.plugin.market.*"
```

第 2 条里的 `MarketSigningFixtureTest` 会断言：目录与插件包能被 App 接受、篡改包会被拒绝、
**发布侧公钥等于安装包内置的官方信任根**。用错私钥签发会在这里失败，而不是等用户在设备上看到
「未知的目录签名密钥」。

## 在 App 里使用

插件市场开箱即用：设置 → 插件管理（Plugins）→ 插件市场，进入即自动加载官方目录。
设置项只用于**自定义覆盖**（测试目录或应急切换）：留空即使用安装包内置的官方目录，
且内置信任根不可被覆盖或移除。

## 运营规则（App 会强制的硬约束）

- `sequence` 默认取当前 Unix 秒，**必须单调不减**；回退会被 App 永久拒绝（持久化在设备私有目录）。
- `expiresAtEpochMs` 到期后整个目录失效（含本地缓存），需在到期前重新签发；默认有效期 30 天。
- 目录 URL 与插件下载地址都必须是 `https://`，且解析到公网地址（App 出口有 DNS 固定与 SSRF 校验，
  内网/回环地址一律拒绝）。
- 目录体积 ≤ 512KB，单个插件包 ≤ 20MB。
- 目录只能由**目录根私钥**签名；插件包只能由**发行者私钥**签名，且每个包的发行者 ID 一旦被用户信任，
  就不能再换公钥（换键会被设备信任根拒绝）。
- 一个插件 id 目前只放一条 entry（多版本共存与回滚模型尚未实现）。

## 新增一个插件

1. 复制 `plugins/time-toolkit/` 为新目录，改 `manifest.json`（id/name/tools/entry）与 `main.js`。
2. JS 契约：每个工具一个函数，接收参数对象并返回普通对象；出错返回 `{ error: "原因" }`。
   宿主会对返回值做 `JSON.stringify`，因此不要自己再返回字符串化结果。
3. 能力白名单只有 `resource.read` / `ui` / `ui.mood` / `ui.skin`；纯计算插件留空即可。
4. 跑 `pack`（会做结构与签名自校验）→ 跑 `catalog` → 上传 → App 里点刷新。

## 新增一个气泡皮肤包（kind: ui-skin）

皮肤包只分发声明式 JSON，**不包含任何 JS**，宿主负责渲染：

```
plugins/skin-xxx/
├── manifest.json   必需：kind="ui-skin"、capabilities=["ui.skin"]、tools=[]
└── skin.json       必需：BubbleSkin JSON（schemaVersion / id / name / light / dark）
```

- `manifest.entry` 保持默认 `main.js` 且**不要真的放 main.js 文件**：宿主的内容摘要会把
  「入口名 + 空内容」算进去，签名工具已按同一规则处理。
- `skin.json` 的 `light` / `dark` 各写一份角色配色，角色名固定为
  `USER` / `ASSISTANT` / `GROUP_ASSISTANT` / `SYSTEM` / `TOOL`；缺省角色会回退到 `ASSISTANT`。
- 颜色是 ARGB 长整型（如 `0xFFDCE9FF` 写成十进制 `4292702207`）；字段含义见
  `ui/theme/BubbleSkin.kt` 的 `BubbleRoleStyle`。
- **发布前会校验 WCAG 对比度**：正文色与底色对比度必须 ≥ 4.5，否则宿主会静默回退内置
  气泡（用户会以为「装了没用」），因此 `pack` 直接拒绝上架这类皮肤。
- 参考实现：`plugins/skin-soft-bubble/` 与 `plugins/skin-contrast-bubble/`。

## 自动化校验

```bash
# App 侧验收：用 App 自己的验签器检查产物（含篡改负向用例）
./gradlew :app:testDebugUnitTest --tests "io.zer0.muse.data.plugin.market.MarketSigningFixtureTest"
```

产物目录不存在时该测试自动跳过，因此 CI 不受影响。
