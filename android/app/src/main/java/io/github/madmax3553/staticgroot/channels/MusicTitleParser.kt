package io.github.madmax3553.staticgroot.channels

import java.time.LocalDate

/*
 * Port of tvOS MusicTitleParser, MusicYear and the display helpers in
 * PlexMediaItem+MusicDisplay. Local string work only: no MusicBrainz or Deezer here.
 *
 * The cleaning rules come from measured ripped filenames in the Swift code: underscores for
 * spaces, [MMV] and {smg} tags, (Shindig 1964) notes, scene suffixes (-vgb-prv, XviD) and a
 * stray "video!". Tests carry the Swift test strings.
 */
object MusicTitleParser {

    data class Parsed(val artist: String?, val song: String)

    private val YEAR_SUFFIX = Regex("""\s*\((?:19|20)\d\d\)\s*$""")
    private val YEAR_NOTE = Regex("""\([^)]*(?:19|20)\d\d[^)]*\)""")
    private val TAGS = Regex("""\[[^\]]*\]|\{[^}]*\}""")
    private val SCENE_WORDS = Regex("""(?i)\b(official|video|hd|hq|lyrics?|remaster(?:ed)?|mv|xvid|dvdrip|vgb|prv)\b!?""")
    private val SPACES = Regex("""\s+""")
    private val FEAT_ARTIST = Regex("""(?i)\s+(?:ft\.?|feat\.?|featuring|with|and the)\s+.*$""")
    private val FEAT_SONG = Regex("""(?i)\s+(?:ft\.?|feat\.?|featuring)\s+.*$""")
    private val LONE_HYPHEN = Regex("""^(.{2,}?)-(.+)$""")
    private val TITLE_YEAR = Regex("""\(((?:19|20)\d\d)\)""")
    private val TRIM_CLEAN = charArrayOf(' ', '-', '!', '.')
    private val TRIM_FEAT = charArrayOf(' ', ',')

    /** The `(1988)` a ripped filename carries. Null for a date inside a note, like `(11-4-1963 ...)`. */
    fun yearInTitle(raw: String): Int? = TITLE_YEAR.find(raw)?.groupValues?.get(1)?.toInt()

    /** Takes a ripped filename down to something a music service can search for. */
    fun clean(raw: String): String {
        var t = raw.replace("_", " ")
        t = t.replace(TAGS, "")
        t = t.replace(YEAR_SUFFIX, "")
        t = t.replace(YEAR_NOTE, "")
        t = t.replace(SCENE_WORDS, "")
        t = t.replace(SPACES, " ")
        return t.trim(*TRIM_CLEAN)
    }

    /** Drops featured artists: "Nelly ft. Akon ft. Ashanti" becomes "Nelly". */
    fun stripFeaturing(s: String, artistSide: Boolean): String =
        s.replace(if (artistSide) FEAT_ARTIST else FEAT_SONG, "").trim(*TRIM_FEAT)

    /**
     * `Artist - Song`, `Artist-Song` or `Song by Artist`. The song is the first dash-separated
     * part after the artist, so "Beatles - I'm A Loser - Boys" searches for I'm A Loser.
     */
    fun parse(raw: String): Parsed {
        val cleaned = clean(raw)

        val sep = cleaned.indexOf(" - ")
        if (sep >= 0) {
            val artist = stripFeaturing(cleaned.substring(0, sep), artistSide = true)
            var song = cleaned.substring(sep + 3).trim()
            val next = song.indexOf(" - ")
            if (next >= 0) song = song.substring(0, next)
            song = stripFeaturing(song, artistSide = false)
            if (artist.isNotEmpty() && song.isNotEmpty()) return Parsed(artist, song)
        }

        val by = cleaned.lastIndexOf(" by ", ignoreCase = true)
        if (by >= 0) {
            val song = stripFeaturing(cleaned.substring(0, by), artistSide = false)
            val artist = stripFeaturing(cleaned.substring(by + 4), artistSide = true)
            if (artist.isNotEmpty() && song.isNotEmpty()) return Parsed(artist, song)
        }

        // "Black eyed peas-shut up": a lone hyphen with letters on both sides.
        if (LONE_HYPHEN.containsMatchIn(cleaned)) {
            val dash = cleaned.indexOf('-')
            val artist = stripFeaturing(cleaned.substring(0, dash), artistSide = true)
            val song = stripFeaturing(cleaned.substring(dash + 1), artistSide = false)
            if (artist.length >= 2 && song.length >= 2 && !artist.contains(" - ")) return Parsed(artist, song)
        }
        return Parsed(null, stripFeaturing(cleaned, artistSide = false))
    }
}

/**
 * Which year a music video gets, in trust order: the year in its own filename, the MusicBrainz
 * first release, then the server's year only when plausible. 1970 is "unknown" and a year at or
 * past the current one is the date the file was added.
 */
object MusicYear {
    fun resolve(
        titleYear: Int?,
        musicBrainzYear: Int?,
        serverYear: Int?,
        thisYear: Int = LocalDate.now().year,
    ): Int? {
        titleYear?.let { return it }
        musicBrainzYear?.let { return it }
        if (serverYear == null) return null
        return if (serverYear > 1970 && serverYear < thisYear) serverYear else null
    }
}

/** True for music videos. An episode never is. */
val MediaItem.isMusicVideo: Boolean
    get() = type != MediaType.EPISODE && (librarySource ?: LibrarySource.MOVIE) == LibrarySource.MUSIC_VIDEO

/**
 * Single-line label for the guide and info panel. A middle dot, not an em dash, so it reads
 * the same as the web tuner and tvOS. Other items show their plain title.
 */
val MediaItem.displayTitle: String
    get() = if (isMusicVideo && !artist.isNullOrEmpty()) "$artist \u00B7 $title" else title
