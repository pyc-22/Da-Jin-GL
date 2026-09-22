# Electron 前台端

## 开发

```powershell
$env:PATH="C:\Users\0.0\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin;$env:PATH"
pnpm install
pnpm dev
```

`pnpm dev` 会启动 Vite (`http://127.0.0.1:5173`) 和 Electron。生产构建使用 `pnpm run build`，Windows 安装包使用 `pnpm run dist`。

## 打包后的资源验证

Electron 正式包通过 `loadFile` 加载 `app.asar/dist/index.html`，Vite 的 `base` 保持为 `./`。`pnpm run build` 会自动运行 `scripts/verify-build.cjs`，按 `file://` 解析生成的 JS/CSS 引用，资源路径越出 `dist` 或文件缺失时终止打包。

生成安装包后，可验证真实 ASAR 内的主进程入口、预加载脚本与页面资源：

```powershell
pnpm exec electron scripts/verify-electron-package.cjs
```

该检查使用临时用户目录，在 Electron 中执行正式包的 `loadFile` 分支，核对 Vue 页面挂载、样式加载、预加载接口及本地文件错误。结果和截图保存在项目根目录的 `runtime-logs/cashier-package-smoke`。该检查覆盖本地页面启动；后端登录、收款和打印另行验收。

## 本地数据与断网同步

Electron 主进程通过 IPC 使用 SQLite WASM，在 Electron userData 目录创建 `dajin-local.sqlite`。商品、金价、会员和打印日志落盘；离线订单/支付写入 `sync_queue`，每条请求都有 `clientRequestId`，同步时按订单先后顺序提交。支付队列同时携带 `orderClientRequestId`，后端可跨同步轮次解析已落云订单，避免应用重启后丢失关联。库存/财务接口返回 409 或版本不一致时写入 `sync_conflict` 并弹出人工处理提示，不会静默覆盖。

## 打印

- 热敏小票：ESC/POS 初始化、居中、换行、切纸 (`ESC @`, `ESC a`, `GS V`)；钱箱脉冲 (`ESC p`)。
- `58mm` 使用 32 字符宽度，`80mm` 使用 48 字符宽度。
- A4 质保单使用 Electron 系统打印服务，预览包含商品图片（无图片时显示占位）和质保条款。
- 在“打印与设备设置”中选择纸宽、Windows 打印机名称和是否静默打印；补打会写入 `print_log`。

## 数据库验收

Docker 可用后执行：

```powershell
.\scripts\verify-db.ps1
```

脚本会启动 MySQL/Redis，并查询表数量、门店数量、配置数量及审批阈值。
