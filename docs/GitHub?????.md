# GitHub 自动构建与部署

仓库地址：`https://github.com/pyc-22/Da-Jin-GL`

## 工作方式

- 推送到 `main`：自动运行后端、管理端、移动端和收银端测试，并构建三端产物。
- 确认一个版本：创建并推送 `v` 开头的 Git 标签，例如 `v1.0.1`。
- 推送标签后：自动构建 Docker 发布镜像、生成 Windows 收银端安装包并创建 GitHub Release。
- 配置服务器参数后：同一个标签构建成功后自动 SSH 登录服务器，切换到该标签并执行 Docker Compose 部署。

普通代码提交不会直接覆盖生产服务器。只有推送版本标签才会进入发布和部署流程。

## 首次服务器准备

服务器建议使用 Ubuntu 22.04/24.04，至少 2 核 4 GB 内存，并安装 Docker Engine、Docker Compose 插件和 Git。

首次在服务器执行：

```bash
sudo mkdir -p /opt/dajin-system
sudo chown "$USER":"$USER" /opt/dajin-system
git clone https://github.com/pyc-22/Da-Jin-GL.git /opt/dajin-system
cd /opt/dajin-system
cp .env.production.example .env
nano .env
```

`.env` 中替换所有 `CHANGE_ME`，并设置正式的 `MINIO_CORS_ORIGINS`。没有域名时可先使用：

```env
MINIO_CORS_ORIGINS=http://服务器IP,http://服务器IP:81
```

服务器上的 `.env`、`data/`、`backup/` 不提交到 GitHub，自动部署会保留它们。

## GitHub 配置

在仓库 `Settings -> Secrets and variables -> Actions` 中添加以下 Repository secrets：

| 名称 | 值 |
| --- | --- |
| `DEPLOY_HOST` | 服务器 IP 或域名 |
| `DEPLOY_PORT` | SSH 端口，默认 `22` |
| `DEPLOY_USER` | SSH 用户，例如 `root` 或部署用户 |
| `DEPLOY_PATH` | 项目目录，例如 `/opt/dajin-system` |
| `DEPLOY_SSH_KEY` | 部署用户对应的 SSH 私钥全文 |
| `DEPLOY_KNOWN_HOSTS` | 可选，服务器的 SSH host key |

然后在 `Variables` 中添加：

```text
DEPLOY_ENABLED=true
```

`DEPLOY_SSH_KEY` 只保存私钥，不要保存 `.env`。服务器 `.env` 直接留在服务器上。

## 发布命令

在本地确认测试通过后：

```powershell
git status
git add .
git commit -m "release: describe the completed update"
git push origin main

git tag v1.0.1
git push origin v1.0.1
```

标签工作流完成后，在仓库的 `Actions` 查看结果，在 `Releases` 下载收银端安装包。服务器部署完成后验证：

```text
管理端：http://服务器IP/
移动端：http://服务器IP:81/
```

## 回滚

回滚到上一个版本时，在服务器执行：

```bash
cd /opt/dajin-system
git fetch --tags origin
git checkout --detach v1.0.0-rc.1
docker compose -f docker-compose.release.yml up -d --build
docker compose -f docker-compose.release.yml ps
```

数据库和图片位于 `data/`，回滚应用版本不会删除它们。执行版本升级前应保留数据库备份。
