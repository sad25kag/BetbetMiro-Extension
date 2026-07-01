package com.sad25kag.cinemax21

import com.fasterxml.jackson.annotation.JsonProperty

data class AesData(
    @field:JsonProperty("m") val m: String,
)

data class ResponseHash(
    @field:JsonProperty("embed_url") val embed_url: String,
    @field:JsonProperty("key") val key: String,
)

data class AniIds(var id: Int? = null, var idMal: Int? = null)

data class TmdbDate(
    val today: String,
    val nextWeek: String,
)

data class VixsrcSource(
    val name: String,
    val url: String,
    val referer: String,
)

data class VidrockSource(
    @field:JsonProperty("resolution") val resolution: Int? = null,
    @field:JsonProperty("url") val url: String? = null,
)

data class VidrockSubtitle(
    @field:JsonProperty("label") val label: String? = null,
    @field:JsonProperty("file") val file: String? = null,
)

data class VidsrccxSource(
    @field:JsonProperty("secureUrl") val secureUrl: String? = null,
)

data class WyzieSubtitle(
    @field:JsonProperty("display") val display: String? = null,
    @field:JsonProperty("url") val url: String? = null,
)

data class VidFastSources(
    @field:JsonProperty("url") val url: String? = null,
    @field:JsonProperty("tracks") val tracks: ArrayList<Tracks>? = null,
) {
    data class Tracks(
        @field:JsonProperty("file") val file: String? = null,
        @field:JsonProperty("label") val label: String? = null,
    )
}

data class VidFastServers(
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("description") val description: String? = null,
    @field:JsonProperty("data") val data: String? = null,
) {
    data class Stream(
        @field:JsonProperty("playlist") val playlist: String? = null,
    )
}

data class VidlinkSources(
    @field:JsonProperty("stream") val stream: Stream? = null,
) {
    data class Stream(
        @field:JsonProperty("playlist") val playlist: String? = null,
    )
}

data class MappleSubtitle(
    @field:JsonProperty("display") val display: String? = null,
    @field:JsonProperty("url") val url: String? = null,
)

data class MappleSources(
    @field:JsonProperty("data") val data: Data? = null,
) {
    data class Data(
        @field:JsonProperty("stream_url") val stream_url: String? = null,
    )
}

data class PrimeboxSources(
    @field:JsonProperty("streams") val streams: HashMap<String, String>? = null,
    @field:JsonProperty("subtitles") val subtitles: ArrayList<Subtitles>? = null,
) {
    data class Subtitles(
        @field:JsonProperty("file") val file: String? = null,
        @field:JsonProperty("label") val label: String? = null,
    )
}

data class RageSources(
    @field:JsonProperty("url") val url: String? = null,
)

data class VidsrcccServer(
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("hash") val hash: String? = null,
)

data class VidsrcccResponse(
    @field:JsonProperty("data") val data: ArrayList<VidsrcccServer>? = arrayListOf(),
)

data class VidsrcccResult(
    @field:JsonProperty("data") val data: VidsrcccSources? = null,
)

data class VidsrcccSources(
    @field:JsonProperty("subtitles") val subtitles: ArrayList<VidsrcccSubtitles>? = arrayListOf(),
    @field:JsonProperty("source") val source: String? = null,
)

data class VidsrcccSubtitles(
    @field:JsonProperty("label") val label: String? = null,
    @field:JsonProperty("file") val file: String? = null,
)

data class UpcloudSources(
    @field:JsonProperty("file") val file: String? = null,
)

data class UpcloudResult(
    @field:JsonProperty("sources") val sources: ArrayList<UpcloudSources>? = arrayListOf(),
)

data class AniMedia(
    @field:JsonProperty("id") var id: Int? = null,
    @field:JsonProperty("idMal") var idMal: Int? = null
)

data class AniPage(@field:JsonProperty("media") var media: java.util.ArrayList<AniMedia> = arrayListOf())

data class AniData(@field:JsonProperty("Page") var Page: AniPage? = AniPage())

data class AniSearch(@field:JsonProperty("data") var data: AniData? = AniData())

data class GpressSources(
    @field:JsonProperty("src") val src: String,
    @field:JsonProperty("file") val file: String? = null,
    @field:JsonProperty("label") val label: Int? = null,
    @field:JsonProperty("max") val max: String,
)

data class KisskhEpisodes(
    @field:JsonProperty("id") val id: Int?,
    @field:JsonProperty("number") val number: Int?,
)

data class WatchsomuchTorrents(
    @field:JsonProperty("id") val id: Int? = null,
    @field:JsonProperty("movieId") val movieId: Int? = null,
    @field:JsonProperty("season") val season: Int? = null,
    @field:JsonProperty("episode") val episode: Int? = null,
)

