# PingClash user guide

This guide covers the main features, common tasks and settings of PingClash 0.2.3. The app runs on Android 10 and later, routes traffic through the Android VPN interface, and does not need root. You need your own proxy subscription or configuration file; keep the links, passwords and keys in it safe.

## 1. Installing and connecting for the first time

Download the APK from the project's GitHub Releases. It is built for arm64-v8a devices, which covers nearly all current Android phones. Once installed, the bottom navigation has Home, Proxies, Profiles and Settings. There is no PingClash account to sign in to.

### Setup guide

The first time you open the app, it shows a setup guide with seven steps:

1. **Choose a language**: pick the language the app uses (currently 简体中文, English and 日本語; this guide follows the language). The app does not take this from the system. The choice applies at once, and you can change it later in Settings → Language & appearance.
2. **Welcome and user agreement**: explains that PingClash is a client with no servers, subscriptions or accounts of its own, and shows the user agreement. Tap Agree to continue. If you do not agree, tap Exit: the app closes and shows the agreement again next time.
3. **How it works**: a short summary of importing, connecting and picking nodes, with the full user guide built into the app (no network needed).
4. **Your profile**: import a subscription, restore a backup from another phone, or do it later.
5. **IF YOU WANT**: choose Proxy, Direct or Default for a few common services. See Service routing below. More services by country and region are in Settings → Service routing.
6. **Blocking**: choose whether to block ads, or ads and tracking. See Ad blocking below.
7. **Ready**: review what you set and go to Home. If a usable profile exists, you can also connect straight away.

Every step after the language and the agreement can be skipped, and Skip setup at the top goes straight to Home. After skipping, Home reminds you that you can finish setup any time in Settings → Setup guide. If the agreement changes later, the app asks you to read the agreement again without repeating the whole guide. The agreement and this guide are also under Settings → About & licenses.

The user agreement has 14 sections: what the software is and its licence, no servers or subscriptions provided, lawful use, data staying on the phone, what the app connects to, the VPN and other permissions, the risks of imported configurations, the limits of service routing and ad blocking, availability, third-party components, limitation of liability, changes to the agreement, feedback, and language. English is the primary text, and the version shown in the app is the one that applies.

To connect, open Profiles, tap the add button at the bottom right and choose how to import. After importing, tap the profile card to select it, then choose a node or group member under Proxies. Back on Home, choose a mode and tap the badge. Android shows its VPN permission window; once you confirm, a VPN icon appears in the status bar and a notification shows the service state.

The system's VPN permission and importing a subscription are separate. Importing only saves a configuration; it does not replace the permission. Normally only one app at a time can hold the Android VPN interface, so starting another VPN app may disconnect this one. On your first connection, check that you chose a real proxy node and not a placeholder that a subscription uses to show its expiry date or announcements.

## 2. Home and connecting

### The badge

Tap the connect button on the badge to start or stop the VPN. The badge flips with the connection state. Long-press and drag only moves the card; it never changes the node or mode, and the card swings back when you let go. Reduce effects gives a simpler display if you prefer a static interface.

Switching to another app does not disconnect the VPN. When you come back, check the badge, the system VPN settings and the service notification for the connection state. The recent-apps card is the app's window and the notification represents the service; they are different things. To stop the proxy, use the connect button or the Stop action in the notification.

### Rules, Global and Direct

| Mode | What it does |
| --- | --- |
| Rules | Chooses the group and outbound from the rules in the current profile and your local overrides. Suited to everyday routing. |
| Global | Sends traffic through the main proxy selection. Use it when you want one outbound for everything. |
| Direct | Uses the direct outbound. It never picks a proxy node. |

The wheel at the top of the Home card switches the mode. Rules, Global and Direct repeat round the wheel. The current mode sits under the blue hairline in bold, with the neighbouring modes lighter on each side:

- **Tap the left or right side** to switch to the mode on that side. Repeated taps keep stepping.
- **Drag or flick it** sideways: the wheel follows your finger, slows down on its own after you let go, passes the nearest mode slightly and springs back. You feel a light tick for each mode it passes and a click when it lands.
- The mode changes only when the wheel reaches it, so dragging never keeps reloading the routing. With Reduce effects on, the wheel moves straight to the next mode with no coasting or rebound.

Below the wheel you see the current profile, the node, where traffic leaves (region and network), and live speed while connected (remaining data otherwise). Tap this area for traffic, duration, latency and running state. To change the profile, go to Profiles; to change a node or group member, go to Proxies. Mode, profile and node are separate choices.

### Website connectivity

The bottom of the Home card has compact checks for common websites (in portrait it is part of the same card). Tap one to test that site, tap the lightning button to test the ones shown, and use the arrow to choose from more. A check only sends traffic when you start it, and opening the list does not sign in to anything.

Website checks use the phone's actual network path to see whether the current network can reach the site, which is different from a node's URL test. With the VPN off they use the current network; with it on they follow the active mode and rules. If a site responds over a verified HTTPS connection, it counts as reachable and shows the response time, including redirects to sign-in or CAPTCHA pages and statuses such as 403 or 429. DNS failures, timeouts, refused or reset connections and certificate problems count as unreachable. When ChatGPT, Claude or Gemini return a clear "not available in your region" page, or a site returns 451, the result is marked Restricted. One check does not prove that your account or all of a site's features work.

Old results update or clear when the network, the connection state or the profile changes. If a page opens but the check reports a problem, check again and compare with your browser. Do not turn off HTTPS certificate verification for the sake of a test.

### IP information

The summary on the Home card shows the country or region and network of your current IP. Tap it for the IP address, organisation, ASN and check time. While connected, it also shows the route the rules chose for this request (direct, or the group, node and protocol).

The check follows the phone's real path: through the current mode and rules with the VPN on, and over the phone's own network with it off, so it shows the address websites see right now. It runs again after you connect, disconnect, or change the mode, profile or main proxy, and you can tap Refresh in the details. Cloudflare returns the address. Network information beyond the country or region comes from data built into the app, and results are kept only in memory.

## 3. Adding and managing profiles

### Ways to import

On Profiles, tap **+** at the bottom right. The menu offers URL, clipboard, pasted configuration text, file, ZIP bundle, QR image, or taking a QR photo. Importing happens on one page: fill it in, check the source and tap Download and import. You can still go back and cancel while it downloads.

- **URL**: paste an HTTPS subscription address. The link is hidden by default and the host it will download from is shown below. The name can stay empty and be filled from what the server provides, or you can edit it. User-Agent is under Advanced.
- **Clipboard**: reads what you chose to import for you to check before confirming. Copying a link never downloads or activates anything by itself.
- **Text**: paste Clash YAML, configuration JSON or a list of supported node URIs. Base64-encoded subscription content can also be imported.
- **File**: uses the Android file picker, so the app never needs access to your whole storage.
- **QR code**: decoded offline from an image, or captured with the system camera. Use a clear original image and avoid glare and cropping.
- **ZIP bundle**: for configurations with local provider files, referenced by relative path inside the bundle.

When a web page opens `clash://install-config?url=`, check the source host and confirm the import first, then choose separately whether to activate the profile. A name in the link is display text only. Sources should use HTTPS. Treat any subscription link with a token as a password, and do not post it in public comments or screenshots.

### ZIP bundles and providers

A bundle has one config.yaml, config.yml or config.json at its root. Other files follow the configuration's relative provider paths, for example providers/nodes.yaml. File names and paths must match exactly; do not use absolute paths or parent-directory references.

