package eu.kanade.tachiyomi.animeextension.it.animesaturn

import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import kotlin.io.encoding.Base64

class AnimeSaturn :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeSaturn"

    override val lang = "it"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        val currentDomain = getString(PREF_DOMAIN, DOMAIN_DEFAULT)!!
        if (currentDomain !in DOMAIN_VALUES) {
            edit()
                .putString(PREF_DOMAIN, DOMAIN_DEFAULT)
                .apply()
        }
    }

    override val baseUrl by preferences.delegate(PREF_DOMAIN, DOMAIN_DEFAULT)

    override fun popularAnimeSelector(): String = "a.group[href]:not(.flex)"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ongoing/$page")

    private fun formatTitle(titlestring: String): String = titlestring.replace("(ITA)", "Dub ITA")

    override fun popularAnimeFromElement(element: Element): SAnime = searchAnimeFromElement(element)

    override fun popularAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    override fun episodeListSelector() = "a.ep-tile"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href").replace("episode/", "anime/").replace("watch/", "stream/"))
        val epText = element.attr("title")
        episode.episode_number = epText.substringAfter("Episodio ").toFloatOrNull() ?: 0f
        episode.name = epText
        return episode
    }

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
                    videoUrl,
                    "Qualità predefinita",
                    videoUrl,
                ),
            )
        }

        return playlistUtils.extractFromHls(videoUrl)
    }

    private fun decodeUrl(url: String, token: String): String {
        val base = Base64.Default.decode(url).decodeToString()
        val builder = StringBuilder()
        for (i in base.indices) {
            builder.append(base[i].code.xor(token[i % token.length].code).toChar())
        }
        return builder.toString()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val episodePage = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val url = episodePage.selectFirst("iframe")!!.attr("src")
        return Request.Builder()
            .url(url.replace("?", "/playlist?"))
            .header("Referer", url)
            .build()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val prQuality = preferences.getString(PREF_QUALITY, QUALITY_DEFAULT)!!

        return sortedWith(
            compareByDescending { it.quality.contains(prQuality) },
        )
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = formatTitle(element.selectFirst("h3")!!.text())
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun searchAnimeSelector(): String = "a.group[href]:not(.flex)"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return GET("$baseUrl/filter/$page?key=$query$parameters")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = formatTitle(document.selectFirst("h1")!!.text())
        anime.author = document.selectFirst("div a[href*=studios]")?.text()
        anime.status = parseStatus(document.selectFirst("div a[href*=states]")?.text().orEmpty())
        anime.genre = document.select("div a[href*=categories]").joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img[src*=locandine]")?.attr("src")
        val alterTitle = formatTitle(
            document.selectFirst(".ag-head > .mt-1")?.text().orEmpty(),
        ).replace("(ITA)", "").trim()
        val descriptionText = document.selectFirst(".text-pretty")?.text()?.trim().orEmpty()
        val typeText = document.selectFirst("a:nth-of-type(1) > .flex > .font-medium")?.text()?.trim().orEmpty()
        val releaseSeasonText = document.selectFirst("a:nth-of-type(2) > .flex > .font-medium")?.text()?.trim().orEmpty()
        val releaseDateText = document.selectFirst("div:nth-of-type(2) > .font-medium")?.text()?.trim().orEmpty()
        val langText = document.selectFirst("a:nth-of-type(3) .font-medium")?.text()?.trim().orEmpty()
        val durationText = document.selectFirst("div:nth-of-type(4) > .font-medium")?.text()?.trim().orEmpty()
        val viewsText = document.selectFirst("div:nth-of-type(6) > .font-medium")?.text()?.trim().orEmpty()
        val voteText = document.selectFirst("#anime-score")?.text()?.trim().orEmpty()
        val votersText = document.selectFirst("#anime-votes")?.text()?.trim().orEmpty()

        anime.description = buildString {
            if (!anime.title.contains(alterTitle, true) && !alterTitle.isEmpty()) append("Titolo Alternativo: ${alterTitle}\n\n")
            if (!langText.isEmpty()) append("Lingua: ${langText}\n\n")
            if (!descriptionText.isEmpty()) append("Descrizione: ${descriptionText}\n\n")
            if (!typeText.isEmpty()) append("Tipo: ${typeText}\n\n")
            if (!releaseDateText.isEmpty()) append("Uscita: ${releaseSeasonText} - ${releaseDateText}\n\n")
            if (!durationText.isEmpty()) append("Durata Media: ${durationText}\n\n")
            if (!viewsText.isEmpty()) append("Visualizzazioni: ${viewsText}\n\n")
            if (!voteText.isEmpty()) append("Voto: ★${voteText[0]}/10 (${votersText})\n\n")
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("In corso") -> SAnime.ONGOING
        statusString.contains("Finito") -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    override fun latestUpdatesSelector(): String = "a.group[href]:not(.flex)"

    override fun latestUpdatesFromElement(element: Element): SAnime = searchAnimeFromElement(element)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newest/$page")

    override fun latestUpdatesNextPageSelector(): String = "a[rel=\"next\"]"

    // Filters
    internal class Genre(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)
    private fun getGenres() = listOf(
        Genre("3", "Arti Marziali"),
        Genre("5", "Avanguardia"),
        Genre("2", "Avventura"),
        Genre("1", "Azione"),
        Genre("47", "Bambini"),
        Genre("4", "Commedia"),
        Genre("6", "Demoni"),
        Genre("7", "Drammatico"),
        Genre("8", "Ecchi"),
        Genre("9", "Fantasy"),
        Genre("10", "Gioco"),
        Genre("11", "Harem"),
        Genre("43", "Hentai"),
        Genre("13", "Horror"),
        Genre("49", "Isekai"),
        Genre("14", "Josei"),
        Genre("16", "Magia"),
        Genre("18", "Mecha"),
        Genre("19", "Militari"),
        Genre("21", "Mistero"),
        Genre("20", "Musicale"),
        Genre("22", "Parodia"),
        Genre("23", "Polizia"),
        Genre("24", "Psicologico"),
        Genre("46", "Romantico"),
        Genre("26", "Samurai"),
        Genre("28", "Sci-Fi"),
        Genre("27", "Scolastico"),
        Genre("29", "Seinen"),
        Genre("25", "Sentimentale"),
        Genre("30", "Shoujo"),
        Genre("31", "Shoujo Ai"),
        Genre("32", "Shounen"),
        Genre("33", "Shounen Ai"),
        Genre("34", "Slice of Life"),
        Genre("37", "Soprannaturale"),
        Genre("35", "Spazio"),
        Genre("36", "Sport"),
        Genre("12", "Storico"),
        Genre("38", "Superpoteri"),
        Genre("39", "Thriller"),
        Genre("40", "Vampiri"),
        Genre("48", "Veicoli"),
        Genre("41", "Yaoi"),
        Genre("42", "Yuri")
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Anno di Uscita", years)
    private fun getYears(): List<Year> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return (1960..currentYear).map { Year(it.toString()) }
    }

    internal class State(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class StateList(states: List<State>) : AnimeFilter.Group<State>("Stato", states)
    private fun getStates() = listOf(
        State("0", "In corso"),
        State("1", "Finito"),
        State("2", "Non rilasciato"),
        State("3", "Droppato"),
    )

    internal class Type(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class TypeList(types: List<Type>) : AnimeFilter.Group<Type>("Tipo", types)
    private fun getTypes() = listOf(
        Type("1", "TV"),
        Type("2", "Movie"),
        Type("3", "OVA"),
        Type("4", "Special"),
        Type("5", "ONA"),
    )

    internal class Lang(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class LangList(langs: List<Lang>) : AnimeFilter.Group<Lang>("Lingua", langs)
    private fun getLangs() = listOf(
        Lang("jp", "Giapponese"),
        Lang("it", "Italiano"),
        Lang("en", "Inglese"),
        Lang("kr", "Coreano"),
        Lang("ch", "Cinese"),
    )

    internal class Subs(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class SubsList(subs: List<Subs>) : AnimeFilter.Group<Subs>("Sottotitoli", subs)
    private fun getSubs() = listOf(
        Subs("0", "Sottotitolato"),
        Subs("1", "Doppiato"),
    )

    internal class ReleaseSeason(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class ReleaseSeasonList(seasons: List<ReleaseSeason>) : AnimeFilter.Group<ReleaseSeason>("Stagione di Uscita", seasons)
    private fun getReleaseSeasons() = listOf(
        ReleaseSeason("spring", "Primavera"),
        ReleaseSeason("summer", "Estate"),
        ReleaseSeason("fall", "Autunno"),
        ReleaseSeason("winter", "Inverno"),
        ReleaseSeason("unknown", "Sconosciuta")
    )

    internal class Order(val id: String, name: String) : AnimeFilter.CheckBox(name) {
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
        GenreList(getGenres()),
        YearList(getYears()),
        StateList(getStates()),
        ReleaseSeasonList(getReleaseSeasons()),
        TypeList(getTypes()),
        LangList(getLangs()),
        SubsList(getSubs()),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        var variantgenre = 0
        var variantstate = 0
        var variantseason = 0
        var varianttype = 0
        var variantyear = 0
        var variantlang = 0
        var variantsubs = 0
        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            totalstring = totalstring + "&categories%5B" + variantgenre.toString() + "%5D=" + genre.id
                            variantgenre++
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { year ->
                        if (year.state) {
                            totalstring = totalstring + "&years%5B" + variantyear.toString() + "%5D=" + year.id
                            variantyear++
                        }
                    }
                }

                is StateList -> { // ---State
                    filter.state.forEach { state ->
                        if (state.state) {
                            totalstring = totalstring + "&states%5B" + variantstate.toString() + "%5D=" + state.id
                            variantstate++
                        }
                    }
                }

                is TypeList -> { // ---Type
                    filter.state.forEach { type ->
                        if (type.state) {
                            totalstring = totalstring + "&types%5B" + varianttype.toString() + "%5D=" + type.id
                            varianttype++
                        }
                    }
                }

                is LangList -> { // ---Lang
                    filter.state.forEach { lang ->
                        if (lang.state) {
                            totalstring = totalstring + "&languages%5B" + variantlang.toString() + "%5D=" + lang.id
                            variantlang++
                        }
                    }
                }

                is SubsList -> { // ---Subs
                    filter.state.forEach { subs ->
                        if (subs.state) {
                            totalstring = totalstring + "&subtitles%5B" + variantsubs.toString() + "%5D=" + subs.id
                            variantsubs++
                        }
                    }
                }

                is ReleaseSeasonList -> { // ---Release Season
                    filter.state.forEach { season ->
                        if (season.state) {
                            totalstring = totalstring + "&release_seasons%5B" + variantseason.toString() + "%5D=" + season.id
                            variantseason++
                        }
                    }
                }

                is OrderList -> { // ---Sorts
                    val order = filter.values[filter.state]
                    totalstring = totalstring + "&sort=" + order.id
                }

                else -> {}
            }
        }
        return totalstring
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY,
            title = "Qualità preferita",
            entries = QUALITY_ENTRIES,
            entryValues = QUALITY_VALUES,
            default = QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_DOMAIN,
            title = "Domain in uso (riavvio dell'app richiesto)",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = DOMAIN_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private val QUALITY_VALUES = listOf("1080", "720", "480", "360", "240", "144")
        private val QUALITY_ENTRIES = QUALITY_VALUES.map { "${it}p" }
        private val QUALITY_DEFAULT = QUALITY_VALUES.first()

        private const val PREF_DOMAIN = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf("animesaturn.net", "animesaturn.cx", "animesaturn.cc", "animesaturn.com", "animemars.org")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val DOMAIN_DEFAULT = DOMAIN_VALUES.first()
    }

    @Serializable
    data class PlaylistModel(val d: String, val p: String, val t: String)
}
