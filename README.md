# 打金店管理系统 V1.0.0

面向黄金门店的收银、库存、会员、审批和经营分析系统，提供 Electron 前台、Vue 管理端、Uni-App 手机端和 Spring Boot 后端。

## 功能特性

- 前台：扫码开单、按克/按件计价、旧金抵扣、组合支付、审批、ESC/POS 小票、A4 质保单、断网队列。
- 管理端：仪表盘、商品/库存、金价、审批、销售、会员、人员、提成、财务和系统设置。
- 手机端：店长看板/审批/报表/会员，销售会员/回访/移动开单/业绩，角色隔离和实时推送。
- 后端：JWT/RBAC、WebSocket、幂等同步、库存版本校验、MinIO 图片和 MySQL/Redis 持久化。

## 架构

```text
Electron收银 ─┐
Vue管理端 ────┼─ HTTP/WebSocket ─ Spring Boot ─ MySQL
Uni-App/H5 ───┘                     ├─ Redis
                                     └─ MinIO
                         Nginx 托管管理端并代理 /api、/uploads
```

## 技术栈

后端 Spring Boot 2.7 / Java 17 / MyBatis / MySQL 8 / Redis 6 / MinIO；前台 Electron + Vue3 + Element Plus；管理端 Vue3 + Vite + Element Plus + ECharts + Pinia；手机端 Uni-App + uView Plus；部署 Docker Compose + Nginx。

## 快速开始

1. `Copy-Item .env.example .env`，修改生产密码和 JWT_SECRET。
2. `docker compose up -d --build`，等待所有服务 healthy。
3. 浏览器访问 `http://localhost`；安装 `front-pc/release` 中的 Windows 安装包并配置 API 地址。

生产服务器建议从 GitHub 克隆后使用发布编排：

```bash
git clone GITHUB_REPOSITORY_URL dajin-system
cd dajin-system
cp .env.production.example .env
# 编辑 .env，替换所有 CHANGE_ME
docker compose -f docker-compose.release.yml up -d --build
docker compose -f docker-compose.release.yml ps
```

Windows 收银端安装程序不提交到 Git 仓库，应放在 GitHub Release 附件中。

演示账号：`admin/admin`（管理员）、`manager/admin`（店长）、`cashier/admin`（前台）、`sales/admin`（销售）。首次登录后建议立即修改密码。

## 文档

- [部署文档](docs/部署文档.md)：服务器、客户端、打印和备份部署。
- [接口文档](docs/接口文档.md)：REST/WebSocket、鉴权和幂等约定。
- [数据库文档](docs/数据库文档.md)：38 张表、字段、索引和关系。
- [操作手册](docs/操作手册.md)：四类角色日常操作与 FAQ。
- [开发文档](docs/开发文档.md)：模块设计、编码、测试和构建。
- [手机端打包说明](docs/手机端打包说明.md)：HBuilderX、Android、iOS。

## 项目结构

```text
dajin-system/
├── backend/       Spring Boot API
├── front-pc/      Electron 收银端与 release 安装包
├── admin-web/     Vue3 管理端
├── mobile/        Uni-App/H5 手机端
├── db/            schema.sql
├── docs/          交付文档
├── backup/        数据库备份
├── docker-compose.yml
├── backup.ps1 / restore.ps1
└── .env.example
```

## 版本历史

V1.0.0：完成四端核心业务、38 张表、Docker 一键部署、MinIO 图片、JWT/WebSocket、离线幂等同步、Electron NSIS 安装包、H5 构建和备份恢复脚本。

## 许可证

内部商业软件，未经门店系统维护方书面许可不得复制、再分发或用于其他商业项目。