data class WatchsomuchMovies(
    @field:JsonProperty("torrents") val torrents: ArrayList<WatchsomuchTorrents>? = arrayListOf(),
)

data class WatchsomuchSubtitles(
    @field:JsonProperty("url") val url: String? = null,
    @field:JsonProperty("label") val label: String? = null,
)

data class WatchsomuchResponses(
    @field:JsonProperty("movie") val movie: WatchsomuchMovies? = null,
)

data class WatchsomuchSubResponses(
    @field:JsonProperty("subtitles") val subtitles: ArrayList<WatchsomuchSubtitles>? = arrayListOf(),
)

data class IndexMedia(
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("driveId") val driveId: String? = null,
    @field:JsonProperty("mimeType") val mimeType: String? = null,
    @field:JsonProperty("size") val size: String? = null,
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("modifiedTime") val modifiedTime: String? = null,
)

data class IndexData(
    @field:JsonProperty("files") val files: ArrayList<IndexMedia>? = arrayListOf(),
)

data class IndexSearch(
    @field:JsonProperty("data") val data: IndexData? = null,
)

data class JikanExternal(
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("url") val url: String? = null,
)

data class JikanData(
    @field:JsonProperty("title") val title: String? = null,
    @field:JsonProperty("external") val external: ArrayList<JikanExternal>? = arrayListOf(),
)

data class VidsrctoResult(
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("title") val title: String? = null,
    @field:JsonProperty("url") val url: String? = null,
)

data class AnilistExternalLinks(
    @field:JsonProperty("id") var id: Int? = null,
    @field:JsonProperty("site") var site: String? = null,
    @field:JsonProperty("url") var url: String? = null,
    @field:JsonProperty("type") var type: String? = null,
)

data class AnilistMedia(@field:JsonProperty("externalLinks") var externalLinks: ArrayList<AnilistExternalLinks> = arrayListOf())

data class AnilistData(@field:JsonProperty("Media") var Media: AnilistMedia? = AnilistMedia())

data class MALSyncSites(
    @field:JsonProperty("Zoro") val zoro: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @field:JsonProperty("9anime") val nineAnime: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
)

data class MALSyncResponses(
    @field:JsonProperty("Sites") val sites: MALSyncSites? = null,
)

data class HianimeResponses(
    @field:JsonProperty("html") val html: String? = null,
    @field:JsonProperty("link") val link: String? = null,
)

data class MalSyncRes(
    @field:JsonProperty("Sites") val Sites: Map<String, Map<String, Map<String, String>>>? = null,
)

data class GokuData(
    @field:JsonProperty("link") val link: String? = null,
)

data class GokuServer(
    @field:JsonProperty("data") val data: GokuData? = GokuData(),
)

data class AllMovielandEpisodeFolder(
    @field:JsonProperty("title") val title: String? = null,
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("file") val file: String? = null,
)

data class AllMovielandSeasonFolder(
    @field:JsonProperty("episode") val episode: String? = null,
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("folder") val folder: ArrayList<AllMovielandEpisodeFolder>? = arrayListOf(),
)

data class AllMovielandServer(
    @field:JsonProperty("title") val title: String? = null,
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("file") val file: String? = null,
    @field:JsonProperty("folder") val folder: ArrayList<AllMovielandSeasonFolder>? = arrayListOf(),
)

data class AllMovielandPlaylist(
    @field:JsonProperty("file") val file: String? = null,
    @field:JsonProperty("key") val key: String? = null,
    @field:JsonProperty("href") val href: String? = null,
)

data class DumpMedia(
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("domainType") val domainType: Int? = null,
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("releaseTime") val releaseTime: String? = null,
)

data class DumpQuickSearchData(
    @field:JsonProperty("searchResults") val searchResults: ArrayList<DumpMedia>? = arrayListOf(),
)

data class SubtitlingList(
    @field:JsonProperty("languageAbbr") val languageAbbr: String? = null,
    @field:JsonProperty("language") val language: String? = null,
    @field:JsonProperty("subtitlingUrl") val subtitlingUrl: String? = null,
)

data class DefinitionList(
    @field:JsonProperty("code") val code: String? = null,
    @field:JsonProperty("description") val description: String? = null,
)

data class EpisodeVo(
    @field:JsonProperty("id") val id: Int? = null,
    @field:JsonProperty("seriesNo") val seriesNo: Int? = null,
    @field:JsonProperty("definitionList") val definitionList: ArrayList<DefinitionList>? = arrayListOf(),
    @field:JsonProperty("subtitlingList") val subtitlingList: ArrayList<SubtitlingList>? = arrayListOf(),
)

