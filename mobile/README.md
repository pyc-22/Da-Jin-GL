# 打金店移动端

## H5 开发预览

```powershell
cd D:\xiangmu-wenjian\dajin-system\mobile
pnpm install
pnpm dev
```

打开 `http://127.0.0.1:5175/`。接口默认指向 `http://localhost:8080`，可用 `VITE_API_BASE` 覆盖。

## Uni-App 打包

使用 HBuilderX 打开 `mobile` 目录，选择运行到浏览器、Android 或 iOS。`src/pages.json` 和 `src/manifest.json` 为标准 Uni-App 配置；原生扫码、拨号、图片选择通过 `uni.scanCode`、`uni.makePhoneCall`、`uni.chooseImage` 接入。

## 演示账号

`manager/admin` 为店长，`sales/admin` 为销售，`admin/admin` 为管理员。移动端断网时保留缓存查看，开单提交按钮自动禁用。
