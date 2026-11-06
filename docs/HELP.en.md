# DSH Watch help

[Project](../README.en.md) · [First connection](INSTALL.en.md) · [简体中文](HELP.zh.md)

## Watch controls

| Action | Where it happens |
| --- | --- |
| Send a message | Tap the text field to type and the tail button to send. The button changes to a stop square while a reply is running. |
| Voice input | Tap the mic to start and tap again to stop. Desktop DSH transcribes the recording. Edit the draft before sending. |
| Find or start a conversation | Open the log from the upper left to search or switch between all, pinned, and archived. Use + to start a conversation. It is created on the desktop when you send the first message. |
| Manage a conversation | The three-dot button opens details with rename, pin, archive, restore, and Open on desktop. |
| Use a message | Long-press a reply to copy, read aloud, quote, or bring back the latest question. Long-press your message to copy or reuse it. |
| Change model | Use the bottom model control. Follow the desktop or select an available model and supported reasoning level. Saving takes effect on the next send. |
| Check usage | Tap the bowl icon or pull down from the title area. The panel shows the balance and usage windows available from desktop DSH. |
| Handle confirmations | Supported prompts appear on the watch. If asked to handle a prompt on the computer, open the same desktop conversation and answer it there. |
| Adjust the round-screen UI | Use the crown to scroll, double-tap the chat to jump to the latest reply, or swipe back from a subpage. Appearance settings cover text size, colors, history density, decorations, and motion. |

Settings include automatic read-aloud and haptics. The computer generates speech audio and the watch plays it. Transcription and read-aloud depend on available desktop voice services.

## Manage connections

- **Switch computers.** Open Settings → Computer connection and select or add a computer. Drafts are kept per computer and conversation.
- **Manual LAN pairing.** In Settings → Watch connection on the desktop, select a reachable listen address and generate a ten-digit code. On the watch, use LAN scan or Manual connection. Enter the code on first pairing or after authorization expires. Normal reconnects reuse existing authorization.
- **Remote access.** Provide your own route to the plugin’s HTTPS listener. Enter its HTTPS URL and the ten-digit code under Remote connection. DSH must be running and reachable. Prefer the LAN path for everyday use.
- **Revoke a device.** Choose the device in the desktop Watch connection settings and confirm revocation. To reconnect afterward, pair the app again.

The watch system generates a six-digit wireless debugging code for installation. The desktop plugin generates a ten-digit code for app authorization. It expires after five minutes and is single-use.

## Troubleshooting

**Watch connection is missing.** Check that the plugin is installed in the DSH profile you are using and reload it. Desktop and Web manage their own profiles.

**The wizard cannot find the watch.** Keep it awake and unlocked, enable wireless debugging, and make sure both devices share a network. Refresh the devices. For a paired watch that is not connected, use the address and port from its main wireless debugging screen.

**The watch keeps connecting.** Confirm desktop DSH is running, its listen address is reachable, and a firewall or guest network is not isolating the devices. If the LAN scan finds nothing, try the address manually.

**The APK signature does not match.** Keep the current app and check the APK source. In-place updates require the same signature. See [First connection](INSTALL.en.md#updates).

**Voice, models, or usage are unavailable.** Check the account, models, and voice features in desktop DSH. Usage may be unavailable for some accounts. Handle desktop-only confirmations in the original conversation.

When reporting an issue, include the plugin version, watch app version, watch model, and steps to reproduce. Redact pairing codes, device IDs, network addresses, and private conversations.
