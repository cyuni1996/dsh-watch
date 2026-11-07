# DSH Watch

<p align="center"><img src="docs/media/icon.png" width="110" alt="DSH Watch 鲸尾图标"></p>
<p align="center">在 Android 手表上继续电脑端的 DeepSeek Harness 会话。</p>

[English](README.en.md) · [首次连接教程](docs/INSTALL.zh.md) · [使用帮助](docs/HELP.zh.md) · [下载与版本](https://github.com/cyuni1996/dsh-watch/releases)

DSH Watch 由电脑插件和手表应用组成。手表能查看电脑上的会话、发送消息和读取回复。电脑运行 DSH 并保持可达时，两端使用同一条会话。

## 功能

- 在手表创建会话、浏览项目、搜索历史，并在电脑打开同一条会话。
- 支持键盘和语音输入。语音转写由电脑端 DSH 提供，识别文字可先编辑再发送。
- 选择电脑端可用模型和支持的思考强度，查看回复进度并停止生成。
- 用饭碗图标查看账号余量。通过表冠滚动、调整字号或开启自动朗读。
- 桌面端提供首次安装向导、设备授权管理和连接设置。

## 快速安装

1. 让运行 DSH 的电脑与手表连到同一个局域网。
2. 按[首次连接教程](docs/INSTALL.zh.md#1-在电脑上安装插件)，在 DSH「发现」添加 DSH Watch 项目索引并安装。没有「自定义插件索引」时，可以从 [GitHub Releases](https://github.com/cyuni1996/dsh-watch/releases) 下载 `dsh-watch.tgz`，用当前 DSH 的插件管理器添加。
3. 在「已安装」确认插件已启用，按提示重载 DSH。进入「设置 → 手表连接」，选择手表可访问的局域网地址，再打开安装向导。
4. 依向导连接无线调试、安装手表应用，然后点击「连接 DSH 并打开」。手表显示「在线」即连接成功。

完整步骤、签名冲突处理和更新流程见 [首次连接教程](docs/INSTALL.zh.md)。

### 交给 AI 助手安装

> 帮我安装 DSH Watch。按 https://github.com/cyuni1996/dsh-watch/blob/main/docs/INSTALL.zh.md 执行，优先用当前 DSH 的插件管理器和官方发布包。需要在手表授权时停下来告诉我。遇到签名冲突，保留现有应用和配对。

## 兼容性

手表应用要求 Android 11 或更新版本，面向圆屏布局。优先使用小米手表 5 的智能模式。桌面向导可使用系统中的 ADB，Windows x64 上也可按向导获取 Google Platform-Tools。模型、余量、语音和部分确认操作取决于电脑端 DSH 的可用能力。详见 [使用帮助](docs/HELP.zh.md)。

DSH Watch 是独立社区项目。代码与原创蓝金鲸尾图案采用 [MIT 许可证](LICENSE)。[构建与打包](docs/BUILD.md) · [提交问题](https://github.com/cyuni1996/dsh-watch/issues)


<!-- support-footer:start -->
## 支持作者

如果这个项目对你有帮助，欢迎自愿请作者喝一杯卡布奇诺 ☕。感谢你的使用、反馈和每一份支持。

### 支付宝 / 微信投喂

<a href="docs/media/support-cny.png"><img src="docs/media/support-cny.png" width="520" alt="感谢投喂：支付宝与微信收款码" /></a>

### 数字货币投喂（BTC / ETH / USDT）

<a href="docs/media/support-crypto.png"><img src="docs/media/support-crypto.png" width="640" alt="感谢投喂：BTC、ETH 与 ERC20 USDT 收款码及网络说明" /></a>

**BTC · Bitcoin 主网**

```text
bc1qyae989cu0mx0zx2je5xtpft09dm4fwplz3g09qnarf76y3jzz5ksdh8qcw
```

**ETH / USDT · Ethereum 主网（USDT：ERC20）**

```text
0x18fe56573647a2b7a3230181460c47b3731dba8b
```

<sub>赞赏完全随心，与功能使用无关，也不附带额外服务承诺。点击图片可查看原图。</sub>
<!-- support-footer:end -->