Mihomo configurations can include proxy-providers and rule-providers. The app reads their sources and refresh settings, downloads them into its private storage, gives groups their members and processes rules in their original order. The source forms, content formats and overrides supported depend on the importer and the current core. Rule providers are recognised by content, so YAML lists published as .txt or .list also work. A single profile can hold up to 5,000 nodes, 512 groups and 50,000 rules, and a subscription download or import can be up to 8 MiB; anything beyond that is skipped and counted in the details. Source errors in the details name the provider by its position, so you can find it in the configuration.

When a configuration uses geographic or ASN categories, the matching rule data is used at run time. Keep the group names, source names and references in a configuration intact; in particular, do not rename a group without updating the rules that point to it. For file-based providers, import the content by file or ZIP.

### Profile cards

Profiles are listed vertically. Tap anywhere on a card to select it; the current choice is highlighted. The fill on a card shows the remaining quota, with transitions as traffic changes and when you select it. Quota comes from the subscription service's response headers. If a service does not report it, the app never treats the unknown value as unlimited.

The menu at the top right of a card offers update, rename, copy subscription link, details, export and delete. Details show usage, total, expiry, nodes and groups, source state and import results. After copying, remember that other apps may be able to read the system clipboard, and check that anything you share is really fit to share.

### Updates, names and User-Agent

Updating a subscription fetches its source again and keeps the current working configuration to fall back on if that fails. Choose the automatic update interval in Settings → Automation; the default is daily. You can rename a profile as you like, which does not affect your server account. Some services return different formats depending on the User-Agent; when needed, enter the value your provider specifies on the import page or in the profile's settings. User-Agent only identifies the request and is not a credential.

Quota and expiry come from the server's subscription-userinfo information. Total usage is usually upload plus download, and dates use the device's time zone. The app's local session traffic may differ from how your provider bills. Billing, reset cycles and renewal are up to the provider. Check the new quota after an update.

### Exporting and deleting

Exporting is for keeping or moving your own configuration. A full configuration may contain account credentials, server details and subscription links, so store it somewhere protected. A redacted diagnostic report is for reporting problems and serves a different purpose; never publish a full configuration as a report. Deleting a profile removes it from the app, and uninstalling or clearing the app's data also removes local profiles.

## 4. Proxies, groups and latency

Proxies keeps the subscription's group order, group names and memberships. Swipe between groups and tap a node to select it; nested groups open further. Automatic selection, fallback and similar groups run as configured. A group name is not a node protocol and does not stand for a particular server address.

The menu at the top right offers a compact two-column or single-column layout, plus display and sorting by name or latency. Search finds members of the current profile. The protocol name appears as a subtitle, and long names and large text adapt to the layout. Switching pages and returning from diagnostics keeps your place where possible.

### URL latency

The lightning button starts a URL test run by Mihomo, comparing how long it takes to reach the target through a node. Node tests can run without this app's VPN, because they test the node's ability to connect, not whether all apps on the phone are going through the VPN. If another VPN is running, its overhead may be part of the path.

Batch tests expand and de-duplicate a group's nodes, run with limited parallelism and show results as they arrive. Tap the running icon again to cancel. You can select other nodes during a batch. Results stay: switching pages, leaving the app, or connecting and disconnecting the VPN do not clear finished results. After a profile update, only nodes that were removed or changed lose their results, and changing the test target, timeout or DNS clears all of them. Connecting or disconnecting stops a test in progress. The network, the target site and the first handshake all affect the number, so compare results using the same target and timeout.

Latency colours are a visual aid: green for lower, yellow for moderate, and higher latency or failure in their own states. A number describes this test, not long-term quality. No result, testing, timed out and a valid latency are different states, and a failure is never shown as a plausible-looking number.

### TCP and node details

Long-press a node for details, where you can run a URL test, check TCP or diagnose that node. The TCP test connects to the node's host and port and shows whether the entry is reachable. The URL test goes through the proxy protocol to the target and shows more of the path. A TCP connection does not mean proxy authentication or the target site will succeed.

