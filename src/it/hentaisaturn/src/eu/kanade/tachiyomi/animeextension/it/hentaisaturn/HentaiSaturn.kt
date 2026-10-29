package eu.kanade.tachiyomi.animeextension.it.hentaisaturn

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.io.encoding.Base64

@Source
abstract class HentaiSaturn :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun popularAnimeSelector(): String = "a.group[href]:not(.flex)"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ongoing/$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime = searchAnimeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = client.newCall(episodeListRequest(anime))
            .awaitSuccess()
            .use(::episodeListParse)

        return episodes.map { enrichEpisode(it) }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    override fun episodeListSelector() = "a.ep-tile"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(
            element.attr("href")
                .replace("episode/", "hentai/")
                .replace("watch/", "stream/"),
        )
        val fallbackName = element.attr("title")
        episode.episode_number = fallbackName.substringAfter("Episodio ").toFloatOrNull() ?: 0f
        episode.name = fallbackName

        return episode
    }

    private suspend fun enrichEpisode(episode: SEpisode): SEpisode {
        client.newCall(GET(baseUrl + episode.url, headers))
            .awaitSuccess()
            .use { response ->
                val tvEpisode = response.asJsoup()
                    .head()
                    .selectFirst("script[type=\"application/ld+json\"]:containsData(\"TVEpisode\")")
                    ?.data()
                    ?.let { runCatching { it.parseAs<TVEpisodeLD>() }.getOrNull() }
                    ?: return episode

                tvEpisode.datePublished
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { episode.date_upload = dateFormat.tryParse(it) }

                tvEpisode.episodeNumber?.let { episode.episode_number = it }

                val seriesName = tvEpisode.partOfSeries?.name
                episode.name = tvEpisode.name
                    ?.let { name -> seriesName?.let { name.substringAfter(it).trim() } ?: name }
                    ?.ifEmpty { episode.name }
                    ?: episode.name
            }

        return episode
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    @Serializable
    internal class TVEpisodeLD(
        val name: String? = null,
        val episodeNumber: Float? = null,
        val datePublished: String? = null,
        val partOfSeries: TVSeriesLD? = null,
    )

    @Serializable
    internal class TVSeriesLD(
        val name: String? = null,
    )

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        if (response.code != 200) {
            return emptyList()
        }

        val token = response.request.url.queryParameter("token") ?: return emptyList()
        val playlistModel = response.parseAs<PlaylistModel>()
        val videoUrl = decodeUrl(playlistModel.d, token)
        if (videoUrl.contains(".mp4")) {
            return listOf(
                Video(
                    videoTitle = "Qualità predefinita",
                    videoUrl = videoUrl,
                ),
            )
        }

        return playlistUtils.extractFromHls(videoUrl)
    }

    private fun decodeUrl(url: String, token: String): String {
        val base = Base64.decode(url).decodeToString()
        val builder = StringBuilder()
        for (i in base.indices) {
            builder.append(base[i].code.xor(token[i % token.length].code).toChar())
        }
        return builder.toString()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val episodePage = client.newCall(GET(baseUrl + episode.url, headers)).execute().asJsoup()
        val url = episodePage.selectFirst("iframe")!!.attr("src")
        return Request.Builder()
            .url(url.replace("?", "/playlist?"))
            .header("Referer", url)
            .build()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sortVideos(): List<Video> {
        val prQuality = preferences.getString(PREF_QUALITY, QUALITY_DEFAULT)!!

        return sortedWith(
            compareByDescending { it.videoTitle.contains(prQuality) },
        )
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.selectFirst("h3")!!.text()
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun searchAnimeSelector(): String = "a.group[href]:not(.flex)"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/filter/$page".toHttpUrl().newBuilder()
            .addQueryParameter("key", query)
        getSearchParameters(url, filters)
        return GET(url.build(), headers)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")!!.text()
        anime.author = document.selectFirst("div a[href*=studios]")?.text()
        val statusText = document.selectFirst("div a[href*=states]")?.text().orEmpty()
        anime.status = parseStatus(statusText)
        if (statusText == "Finito" || statusText == "Droppato") {
            anime.update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
        } else {
            anime.update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
        }
        anime.genre = document.select("div a[href*=categories]").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img[src*=locandine]")?.attr("src")
        val alterTitle = document.selectFirst("div.text-center > p.mt-1")?.text().orEmpty()
            .replace(Regex("\\(\\w+\\)"), "").trim()
        val descriptionText = document.selectFirst("section:has(h2) div")?.text()?.trim().orEmpty()
        val typeText = document.selectFirst("div:nth-of-type(1) > dd")?.text()?.trim().orEmpty()
        val releaseText = document.selectFirst("div:nth-of-type(5) > dd")?.text()?.trim().orEmpty()
        val langText = document.selectFirst("div:nth-of-type(3) > dd > a")?.text()?.trim().orEmpty()
        val durationText = document.selectFirst("div:nth-of-type(7) > dd")?.text()?.trim().orEmpty()
        val viewsText = document.selectFirst("div:nth-of-type(8) > dd")?.text()?.trim().orEmpty()
        val voteText = document.selectFirst("#hentai-score")?.text()?.trim().orEmpty()
        val votersText = document.selectFirst("#hentai-votes")?.text()?.trim().orEmpty()

        anime.description = buildString {
            if (anime.title.lowercase() != alterTitle.lowercase() && alterTitle.isNotEmpty()) append("Titolo Alternativo: ${alterTitle}\n\n")
            if (langText.isNotEmpty()) append("Lingua: ${langText}\n\n")
            if (descriptionText.isNotEmpty()) append("Descrizione: ${descriptionText}\n\n")
            if (typeText.isNotEmpty()) append("Tipo: ${typeText}\n\n")
            if (releaseText.isNotEmpty()) append("Uscita: ${releaseText}\n\n")
            if (durationText.isNotEmpty()) append("Durata Media: ${durationText}\n\n")
            if (viewsText.isNotEmpty()) append("Visualizzazioni: ${viewsText}\n\n")
            if (voteText.isNotEmpty()) append("Voto: ★$voteText/10 ($votersText)\n\n")
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("In corso") -> SAnime.ONGOING
        statusString.contains("Finito") -> SAnime.COMPLETED
        statusString.contains("Droppato") -> SAnime.CANCELLED
        statusString.contains("Non rilasciato") -> SAnime.UNKNOWN
        else -> SAnime.UNKNOWN
    }

    override fun latestUpdatesSelector(): String = "a.group[href]:not(.flex)"

    override fun latestUpdatesFromElement(element: Element): SAnime = searchAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newest/$page", headers)

    override fun latestUpdatesNextPageSelector(): String = "a[rel=\"next\"]"

    // Filters
    private class FilterOption(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class FilterGroup(
        val key: String,
        title: String,
        options: List<FilterOption>,
    ) : AnimeFilter.Group<FilterOption>(title, options)

    private fun getGenres() = listOf(
        FilterOption("1", "3D"),
        FilterOption("2", "Ahegao"),
        FilterOption("3", "Anal"),
        FilterOption("4", "BDSM"),
        FilterOption("5", "Big Boobs"),
        FilterOption("6", "Blow Job"),
        FilterOption("7", "Bondage"),
        FilterOption("8", "Boob Job"),
        FilterOption("9", "Censored"),
        FilterOption("10", "Comedy"),
        FilterOption("11", "Cosplay"),
        FilterOption("12", "Creampie"),
        FilterOption("13", "Dark Skin"),
        FilterOption("14", "Facial"),
        FilterOption("15", "Fantasy"),
        FilterOption("16", "Filmed"),
        FilterOption("17", "Foot Job"),
        FilterOption("18", "Futanari"),
        FilterOption("19", "Gangbang"),
        FilterOption("20", "Glasses"),
        FilterOption("21", "Hand Job"),
        FilterOption("22", "Harem"),
        FilterOption("25", "Horror"),
        FilterOption("26", "Incest"),
        FilterOption("27", "Inflation"),
        FilterOption("29", "Lactation"),
        FilterOption("30", "Loli"),
        FilterOption("31", "Maid"),
        FilterOption("32", "Masturbation"),
        FilterOption("33", "Milf"),
        FilterOption("34", "Mind Break"),
        FilterOption("35", "Mind Control"),
        FilterOption("36", "Monster"),
        FilterOption("37", "NTR"),
        FilterOption("38", "Nurse"),
        FilterOption("39", "Orgy"),
        FilterOption("40", "Plot"),
        FilterOption("41", "POV"),
        FilterOption("42", "Pregnant"),
        FilterOption("43", "Public Sex"),
        FilterOption("44", "Rape"),
        FilterOption("45", "Reverse Rape"),
        FilterOption("46", "Rimjob"),
        FilterOption("48", "Scat"),
        FilterOption("49", "School Girl"),
        FilterOption("51", "Shota"),
        FilterOption("52", "Softcore"),
        FilterOption("53", "Swimsuit"),
        FilterOption("54", "Teacher"),
        FilterOption("55", "Tentacle"),
        FilterOption("56", "Threesome"),
        FilterOption("57", "Toys"),
        FilterOption("58", "Trap"),
        FilterOption("59", "Tsundere"),
        FilterOption("60", "Ugly Bastard"),
        FilterOption("61", "Uncensored"),
        FilterOption("62", "Vanilla"),
        FilterOption("63", "Virgin"),
        FilterOption("64", "Watersports"),
        FilterOption("65", "X-Ray"),
        FilterOption("66", "Yaoi"),
        FilterOption("67", "Yuri"),
    )

    private fun getYears() = run {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        (1960..currentYear).map { FilterOption(it.toString(), it.toString()) }
    }

    private fun getStates() = listOf(
        FilterOption("0", "In corso"),
        FilterOption("1", "Finito"),
        FilterOption("2", "Non rilasciato"),
        FilterOption("3", "Droppato"),
    )

    private fun getTypes() = listOf(
        FilterOption("3", "OVA"),
        FilterOption("4", "Special"),
        FilterOption("5", "ONA"),
    )

    private fun getLangs() = listOf(
        FilterOption("jp", "Giapponese"),
        FilterOption("it", "Italiano"),
        FilterOption("en", "Inglese"),
        FilterOption("kr", "Coreano"),
        FilterOption("ch", "Cinese"),
    )

    private fun getSubs() = listOf(
        FilterOption("0", "Sottotitolato"),
        FilterOption("1", "Doppiato"),
    )

    private class Order(val id: String, private val name: String) {
        override fun toString(): String = name
    }

    private class OrderList(sorts: Array<Order>) : AnimeFilter.Select<Order>("Ordina per", sorts)

    private fun getOrder() = arrayOf(
        Order("standard", "Standard"),
        Order("recent", "Ultime aggiunte"),
        Order("az", "Lista A-Z"),
        Order("za", "Lista Z-A"),
        Order("oldest", "Più vecchi (anno)"),
        Order("newest", "Più recenti (anno)"),
        Order("most_viewed", "Più visti"),
        Order("least_viewed", "Meno visti"),
        Order("best_rated", "Meglio valutati"),
        Order("worst_rated", "Peggio valutati"),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        OrderList(getOrder()),
        FilterGroup("categories", "Generi", getGenres()),
        FilterGroup("years", "Anno di Uscita", getYears()),
        FilterGroup("states", "Stato", getStates()),
        FilterGroup("types", "Tipo", getTypes()),
        FilterGroup("languages", "Lingua", getLangs()),
        FilterGroup("subtitles", "Sottotitoli", getSubs()),
    )

    private fun getSearchParameters(url: HttpUrl.Builder, filters: AnimeFilterList) {
        filters.forEach { filter ->
            when (filter) {
                is FilterGroup -> {
                    filter.state.filter { it.state }.forEachIndexed { index, option ->
                        url.addQueryParameter("${filter.key}[$index]", option.id)
                    }
                }

                is OrderList -> url.addQueryParameter("sort", filter.values[filter.state].id)
                else -> Unit
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY,
            title = "Qualità Preferita",
            entries = QUALITY_ENTRIES,
            entryValues = QUALITY_VALUES,
            default = QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private val QUALITY_VALUES = listOf("1080", "720", "480", "360", "240", "144")
        private val QUALITY_ENTRIES = QUALITY_VALUES.map { "${it}p" }
        private val QUALITY_DEFAULT = QUALITY_VALUES.first()
    }

    @Serializable
    data class PlaylistModel(val d: String, val p: String, val t: String)
}
