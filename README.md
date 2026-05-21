# Lido VPN

Modern Android VPN client based on Xray-core.

## Features
- **Xray Engine:** Supports VLESS, Trojan, VMess.
- **Smart Routing:** "Bypass LAN & Russia" mode for optimal performance.
- **App Filtering:** Select which apps should use the VPN.
- **Server Health Check:** Built-in ping and Telegram availability testing.
- **Automatic Updates:** Check for new versions directly from the app.
- **Dark/Light Theme:** Native Material 3 UI.

## How it works: Bypass LAN & Russia
The app uses GeoIP and GeoSite routing rules to ensure:
1. **Local traffic** (LAN) stays local.
2. **Russian websites and services** connect directly (no VPN) for better speed and access to local services.
3. **Restricted content** is automatically routed through the VPN.

## Development
- Language: Kotlin
- UI: Jetpack Compose
- Core: libv2ray (Xray)

## License
MIT