For UDP protocols, the TCP measure shows as not applicable rather than as a failure. For this app's own VPN, entry probes use protected sockets on the underlying network, so the proxy entry does not loop back into the tunnel. While another VPN is running, some direct measurements are limited by its path. Once a node has finished its own URL test, you can look at its diagnosis without waiting for the whole list.

## 5. Diagnostics, notices and connections

### Connection diagnostics

Open diagnostics from a node's details or from Settings. Results are shown step by step: network, entry DNS, TCP, TLS and the proxy path. Steps a protocol does not use are marked skipped. Diagnostics do not scan the internet, and they never bypass certificate verification to explain a failure.

Start with the stage that failed: DNS means name resolution, TCP means the entry connection, TLS involves certificates, host names and the handshake, and the proxy path also involves protocol authentication and reaching the target. Timeouts, resets, refused connections and certificate errors are different things, and a single error does not prove the network is under attack. Try again on the same device after switching between Wi-Fi and mobile data, and compare.

**Check a domain or IP manually** at the bottom of the diagnostics page opens a direct check: enter a server domain or IP and a port, optionally with a TLS handshake and SNI, and IPv4 or IPv6. The check does not go through a node. It always uses the phone's physical network (Wi-Fi or mobile data), and goes around the tunnel when the VPN is on. It only tells you whether this phone can resolve and reach that address directly, not whether a proxy works.

### Reminders and warnings

Yellow reminders appear at the top right and dismiss themselves after a countdown. Red warnings stay until you swipe them away or open the details, which show the stage, the reason and a suggestion, so error text does not pile up on the page. If an import fails, check the source or field hint first; you do not need to send your whole subscription to anyone.

Certificate handling follows your configuration. If verification is skipped, the server's identity cannot be reliably confirmed, so know the source and check your provider's certificate settings. A wrong system clock, an SNI mismatch or an untrusted certificate can all affect TLS. Ordinary error messages can be screenshotted; windows that show credentials directly have separate screenshot protection.

### Connections

Settings → Connections shows sessions. When the VPN is off or nothing is active, a status card takes the list's place. With the matching display options on, you can see the target, network type, matched rule type, actual outbound, connection time and traffic. Showing connection targets is a separate option; turn it on when you need it. These details can reveal personal browsing, so check them before sharing.

Swipe a connection left to reveal the delete action, and keep swiping past the threshold to close it directly. This closes the current connection; it does not delete a node or subscription. Apps and web pages still using the network may quickly open a new one. Whether changing a node affects existing connections right away depends on the connection and its route.

### Sharing reports

Diagnostic reports are generated on the phone. Preview before sharing and check that you have not added anything sensitive by hand. For feedback, the app version, Android version, steps, failing stage and a checked report are usually enough. Do not provide account passwords, UUIDs, full subscription addresses or device identifiers. Crash reports are also only viewed and shared by you, and are never uploaded automatically.

## 6. DNS, routing and network settings

### DNS

To keep the subscription's own DNS behaviour, use the configuration's DNS option. For manual setup, choose the app's DNS mode and an encrypted DNS address. DNS over HTTPS uses an HTTPS address, and DNS over TCP uses the matching server settings. Note the old value before changing it, and avoid changing node, DNS and rules together, or you will not know which one made the difference.

Fake-IP, domain rules, sniffing and similar behaviour depend on your configuration and how the core runs. A DNS address is not a proxy address, and a successful DNS check does not mean a site is reachable. If a browser or the system has its own private DNS enabled, take that into account when working out the final resolution path.

### Per-app VPN

**All apps** lets every app covered by the VPN's routes enter the interface. **Exclude selected** keeps the selected apps out. **Only selected** lets only the selected apps into the VPN. The list has search and filters for selected or system apps. Check the app name and package name before ticking, and be careful with system components.

