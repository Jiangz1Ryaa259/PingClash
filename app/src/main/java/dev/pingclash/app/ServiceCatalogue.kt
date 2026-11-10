// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.annotation.StringRes

/** A tab of Settings > Service routing: a country or region, or a worldwide group. */
internal enum class ServiceRegion(@StringRes val title: Int, val flag: String) {
    US(R.string.region_us, "🇺🇸"),
    UK(R.string.region_uk, "🇬🇧"),
    JAPAN(R.string.region_japan, "🇯🇵"),
    KOREA(R.string.region_korea, "🇰🇷"),
    // Every entry that belongs to China carries the Chinese flag.
    HONG_KONG(R.string.region_hong_kong, "🇨🇳"),
    TAIWAN(R.string.region_taiwan, "🇨🇳"),
    MAINLAND(R.string.region_mainland, "🇨🇳"),
    AI(R.string.region_ai, ""),
    GAMES(R.string.region_games, ""),
    SOCIAL(R.string.region_social, ""),
}

/**
 * One service the user can route. [geosite] categories come from the bundled GeoSite.dat;
 * [suffixes] and [full] are taken only from curated upstream lists, never guessed. [broad] rows
 * contain other rows (Google holds YouTube and Gemini), so their rules go after the narrower ones.
 * [checked] is the date of the desk check against the bundled data.
 */
internal data class RoutedService(
    val id: String,
    val name: String,
    val region: ServiceRegion,
    val geosite: List<String> = emptyList(),
    val suffixes: List<String> = emptyList(),
    val full: List<String> = emptyList(),
    val zhName: String? = null,
    val broad: Boolean = false,
    val checked: String = DESK_CHECK,
) {
    fun label(language: String) = if (language == "zh") zhName ?: name else name
}

/** The rows of the setup guide's short list; one row may set several services at once. */
internal data class GuideRow(val id: String, val name: String, val members: List<String>) {
    /**
     * A one-service row shows that service's name in the app's language, as Service routing does.
     */
    fun label(language: String): String =
        members
            .singleOrNull()
            ?.let { id -> SERVICE_CATALOGUE.firstOrNull { it.id == id } }
            ?.label(language) ?: name
}

internal const val ROUTE_PROXY = "proxy"
internal const val ROUTE_DIRECT = "direct"
internal val ROUTE_VALUES = setOf(ROUTE_PROXY, ROUTE_DIRECT)
internal const val BLOCKING_OFF = "off"
internal const val BLOCKING_ADS = "ads"
internal const val BLOCKING_TELEMETRY = "telemetry"
internal val BLOCKING_VALUES = setOf(BLOCKING_OFF, BLOCKING_ADS, BLOCKING_TELEMETRY)

private const val DESK_CHECK = "2026-11-07"

private fun service(id: String, name: String, region: ServiceRegion, geosite: String) =
    RoutedService(id, name, region, listOf(geosite))

private infix fun RoutedService.zh(name: String) = copy(zhName = name)

/** A row that contains others: its rules go after theirs. */
private fun RoutedService.broad() = copy(broad = true)

