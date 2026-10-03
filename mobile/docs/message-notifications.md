# Capacitor 新消息提示音与后台本地通知

实现位于现有 Vue3 + Capacitor 工程，只涉及 `mobile/`。使用 Android 系统 API、自有原生桥接和本地 WAV；没有引入第三方推送 SDK、厂商推送通道或后台常驻服务。已有接口、角色权限和 WebSocket 事件保持原契约。

## 已确认的行为

- 以 `/api/notification` 返回的新增通知为准。审批、加工完成、库存预警等新通知触发提醒；金价行情广播本身保持静音。
- 前台收到新通知播放一次本地两音提示声。后台仍在执行时，发布带声音的本地通知；点击进入 `/notifications`，继续使用现有路由权限检查。
- 网络恢复、重新打开或冷启动时同步现有消息中心。积压的新通知合并提醒一次，列表逐条保留。
- “我的 → 消息设置 → 声音提醒”默认开启，关闭后仍发送静音通知栏提醒。类型开关控制对应类别的本机提醒，默认全部开启。设置和去重记录按门店、账号隔离。
- 首次安装或该账号第一次运行建立历史消息基线，旧未读消息不批量响铃。后续启动按保存的通知 ID 判断新增。
- 退出或切换账号取消本应用当前的消息通知，忽略旧账号的异步结果和通知点击。系统通知使用汇总文本，锁屏不展示业务内容。

## 三种运行状态及明确边界

| 状态 | 接收与提示音 | 恢复行为 |
| --- | --- | --- |
| APP 前台、联网且通知/声音已开启 | 收到通知后播放提示音 | WS 断开时自动重连 |
| APP 后台，JS 仍在运行，网络仍可用 | 收到通知后显示通知栏并响铃 | 返回前台主动重建 WS、同步消息 |
| APP 后台被系统冻结、进程被终止或被强行停止 | JS 停止；计时器、心跳、重连和通知触发都不执行，此时不接收新消息、不响铃 | 用户打开 APP、JS 恢复后才重连和同步，积压消息合并提醒一次 |

冻结是 Android 的进程/执行调度行为，纯 JS 没有绕过机制。即使进程还在，WebView JS 也可能已暂停。后台未冻结也不代表网络永远可用，系统省电和网络策略仍可能阻断连接。

“划掉最近任务”在不同机型上的结果不同：只要进程被停止或 JS 被冻结，就落入第三种状态。忽略电池优化、允许后台活动、锁定最近任务均不构成持续运行保证。悬浮窗权限同样不提供这项保证。

程序也不会在 JS 已冻结时实时更新页面上的“冻结”标志；恢复后只记录观察到的长执行间隔。该间隔可能来自冻结、计时器节流或系统繁忙，属于疑似暂停证据。`executionState` 表示可观察的前台/后台/恢复中状态，`lastExecutionGap` 保存恢复后观察到的间隔。

声音遵循系统静音、勿扰、通知音量、应用通知开关和 Android 通知通道设置。权限拒绝或系统通道静音时，应通过“消息设置”检查。网页声音还需要用户先点击页面以解锁浏览器音频；安卓通知栏和后台设置须在重新安装的 APK 中验证。

## 代码文件与接入点

| 文件（相对 mobile/） | 职责 |
| --- | --- |
| `src/utils/messageSocket.js` | 连接管理、连接超时、退避重连、网络状态、执行间隔、健康检查和静默连接重建 |
| `src/stores/app.js` | 沿用现有 WS URL、鉴权与业务事件处理，接入连接管理器并公开连接状态 |
| `src/composables/useMessageRuntime.js` | 全局单例监听 Capacitor 生命周期、网络切换和通知点击；协调通知同步与声音 |
| `src/utils/messageMonitor.js` | 按通知 ID 去重、账号隔离、首次基线、并发合并、恢复后补响 |
| `src/utils/messagePermissions.js` | Capacitor 权限和系统设置工具；H5 返回非原生状态 |
| `src/utils/messageSound.js` | APK 原生提示声，H5 本地 Web Audio 声音及用户手势解锁 |
| `src/utils/messagePreferences.js` / `src/stores/messages.js` | 当前通知列表、未读状态、每账号设置和去重缓存 |
| `src/components/MessageSettings.vue` | 已集成的页面组件：声音开关、试听、通知授权、电池优化及后台设置 |
| `src/components/ProfilePanel.vue` / `src/views/Notifications.vue` | 设置入口和实时消息列表 |
| `src/App.vue` | 在应用根部调用一次 `useMessageRuntime(router)`，全局生效 |
| `android/app/src/main/java/com/xinchengjinjiang/mobile/DajinMessagesPlugin.java` | Android 网络回调、本地通知、音量/勿扰处理、权限和厂商设置页跳转 |
| `android/app/src/main/java/com/xinchengjinjiang/mobile/MainActivity.java` | 注册自有 `DajinMessagesPlugin` |
| `android/app/src/main/res/raw/new_message.wav` / `public/sounds/new-message.wav` | 相同的自制本地提示音 |
| `scripts/create-message-sound.mjs` | 可重复生成 WAV 的源码 |

页面接入示例（现有“我的”页已完成集成）：

```vue
<template>
  <button @click="open = true">消息设置</button>
  <MessageSettings v-if="open" @close="open = false" />
</template>
<script setup>
import { ref } from 'vue'
import MessageSettings from './components/MessageSettings.vue'
const open = ref(false)
</script>
```

不要在每个页面重复调用全局运行器；保持 `App.vue` 中的一处接入，避免重复连接和重复提示音。

