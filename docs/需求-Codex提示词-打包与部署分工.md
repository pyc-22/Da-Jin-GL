# 打包与部署 · 分工提示词（交给 Codex）

> 一句话：**打包交给 Codex（本机构建，安全）**；**部署到云服务器由运维（老板那边）做**（需要 SSH 私钥、生产库备份和回滚，Codex 一直被告知不要碰生产）。
> 规则：**不要部署、不要连生产、不要 push**；打包前四套测试必须全绿（当前基线：后端 217 / 管理端 63 / 手机端 180 / 收银端 84）。

---

## 一、版本号：1.0.7 → **1.0.8**

- 改 `front-pc/package.json` 与 `mobile/package.json` 的 `version` 为 **1.0.8**（管理端、后端不用改）。
- **不要覆盖旧的安装包/APK**（1.0.7 要留着回滚）。

## 二、要 Codex 做的四件打包（全部在本机，不碰生产）

### 1. 收银端安装包（Electron）

```powershell
pnpm --dir front-pc test                     # 含网络检查，必须全过
pnpm --dir front-pc run dist                 # = vite build + verify + electron-builder
```
- **产物**：`front-pc/release/打金店收银台 Setup 1.0.8.exe`
- **期望**：把 exe 复制到 `D:\xiangmu-wenjian\releases\cashier\`，并生成同名 `.sha256`（小写十六进制）。
- **注意**：`dist` 已经包含 `vite build` + 产物校验，不要再单独跑 `build`；electron-builder 输出目录就是 `front-pc/release`。

### 2. 手机端 APK（Capacitor + Android）

```powershell
pnpm --dir mobile test
pnpm --dir mobile run build:android          # vite build --mode android（用 .env.android，指向线上域名）+ cap sync android
cd mobile/android
.\gradlew.bat :app:assembleRelease --console=plain
```
- **产物**：`mobile/android/app/build/outputs/apk/release/app-release.apk`（若 release 不可用，用 `assembleDebug` 的 `app-debug.apk`，并在汇报里说明用的是哪个）。
- **期望**：复制成 `D:\xiangmu-wenjian\releases\android\dajin-mobile-1.0.8.apk` + 同名 `.sha256`。
- ⚠️ **不要用 `pnpm --dir mobile run build:android:local`**：那个脚本会把接口指向局域网 `http://192.168.1.221:8080`，**只适合本地测试**，装到店里会连不上；正式包必须走 `--mode android`（`.env.android` 里是 `https://admin.xinchengjinjiang.com`）。

### 3. 管理端（admin-web）构建产物

```powershell
pnpm --dir admin-web test
pnpm --dir admin-web run build
```
- **产物**：`admin-web/dist/`（静态站点）。
- **期望**：打成 `D:\xiangmu-wenjian\releases\admin-web-1.0.8.tar.gz`（或 .zip）+ 同名 `.sha256`；**不要**修改 `admin-web/nginx.conf` 与线上 `nginx-live/`。

### 4. 后端（jar）

```powershell
mvn -B -pl backend test                      # 必须全绿
mvn -B -pl backend package -DskipTests
```
- **产物**：`backend/target/backend-*.jar`
- **期望**：复制成 `D:\xiangmu-wenjian\releases\backend-1.0.8.jar` + 同名 `.sha256`。

## 三、汇报格式（四个产物都要）

```
产物名 | 绝对路径 | 大小(MB) | SHA256
四套测试结果（后端/管理端/手机端/收银端 各多少通过、几失败）
构建过程中遇到的报错原文（没有就写"无"）
```

## 四、明确禁止

- ❌ **不要部署到云服务器、不要 SSH、不要动 `/opt/dajin-system`、不要动 `docker-compose*.yml` 与 `.env`**
- ❌ **不要 `git push`**（提交与快照由运维负责）
- ❌ 不要修改 `nginx-live/conf.d/default.conf`（那是线上真实生效的配置）
- ❌ 不要覆盖 1.0.7 及更早的安装包/APK（要留回滚）

---

# 附：部署到云服务器（**由运维执行，不要交给 Codex**）

> 原因：需要 SSH 私钥、生产数据库备份、镜像回滚标签；这些都不该给到自动化工具。

运维（老板那边）执行时的步骤：

1. **备份**：整库 dump + 记录当前镜像标签（回滚用）
2. **同步代码**：把工作区改动同步到服务器 `/opt/dajin-system`（或按既有发布流程）
3. **构建镜像**：`docker compose -f docker-compose.release.yml -f docker-compose.override.yml build`
4. **升级**：`... up -d`（后端启动时会自动跑 `SchemaCompatibilityMigration`，**迁移可重复执行**）
5. **验证**：
   - 六个容器 `healthy`
   - 公网：管理端首页 200、H5 首页 200、`/api` 正常（未登录 401）
   - 手机端 / 收银端 / 报表 / 打印**时间一致**（时区修正后）
   - 关键业务抽查：加工单列表能打开、老单能编辑/打印、库存金额口径正常
6. **交付**：把新的收银端安装包与 APK 发给店里安装；给老板回滚命令与备份文件路径
