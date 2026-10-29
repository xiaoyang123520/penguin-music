# 企鹅音乐 Penguin Music

基于 [jussichords](https://github.com/easyTIDollar/jussichords)（GPL-3.0）的 Windows 桌面移植与定制版，当前桌面版版本 **2.2.0**。

> 本仓库为**私有仓库**，仅作项目源码备份与同步，不公开发布。

## 内容

- `desktop/` — 企鹅音乐桌面版（Compose Desktop 移植层、播放引擎、MV、桌面歌词、应用内更新等），源码在 `desktop/src/`，构建脚本在 `desktop/tools/`，移植全过程记录见 `PORTING.md`
- `app/` — 上游安卓工程（含曜石灰 + 红点缀定制主题、自建更新通道等定制改动）
- `ncmapi/` — 网易云音乐 API 接口层（weapi/eapi 直连），桌面与安卓共用
- `deploy/` — 安卓定制版在线更新清单

## 说明

- 桌面版构建走手工管线（见 `PORTING.md` 与 `desktop/tools/build.sh`）；第三方依赖 jar（`desktop/libs/`）、JCEF 运行时、构建产物与发行包体积过大，未入库。
- 桌面版在线更新源为程序内常量 `PENGUIN_VERSION_URL`（指向自有下载站的版本清单），属公开地址，无任何密钥。
- 本仓库不含任何账号、密码、API 密钥或签名文件。

## 许可与致谢

- 本项目是 jussichords 的衍生作品，遵循 **GPL-3.0**（见 `LICENSE`）。
- 上游：https://github.com/easyTIDollar/jussichords
- API 参考：https://neteasecloudmusicapienhanced.js.org/
