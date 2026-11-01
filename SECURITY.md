# Security policy

## Supported versions

Only the [latest release](https://github.com/ozsinyol/tv-alici/releases/latest) receives fixes. The app updates itself (TV menu > Check for updates).

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private reporting instead: **Security** tab > **Report a vulnerability** ([direct link](https://github.com/ozsinyol/tv-alici/security/advisories/new)). If that is unavailable, contact the maintainer through their GitHub profile and share details only privately.

Helpful details: affected version (`vNN`, shown in the TV menu), what an attacker needs (same Wi-Fi network? a PIN?), and steps to reproduce.

## Scope and design notes

The app runs two network services that are meant for a **trusted local network only**:

- the AirPlay receiver (port 7000), where unknown devices must be approved on the TV (trust is remembered per IP address), and
- the web interface (port 8080), protected by a random 4-digit PIN (a new one every time the app starts) with a 5-minute lockout per device after 5 wrong attempts.

Traffic on both is plain HTTP/RTSP on the LAN, as in the AirPlay protocol itself. **Do not expose ports 7000 or 8080 to the Internet.** Anyone on the same network who can see the PIN on the TV screen can use the web interface; this is by design.

In scope: remote crashes or code execution, authentication or approval bypass, unsafe update installation (the updater must only install APKs with the pinned package name, version and certificate), and information leaks. Out of scope: attacks that require a malicious app on the same device, and physical access to the TV.