### 心跳与通知同步

- WebSocket 连接超时 12 秒，断线按 1/2/4/8/16/30 秒加少量随机间隔重试；断网暂停，联网恢复。
- 管理器每 25 秒调用现有健康检查，静默 90 秒的 OPEN 连接主动重建。保留原有服务健康状态检查。HTTP 健康成功只证明服务可达，不充当 WS pong。
- 现有前端协议未定义 JSON `ping/pong`，因此不发送自创的心跳消息。网络切换和页面激活会主动重建连接，避免依赖休眠前过期的 `readyState`。
- 业务广播在 500 毫秒内合并触发消息查询；JS 正常运行期间每 15 秒补同步一次，覆盖没有广播的通知。没有广播的消息提示延迟通常不超过一个轮询周期再加网络耗时。
- 去重缓存保存当前服务端返回的 ID 与最近 2000 条历史 ID。消息始终以服务端现存数据为准，超过服务端查询保留范围或已被清理的通知没有补拉来源。
- 本次是“尽力提醒”，不是消息投递 SLA。断网、冻结、强行停止、通知权限拒绝和系统静音均受各自限制。

## AndroidManifest.xml 配置

当前项目由 Capacitor 构建；有效配置文件是 `android/app/src/main/AndroidManifest.xml`，仓库遗留的 UniApp `src/manifest.json` 不参与 APK 构建。

以下节点已放在 `<manifest>` 下，与 `<application>` 同级：

```xml
<!-- 既有网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<!-- 新增：网络状态变化监听 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- 新增：Android 13+ 需运行时申请，由系统让用户决定 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- 新增：用户点击设置入口时，申请忽略电池优化 -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<!-- 新增：可选高级设置，单独手动授权；不是通知前置条件 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

没有新增后台 Service、开机自启 Receiver、WakeLock 或常驻通知。Android 不存在各品牌通用的“后台运行权限”弹窗；小米/vivo 的自启动和后台活动需要用户在厂商设置页中打开。设置入口失效时回到应用详情页，不假定已授权。首次登录仅自动询问系统通知权限一次；电池、悬浮窗等设置均由用户点击后打开。

原生插件建立“新消息（有声）”和“新消息（静音）”两个通知通道。应用关闭声音时选择静音通道，避免 Android 通道创建后声音配置固定导致开关失效；不删除重建通道覆盖用户的系统设置。

## 小米 / vivo 使用步骤

1. 安装本次重新构建的 APK，登录后允许发送通知。
2. 打开“我的 → 消息设置”，点“试听提示音”。检查通知音量，退出静音和勿扰模式后进行有声测试。
3. 点“申请忽略电池优化”，根据系统界面完成授权。
4. 小米/Redmi：在应用省电策略中选择“无限制”；在权限或应用管理中允许后台自启动。vivo/iQOO：在后台耗电管理中允许应用后台高耗电/后台运行，并在权限管理中打开自启动。
5. 如系统支持，在最近任务中锁定本应用，减少误清理。不要把这一步理解为系统冻结豁免。
6. 若长时间锁屏后消息停止到达，打开 APP 恢复连接；新的未读通知会合并提醒一次。各系统版本菜单名称可能不同。

## 构建与验证

在 `mobile/` 中运行：

```powershell
pnpm test
npm run build
npm run build:android
Set-Location android
.\gradlew.bat :app:assembleDebug --console=plain
```

Android 构建使用 JDK 21 和已配置的 Android SDK。输出：`android/app/build/outputs/apk/debug/app-debug.apk`。调试包使用项目已有的 `.debug` 包名后缀，可与正式包并存；API 地址沿用 `.env.android`，打包本身没有部署服务端。普通 H5 构建产物在 `dist/build/h5/`。

自动验证覆盖：首次基线、历史消息静音、后台静音通知、点击跳转、账号切换、断网恢复、连接超时、静默连接、恢复后合并补响、缓存去重、偏好开关。硬件行为还需在小米与 vivo 实机分别完成以下验收：

| 场景 | 预期 |
| --- | --- |
| 前台收到 1 条新通知 | 播放一次声音，消息中心新增记录 |
| 后台仍在运行收到新通知 | 通知栏提醒；点击进入消息页 |
| 声音开关关闭 | 前台静音、后台静音通知，记录保留 |
| 静音/勿扰/通知通道被关闭 | 遵守手机设置，不强行发声 |
| 仅实时金价刷新 | 不响铃 |
| 网络断开后恢复、Wi-Fi 切到流量 | 自动重连，无重复通知 |
| 锁屏静置数分钟并被系统冻结 | 冻结期间无实时提醒；打开后补同步并合并响一次 |
| 强行停止再手动启动 | 停止期间无实时提醒；启动后同步 |
| 旧通知点击时已切换账号 | 不跳转到另一账号的业务消息 |
| 拒绝通知/电池权限 | 页面和正常业务继续运行，设置中可查看状态并重新操作 |

本次验证（2026-10-03）：`pnpm test` 通过 37 个文件、107 项测试；H5 生产构建、Android Web 构建及 `assembleDebug` 均通过。浏览器设置页的声音开关、试听入口已检查，音频播放 API 返回成功。构建仍有既有的大于 500 KB 分包提示及 Gradle flatDir 提示。

开发机当前未连接安卓真机；自动化测试和 APK 编译通过不等于已验证小米/vivo 的锁屏后台行为。调试 APK 沿用现有 `https://admin.xinchengjinjiang.com` 接口配置，供实机检查；请使用专门的测试账号和测试单据。