data class DumpMediaDetail(
    @field:JsonProperty("episodeVo") val episodeVo: ArrayList<EpisodeVo>? = arrayListOf(),
)

data class EMovieServer(
    @field:JsonProperty("value") val value: String? = null,
)

data class EMovieSources(
    @field:JsonProperty("file") val file: String? = null,
)

data class EMovieTraks(
    @field:JsonProperty("file") val file: String? = null,
    @field:JsonProperty("label") val label: String? = null,
)

data class ShowflixResultsMovies(
    @field:JsonProperty("movieName") val movieName: String? = null,
    @field:JsonProperty("streamwish") val streamwish: String? = null,
    @field:JsonProperty("filelions") val filelions: String? = null,
    @field:JsonProperty("streamruby") val streamruby: String? = null,
)

data class ShowflixResultsSeries(
    @field:JsonProperty("seriesName") val seriesName: String? = null,
    @field:JsonProperty("streamwish") val streamwish: HashMap<String, List<String>>? = hashMapOf(),
    @field:JsonProperty("filelions") val filelions: HashMap<String, List<String>>? = hashMapOf(),
    @field:JsonProperty("streamruby") val streamruby: HashMap<String, List<String>>? = hashMapOf(),
)

data class ShowflixSearchMovies(
    @field:JsonProperty("results") val resultsMovies: ArrayList<ShowflixResultsMovies>? = arrayListOf(),
)

data class ShowflixSearchSeries(
    @field:JsonProperty("results") val resultsSeries: ArrayList<ShowflixResultsSeries>? = arrayListOf(),
)

data class SFMoviesSeriess(
    @field:JsonProperty("title") var title: String? = null,
    @field:JsonProperty("svideos") var svideos: String? = null,
)

data class SFMoviesAttributes(
    @field:JsonProperty("title") var title: String? = null,
    @field:JsonProperty("video") var video: String? = null,
    @field:JsonProperty("releaseDate") var releaseDate: String? = null,
    @field:JsonProperty("seriess") var seriess: ArrayList<ArrayList<SFMoviesSeriess>>? = arrayListOf(),
    @field:JsonProperty("contentId") var contentId: String? = null,
)

data class SFMoviesData(
    @field:JsonProperty("id") var id: Int? = null,
    @field:JsonProperty("attributes") var attributes: SFMoviesAttributes? = SFMoviesAttributes()
)

data class SFMoviesSearch(
    @field:JsonProperty("data") var data: ArrayList<SFMoviesData>? = arrayListOf(),
)

data class RidoContentable(
    @field:JsonProperty("imdbId") var imdbId: String? = null,
    @field:JsonProperty("tmdbId") var tmdbId: Int? = null,
)

data class RidoItems(
    @field:JsonProperty("slug") var slug: String? = null,
    @field:JsonProperty("contentable") var contentable: RidoContentable? = null,
)

data class RidoData(
    @field:JsonProperty("url") var url: String? = null,
    @field:JsonProperty("items") var items: ArrayList<RidoItems>? = arrayListOf(),
)

data class RidoResponses(
    @field:JsonProperty("data") var data: ArrayList<RidoData>? = arrayListOf(),
)

data class RidoSearch(
    @field:JsonProperty("data") var data: RidoData? = null,
)

data class SmashySources(
    @field:JsonProperty("sourceUrls") var sourceUrls: ArrayList<String>? = arrayListOf(),
    @field:JsonProperty("subtitleUrls") var subtitleUrls: String? = null,
)

data class AoneroomResponse(
    @field:JsonProperty("data") val data: Data? = null,
) {
    data class Data(
        @field:JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
        @field:JsonProperty("list") val list: ArrayList<List>? = arrayListOf(),
    ) {
        data class Items(
            @field:JsonProperty("subjectId") val subjectId: String? = null,
            @field:JsonProperty("title") val title: String? = null,
            @field:JsonProperty("releaseDate") val releaseDate: String? = null,
        )

        data class List(
            @field:JsonProperty("resourceLink") val resourceLink: String? = null,
            @field:JsonProperty("extCaptions") val extCaptions: ArrayList<ExtCaptions>? = arrayListOf(),
            @field:JsonProperty("se") val se: Int? = null,
            @field:JsonProperty("ep") val ep: Int? = null,
            @field:JsonProperty("resolution") val resolution: Int? = null,
        ) {
            data class ExtCaptions(
                @field:JsonProperty("lanName") val lanName: String? = null,
                @field:JsonProperty("url") val url: String? = null,
                )
            }
        }
    }