@Suppress("LongMethod") // One flat, reviewable table.
private fun catalogue(): List<RoutedService> {
    val us = ServiceRegion.US
    val jp = ServiceRegion.JAPAN
    val kr = ServiceRegion.KOREA
    val hk = ServiceRegion.HONG_KONG
    val tw = ServiceRegion.TAIWAN
    val cn = ServiceRegion.MAINLAND
    val ai = ServiceRegion.AI
    val games = ServiceRegion.GAMES
    val social = ServiceRegion.SOCIAL
    return listOf(
        service("google", "Google", us, "GOOGLE").broad(),
        service("youtube", "YouTube", us, "YOUTUBE"),
        service("netflix", "Netflix", us, "NETFLIX"),
        service("disney", "Disney+", us, "DISNEY").broad(),
        service("hbo", "HBO Max", us, "HBO"),
        service("hulu", "Hulu", us, "HULU"),
        service("primevideo", "Prime Video", us, "PRIMEVIDEO"),
        service("spotify", "Spotify", us, "SPOTIFY"),
        service("apple", "Apple", us, "APPLE").broad(),
        service("icloud", "iCloud", us, "ICLOUD"),
        service("microsoft", "Microsoft", us, "MICROSOFT").broad(),
        service("github", "GitHub", us, "GITHUB"),
        service("paypal", "PayPal", us, "PAYPAL"),
        service("dazn", "DAZN", us, "DAZN"),
        service("bbc", "BBC iPlayer", ServiceRegion.UK, "BBC"),
        service("sky", "Sky", ServiceRegion.UK, "SKY"),
        // No GeoSite category: the domains of the curated ITV and All4 lists.
        RoutedService(
            "itvx",
            "ITVX",
            ServiceRegion.UK,
            suffixes = listOf("itv.com", "itvstatic.com"),
            full = listOf("itvpnpmobile-a.akamaihd.net"),
        ),
        RoutedService(
            "channel4",
            "Channel 4",
            ServiceRegion.UK,
            suffixes = listOf("channel4.com", "c4assets.com"),
        ),
        service("abema", "ABEMA", jp, "ABEMA"),
        service("tver", "TVer", jp, "TVER"),
        service("unext", "U-NEXT", jp, "UNEXT"),
        service("nhk", "NHK", jp, "NHK"),
        service("niconico", "Niconico", jp, "NICONICO"),
        service("dmm", "DMM", jp, "DMM"),
        service("naver", "Naver", kr, "NAVER"),
        service("kakao", "Kakao", kr, "KAKAO"),
        service("coupang", "Coupang", kr, "COUPANG"),
        service("tvb", "TVB / myTV SUPER", hk, "TVB"),
        service("viu", "Viu", hk, "VIU"),
        service("nowtv", "Now TV / Now E", hk, "NOW"),
        service("bahamut", "Bahamut Ani-Gamer", tw, "BAHAMUT") zh "巴哈姆特动画疯",
        service("kktv", "KKTV", tw, "KKTV"),
        service("litv", "LiTV", tw, "LITV"),
        service("catchplay", "CatchPlay+", tw, "CATCHPLAY"),
        service("hamivideo", "Hami Video", tw, "HAMIVIDEO"),
        service("bilibili", "Bilibili", cn, "BILIBILI") zh "哔哩哔哩",
        service("tencent", "WeChat / QQ", cn, "TENCENT").broad() zh "微信 / QQ",
        service("alibaba", "Alipay / Taobao", cn, "ALIBABA").broad() zh "支付宝 / 淘宝",
        service("baidu", "Baidu", cn, "BAIDU") zh "百度",
        service("douyin", "Douyin", cn, "DOUYIN") zh "抖音",
        service("kuaishou", "Kuaishou", cn, "KUAISHOU") zh "快手",
        service("netease", "NetEase / NetEase Cloud Music", cn, "NETEASE") zh "网易 / 网易云音乐",
        service("iqiyi", "iQIYI", cn, "IQIYI") zh "爱奇艺",
        service("youku", "Youku", cn, "YOUKU") zh "优酷",
        service("jd", "JD", cn, "JD") zh "京东",
        service("meituan", "Meituan", cn, "MEITUAN") zh "美团",
        service("zhihu", "Zhihu", cn, "ZHIHU") zh "知乎",
        service("xiaohongshu", "Xiaohongshu (RED)", cn, "XIAOHONGSHU") zh "小红书",
        service("weibo", "Weibo", cn, "SINA") zh "微博",
        service("chatgpt", "ChatGPT", ai, "OPENAI"),
        service("claude", "Claude", ai, "ANTHROPIC"),
        service("gemini", "Gemini", ai, "GOOGLE-GEMINI"),
        service("perplexity", "Perplexity", ai, "PERPLEXITY"),
        service("grok", "Grok", ai, "XAI"),
        service("poe", "Poe", ai, "POE"),
        service("steam", "Steam", games, "STEAM"),
        service("epic", "Epic Games", games, "EPICGAMES"),
        service("playstation", "PlayStation", games, "PLAYSTATION"),
        service("xbox", "Xbox", games, "XBOX"),
        service("nintendo", "Nintendo", games, "NINTENDO"),
        service("ea", "EA", games, "EA"),
        service("blizzard", "Blizzard / Battle.net", games, "BLIZZARD"),
        service("riot", "Riot Games", games, "RIOT"),
        service("hoyoverse", "HoYoverse", games, "HOYOVERSE"),
        service("twitch", "Twitch", games, "TWITCH"),
        service("telegram", "Telegram", social, "TELEGRAM"),
        service("whatsapp", "WhatsApp", social, "WHATSAPP"),
        service("signal", "Signal", social, "SIGNAL"),
        service("discord", "Discord", social, "DISCORD"),
        service("x", "X / Twitter", social, "TWITTER"),
        service("facebook", "Facebook", social, "FACEBOOK"),
        service("instagram", "Instagram", social, "INSTAGRAM"),
        service("tiktok", "TikTok", social, "TIKTOK"),
        service("reddit", "Reddit", social, "REDDIT"),
        service("linkedin", "LinkedIn", social, "LINKEDIN"),
        service("wikipedia", "Wikipedia", social, "WIKIMEDIA"),
        service("zoom", "Zoom", social, "ZOOM"),
        service("slack", "Slack", social, "SLACK"),
        service("notion", "Notion", social, "NOTION"),
        service("dropbox", "Dropbox", social, "DROPBOX"),
        service("onedrive", "OneDrive", social, "ONEDRIVE"),
        service("yahoo", "Yahoo", social, "YAHOO"),
    )
}