Per-app VPN sets which apps the Android VPN covers, while the mode decides the outbound after traffic reaches the core. Both can affect traffic. For settings that need the system VPN interface rebuilt, disconnect and reconnect when prompted. If you use lockdown mode, check how excluded apps combine with the system's blocking.

### Outbound rules and local network

When keeping subscription rules, you can add local overrides. A rule's type, match, outbound and rule-provider references must correspond, and order affects matching. Save after writing rules and look at the check result. A running rule's number is not a line number in the configuration file.

The Local network option in Settings → Network & connection decides how LAN traffic is handled. To reach a router, printer or NAS, choose between Proxy LAN, Direct LAN and Block LAN. Do not open LAN listening unless you know why you need it. The app's control interface uses restricted local access, so there is no need to expose a control port to other devices.

### Service routing

Settings → Service routing lists common services by country and region: U.S., U.K., Japan, South Korea, China – Hong Kong, China – Taiwan and China – Mainland, plus three worldwide groups: AI, Games, and Work & social. Each service offers:

| Option | Effect |
| --- | --- |
| Proxy | The service uses the main proxy (the current profile's main group or selected node), or a group you pick from the profile. |
| Direct | The service does not go through the proxy. |
| Default | No rule is added, so the subscription's own rules decide. |

When you choose Proxy and the current profile has groups, a line under the service's name shows which proxy it uses, Main proxy at first. Tap it to pick one of the profile's groups instead, for example to send Netflix through a Japan group. If you switch to a profile without that group, the service uses the main proxy for now and the line says so; it returns to the group when you switch back.

These choices belong to PingClash, not to any subscription: they survive switching or updating subscriptions and are backed up with your settings. They apply in Rules mode, after your custom rules and before the subscription's rules. Changes take effect at once while connected, with no need to disconnect. Ad blocking and the region tabs at the top stay in place and only the service list below scrolls. **Clear all choices** at the top right puts every service back to Default and leaves ad blocking alone.

A service's domains come from rule data built into the app (GeoSite); no rules are downloaded. When one service is inside another (YouTube is part of Google), the narrower one goes first, so "Google through the proxy, YouTube direct" can both hold. Proxy only means using the main proxy. Whether you can watch a region's content still depends on where your node is and on the service's own restrictions.

### Ad blocking

**Ad blocking** at the top of Settings → Service routing offers Off, Ads, or Ads & tracking. It blocks known domains using the built-in rule data and applies in Rules mode. Some sites or apps may stop working properly as a result; if so, turn it off here.

### Connection settings

Network & connection has automatic reconnection, IPv6, UDP, MTU, connection timeout, test target, parallel test count and similar settings. The defaults suit most situations. MTU affects packet size, and too large or too small a value can change how things behave. Too many parallel tests also add momentary load on the phone and nodes. Change one setting at a time and watch the result, so you can compare.

## 7. The standard terminal

The terminal is Android `sh`, not Windows PowerShell and not a complete Linux distribution. It runs under PingClash's app UID and permissions, using the commands, directories and network the device already has. Its PTY supports a normal keyboard, coloured output, pipes, redirection and interactive input.

Read the permission note the first time you open it. Tap the terminal area for the keyboard, and press Enter to run. Ctrl+C interrupts the foreground command, and exit leaves the shell. Terminal content can be copied or screenshotted; before sharing, check that commands and output contain no subscription addresses or passwords you typed.

These commands show what is available; still check the output yourself:

```sh
pwd
id
uname -a
ls -la
printf '\033[36mPingClash terminal\033[0m\n'
printf 'one\ntwo\n' | wc -l
command -v ping
command -v curl
```

Once you know what tools exist, you can use the network commands your device supports. This example uses a public domain and needs no subscription credentials:

```sh
ping -c 3 example.com
```

Some Android systems lack particular commands, or restrict ICMP, raw sockets and system directories. command -v tells you whether a command exists. The terminal does not install extra software and never gains root through the app's permissions. System commands follow their actual route, which is not the same as being forced through a selected node.

Do not run remote scripts you do not understand. The terminal can access the app's own files, and redirection leaves files behind. Even though the app keeps no persistent command history, commands themselves may still write content. Avoid showing environment variables, file contents or URLs with credentials in public screenshots.

## 8. Appearance, background and local privacy

### Appearance

In Language & appearance, choose 简体中文, English, 日本語 or the system language. Choose Ocean and waves, Drifting clouds, Plain background or your own picture, and edit preset and custom colours. With a picture, Fill lets you pinch and drag to pick the part that covers the screen, while Fit shows the chosen part whole and fills the rest with the picture's edge colour or a colour you pick. The colour picker offers grid, spectrum and sliders. Pictures are re-encoded and saved on the phone without the original's metadata. Light and dark options depend on what the current background supports. Titles and labels that sit directly on the background change colour to stay readable on it, including on your own picture. Reduce effects and Reduce transparency simplify animation and glass to your taste.

The glass navigation supports tapping and dragging to switch. Long-press dragging the badge is a separate gesture from page navigation. For detailed running information, open the details panel on Home; display options decide whether it shows speed, traffic, service and memory. Speed in the status notification has its own setting.

### Landscape and tablets

In landscape, the bottom navigation becomes a frosted-glass sidebar on the left. Expanded, it shows the app name, pages and connection state; the button at the top left collapses it to icons, and the choice is remembered. Tablets default to expanded and phones held sideways to collapsed. In tablet portrait, the navigation bar keeps phone proportions and is centred, Settings and Profiles keep a readable width, and Proxies shows more columns to suit the screen.

### Background and reconnecting

Automatic reconnection handles switches between Wi-Fi and mobile data. Keep the VPN notification visible and allow the notification permission in the system; its title shows the current mode. If battery management limits background services, adjust battery optimisation and background permissions in the app's system info; menu names differ between manufacturers.

In Android's VPN settings, "Always-on VPN" keeps the service running and "Block connections without VPN" blocks anything outside the tunnel. After turning them on, check that your browser and common apps work before keeping them on. Removing the recent-apps card is not the same as stopping the VPN service.

### Automation and shortcuts

Add the PingClash tile to Quick Settings, or add the widget to your home screen, to connect or disconnect directly; the widget also shows current speed. The first connection needs VPN permission inside the app, after which the tile and widget work without opening it.

Settings → Automation offers:

- Connect on boot (off by default).
- Subscription update interval: off, every 6 or 12 hours, daily (default), every 2 or 3 days, or weekly. Updates run in the background when there is a network, over HTTPS only, and the current configuration stays if a download or check fails.
- Connect by network (off by default): disconnects on trusted Wi-Fi and connects on other networks, acting only when the network changes. Android provides the Wi-Fi name only with location permission, and to work while the app is closed you need to choose "Allow all the time".

Backup in Settings → Privacy and backup saves your settings to a file you choose. By default it leaves out subscription links, node passwords and usage. With **Include profiles**, the file contains credentials, so keep it safe. Restoring replaces current settings and adds profiles from the backup that are not already there; subscriptions download again.

### Data and sharing

Profiles and preferences are stored encrypted on the phone with Android Keystore and AES-GCM, and the app disables system cloud backup and migration. There are no ads, telemetry or automatic crash reports. Fetching subscriptions, updating providers, DNS, latency tests and proxy connections reach the services you configured or chose, as those features require.

Encrypted storage cannot protect plain text you copy or export, and it does not replace a screen lock. Before changing devices, back up in Privacy and backup. Uninstalling, clearing data or a change in the system's keys can affect recovering local data. Do not commit subscriptions, private keys or full logs to public repositories.

About & licenses shows the app version, Mihomo version and open-source licences. These versions are enough to identify a problem when you report one; no personal details are needed. For features, see this guide; for the project and source code, see the [README](../README.md).