data class CinemaTvResponse(
    @field:JsonProperty("streams") val streams: HashMap<String, String>? = null,
    @field:JsonProperty("subtitles") val subtitles: ArrayList<Subtitles>? = arrayListOf(),
) {
    data class Subtitles(
        @field:JsonProperty("language") val language: String? = null,
        @field:JsonProperty("file") val file: Any? = null,
    )
}

data class NepuSearch(
    @field:JsonProperty("data") val data: ArrayList<Data>? = arrayListOf(),
) {
    data class Data(
        @field:JsonProperty("url") val url: String? = null,
        @field:JsonProperty("name") val name: String? = null,
        @field:JsonProperty("type") val type: String? = null,
    )
}

data class CinemaOsSecretKeyRequest(
    val tmdbId: String,
    val seasonId: String,
    val episodeId: String
)

data class CinemaOSReponse(
    val data: CinemaOSReponseData,
    val encrypted: Boolean,
)

data class CinemaOSReponseData(
    val encrypted: String,
    val cin: String,
    val mao: String,
    val salt: String,
)

data class Player4uLinkData(
    val name: String,
    val url: String,
)

data class DramaSearchResponse(
    @field:JsonProperty("data") val data: ArrayList<SearchItem>? = arrayListOf(),
    @field:JsonProperty("success") val success: Boolean? = null
)

data class SearchItem(
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("title") val title: String? = null,
    @field:JsonProperty("slug") val slug: String? = null,
    @field:JsonProperty("image") val image: String? = null,
    @field:JsonProperty("year") val year: String? = null 
)

data class RiveStreamSource(
    val data: List<String>
)

data class RiveStreamResponse(
    val data: RiveStreamData,
)

data class RiveStreamData(
    val sources: List<RiveStreamSourceData>,
)

data class RiveStreamSourceData(
    val quality: String,
    val url: String,
    val source: String,
    val format: String,
)

data class MovieboxResponse(
    @field:JsonProperty("data") val data: MovieboxData? = null,
)

data class MovieboxData(
    @field:JsonProperty("items") val items: ArrayList<MovieboxItem>? = arrayListOf(),
    @field:JsonProperty("streams") val streams: ArrayList<MovieboxStreamItem>? = arrayListOf(),
    @field:JsonProperty("captions") val captions: ArrayList<MovieboxCaptionItem>? = arrayListOf(),
)

data class MovieboxItem(
    @field:JsonProperty("subjectId") val subjectId: String? = null,
    @field:JsonProperty("title") val title: String? = null,
    @field:JsonProperty("releaseDate") val releaseDate: String? = null,
    @field:JsonProperty("detailPath") val detailPath: String? = null,
)

data class MovieboxStreamItem(
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("format") val format: String? = null,
    @field:JsonProperty("url") val url: String? = null,
    @field:JsonProperty("resolutions") val resolutions: String? = null,
)

data class MovieboxCaptionItem(
    @field:JsonProperty("lanName") val lanName: String? = null,
    @field:JsonProperty("url") val url: String? = null,
)

data class Moviebox2SearchResponse(
    @param:JsonProperty("data") val data: Moviebox2SearchData? = null
)

data class Moviebox2SearchData(
    @param:JsonProperty("results") val results: ArrayList<Moviebox2SearchResult>? = arrayListOf()
)

data class Moviebox2SearchResult(
    @param:JsonProperty("subjects") val subjects: ArrayList<Moviebox2Subject>? = arrayListOf()
)

data class Moviebox2Subject(
    @param:JsonProperty("subjectId") val subjectId: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("releaseDate") val releaseDate: String? = null,
    @param:JsonProperty("subjectType") val subjectType: Int? = null
)

data class Moviebox2PlayResponse(
    @param:JsonProperty("data") val data: Moviebox2PlayData? = null
)

data class Moviebox2PlayData(
    @param:JsonProperty("streams") val streams: ArrayList<Moviebox2Stream>? = arrayListOf()
)

data class Moviebox2Stream(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("format") val format: String? = null,
    @param:JsonProperty("resolutions") val resolutions: String? = null,
    @param:JsonProperty("signCookie") val signCookie: String? = null
)

data class Moviebox2SubtitleResponse(
    @param:JsonProperty("data") val data: Moviebox2SubtitleData? = null
)

data class Moviebox2SubtitleData(
    @param:JsonProperty("extCaptions") val extCaptions: ArrayList<Moviebox2Caption>? = arrayListOf()
)

data class Moviebox2Caption(
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("language") val language: String? = null,
    @param:JsonProperty("lanName") val lanName: String? = null,
    @param:JsonProperty("lan") val lan: String? = null
)