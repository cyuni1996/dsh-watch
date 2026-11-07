# DSH Watch

<p align="center"><img src="docs/media/icon.png" width="110" alt="DSH Watch whale-tail icon"></p>
<p align="center">Continue DeepSeek Harness conversations from an Android watch.</p>

[简体中文](README.md) · [First connection](docs/INSTALL.en.md) · [Help](docs/HELP.en.md) · [Downloads and versions](https://github.com/cyuni1996/dsh-watch/releases)

DSH Watch consists of a desktop plugin and a watch app. Read conversations, send messages, and get replies on the watch. Both devices work in the same conversation while the desktop runs DSH and remains reachable.

## Features

- Start conversations, browse projects, search history, and open the same conversation on the desktop.
- Type or record voice input. Desktop DSH supplies transcription, and you can edit the text before sending.
- Pick an available desktop model and supported reasoning level, track progress, and stop a reply.
- Check account usage from the rice-bowl icon. Scroll with the crown, change text size, or enable automatic read-aloud.
- Use the desktop wizard for first-time setup, device authorization, and connection settings.

## Quick setup

1. Connect the computer running DSH and the watch to the same local network.
2. Follow the [first-connection guide](docs/INSTALL.en.md#1-install-the-desktop-plugin) to add the DSH Watch project index in Discover and install the plugin. If Custom plugin indexes is unavailable, download `dsh-watch.tgz` from [GitHub Releases](https://github.com/cyuni1996/dsh-watch/releases) and add it through your current DSH plugin manager.
3. Enable the plugin under Installed if it is off, then reload DSH as prompted. Open Settings → Watch connection, select a LAN address reachable from the watch, and open the installation wizard.
4. Follow the wizard to connect wireless debugging, install the watch app, and select Connect to DSH and open. The watch should show Online.

See [First connection](docs/INSTALL.en.md) for the complete procedure, signature conflicts, and updates.

### Ask an AI assistant to install it

> Help me install DSH Watch. Follow https://github.com/cyuni1996/dsh-watch/blob/main/docs/INSTALL.en.md and prefer the current DSH plugin manager and published release package. Tell me when I need to authorize the watch. If the package signature differs, preserve the existing app and pairing.

## Compatibility

The watch app requires Android 11 or later and is designed for round displays. Use Smart mode on Xiaomi Watch 5. The wizard can use an installed ADB, or download Google Platform-Tools on Windows x64. Models, usage, voice, and some confirmations depend on the desktop DSH host. See [Help](docs/HELP.en.md).

DSH Watch is an independent community project. Code and original blue-and-gold whale-tail artwork use the [MIT license](LICENSE). [Build and package](docs/BUILD.md) · [Report an issue](https://github.com/cyuni1996/dsh-watch/issues)


<!-- support-footer:start -->
## Support the author

If this project is useful to you, you're welcome to buy the author a cappuccino ☕. Thank you for using it, sharing feedback and supporting it in your own way.

### Alipay / WeChat Pay

<a href="docs/media/support-cny.png"><img src="docs/media/support-cny.png" width="520" alt="Optional support QR codes for Alipay and WeChat Pay" /></a>

### Cryptocurrency (BTC / ETH / USDT)

<a href="docs/media/support-crypto.png"><img src="docs/media/support-crypto.png" width="640" alt="Optional support QR codes for BTC, ETH and ERC20 USDT with network labels" /></a>

**BTC · Bitcoin mainnet**

```text
bc1qyae989cu0mx0zx2je5xtpft09dm4fwplz3g09qnarf76y3jzz5ksdh8qcw
```

**ETH / USDT · Ethereum mainnet (USDT: ERC20)**

```text
0x18fe56573647a2b7a3230181460c47b3731dba8b
```

<sub>Tips are entirely voluntary, independent of feature access, and do not include additional services. Click an image to view its original size.</sub>
<!-- support-footer:end -->