internal val SERVICE_CATALOGUE: List<RoutedService> = catalogue()
internal val SERVICE_IDS: Set<String> = SERVICE_CATALOGUE.mapTo(HashSet()) { it.id }

private val googleRow = GuideRow("google_youtube", "Google & YouTube", listOf("google", "youtube"))
private val aiRow =
    GuideRow("ai", "ChatGPT, Claude & Gemini", listOf("chatgpt", "claude", "gemini"))
private val appleRow = GuideRow("apple_icloud", "Apple & iCloud", listOf("apple", "icloud"))

private fun single(id: String) =
    SERVICE_CATALOGUE.first { it.id == id }.let { GuideRow(id, it.name, listOf(id)) }

private val defaultGuide =
    listOf(
        googleRow,
        aiRow,
        single("telegram"),
        single("x"),
        single("netflix"),
        single("github"),
        appleRow,
        single("steam"),
    )

/** The setup guide's short list: the local services first where a region has its own. */
internal fun guideRows(home: ServiceRegion?): List<GuideRow> {
    val local =
        when (home) {
            ServiceRegion.UK -> listOf("bbc", "itvx", "channel4", "sky")
            ServiceRegion.JAPAN -> listOf("abema", "tver", "unext", "nhk")
            ServiceRegion.KOREA -> listOf("naver", "kakao", "coupang")
            ServiceRegion.HONG_KONG -> listOf("tvb", "viu", "nowtv")
            ServiceRegion.TAIWAN -> listOf("bahamut", "kktv", "litv", "hamivideo")
            else -> return defaultGuide
        }.map(::single)
    return (local + listOf(googleRow, aiRow, single("netflix"), single("telegram"))).take(
        GUIDE_ROWS
    )
}

private const val GUIDE_ROWS = 8

/** The device's own country or region, from its locale; null when none of the tabs fits. */
internal fun homeRegion(country: String, language: String): ServiceRegion? =
    when (country.uppercase(java.util.Locale.ROOT)) {
        "US" -> ServiceRegion.US
        "GB" -> ServiceRegion.UK
        "JP" -> ServiceRegion.JAPAN
        "KR" -> ServiceRegion.KOREA
        "HK",
        "MO" -> ServiceRegion.HONG_KONG
        "TW" -> ServiceRegion.TAIWAN
        "CN" -> ServiceRegion.MAINLAND
        else ->
            when (language) {
                "ja" -> ServiceRegion.JAPAN
                "ko" -> ServiceRegion.KOREA
                else -> null
            }
    }

/** Tabs in order: the device's own country or region first, then the fixed order. */
internal fun regionOrder(home: ServiceRegion?): List<ServiceRegion> =
    listOfNotNull(home) + ServiceRegion.entries.filter { it != home }
