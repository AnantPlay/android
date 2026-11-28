package me.champdev.weblock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Subtitle searches by IMDb id and language. OpenSubtitles' legacy REST search and TheSubtitleDB (no key, no login), SubDL with
 * the viewer's own key. Android-free; the caller supplies the connection. Files: see SubFetch.
 */
final class SubOs {
    /** The legacy API refuses requests without a User-Agent; Http implementations must send this one. */
    static final String UA = "TemporaryUserAgent";
    static final String BASE = "https://rest.opensubtitles.org/search/";
    static final int MAX_RESULTS = 100;
    static final int MAX_ANSWER = 4 * 1024 * 1024;

    /** Opens a GET (following redirects) and returns the body; the implementation sets {@link #UA} and its timeouts. */
    interface Http {
        InputStream open(String url) throws IOException;
    }

    static final class Result {
        String url, lang, release, format, encoding, source = "OpenSubtitles";
        int downloads;
        boolean hearingImpaired, ai;
    }

    /**
     * The one form the server answers directly (any other segment order 302s to it): segments sorted by name, imdb id
     * zero-padded to 7 digits. Movies: imdbid-0133093/sublanguageid-ara. Episodes: episode-1/imdbid-0903747/season-1/sublanguageid-eng.
     */
    static String url(String imdb, int season, int episode, String lang) throws IOException {
        String digits = imdb == null ? "" : imdb.trim().replaceFirst("^(?i)tt", "");
        if (!digits.matches("\\d{1,9}")) throw new IOException("bad IMDb id: " + imdb);
        boolean ep = season > 0 || episode > 0;
        StringBuilder u = new StringBuilder(BASE);
        if (ep) u.append("episode-").append(episode).append('/');
        u.append("imdbid-").append(String.format(Locale.ROOT, "%07d", Long.parseLong(digits)));
        if (ep) u.append("/season-").append(season);
        String l = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        if (!l.isEmpty()) {
            if (!l.matches("[a-z]{3}(,[a-z]{3})*")) throw new IOException("bad language: " + lang);
            u.append("/sublanguageid-").append(l);
        }
        return u.toString();
    }

    /** lang "" = all languages (the server then returns a mixed top 100). Season and episode 0,0 = a movie. */
    static List<Result> search(Http http, String imdb, int season, int episode, String lang) throws IOException {
        String body;
        try (InputStream in = http.open(url(imdb, season, episode, lang))) {
            body = new String(read(in, MAX_ANSWER, "answer"), StandardCharsets.UTF_8).trim();
        }
        if (!body.startsWith("[")) {
            String s = body.replaceAll("\\s+", " ");
            throw new IOException("not a subtitle list: " + s.substring(0, Math.min(s.length(), 80)));
        }
        JSONArray a;
        try {
            a = new JSONArray(body);
        } catch (JSONException e) {
            throw new IOException("bad subtitle list: " + e.getMessage());
        }
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Result r = new Result();
            r.url = o.optString("SubDownloadLink", "");
            r.format = o.optString("SubFormat", "").toLowerCase(Locale.ROOT);
            // .sub is MicroDVD (frame numbers), which no player here reads
            if (r.url.isEmpty() || "1".equals(o.optString("SubBad")) || !(r.format.isEmpty() || r.format.matches("srt|ass|ssa|vtt"))) continue;
            r.lang = o.optString("SubLanguageID", "");
            r.release = o.optString("SubFileName", "");
            if (r.release.isEmpty()) r.release = o.optString("MovieReleaseName", "");
            r.encoding = o.optString("SubEncoding", "");
            r.downloads = (int) Math.min(Integer.MAX_VALUE, Math.max(0, o.optLong("SubDownloadsCnt", 0)));
            r.hearingImpaired = "1".equals(o.optString("SubHearingImpaired"));
            out.add(r);
        }
        Collections.sort(out, new Comparator<Result>() {
            @Override public int compare(Result x, Result y) { return Integer.compare(y.downloads, x.downloads); }
        });
        return out.size() > MAX_RESULTS ? new ArrayList<>(out.subList(0, MAX_RESULTS)) : out;
    }

    // ---- TheSubtitleDB: no key; lookups unlimited, 1,000 downloads an hour ----

    static final String TSDB = "https://api.thesubtitledb.org/v1/by-imdb/";

    /** Its files in one language (OpenSubtitles code) for a movie (season 0) or episode; machine-made ones (ai) last. */
    static List<Result> theSubtitleDb(Http http, String imdb, int season, int episode, String lang) throws IOException {
        String code = iso2(lang);
        if (!imdb.matches("tt\\d{1,10}") || code.isEmpty()) return new ArrayList<>();
        String url = TSDB + imdb + (season > 0 ? "/season/" + season + "/episode/" + episode : "") + "?lang=" + code + "&limit=50";
        JSONObject list = object(http, url).optJSONObject("subtitles");
        JSONArray items = list == null ? null : list.optJSONArray("items");
        List<Result> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null || !o.optString("download_url").startsWith("https://")) continue;
            Result r = new Result();
            r.url = o.optString("download_url");
            r.lang = lang;
            r.release = o.optString("release_name");
            r.format = o.optString("format").toLowerCase(Locale.ROOT);
            r.encoding = o.optString("encoding");
            r.hearingImpaired = o.optBoolean("hearing_impaired");
            r.ai = o.optBoolean("ai_transcribed") || !o.isNull("ai") && !o.optString("ai").isEmpty();
            r.source = "TheSubtitleDB";
            if (r.format.isEmpty() || r.format.matches("srt|ass|ssa|vtt")) out.add(r);
        }
        Collections.sort(out, (x, y) -> Boolean.compare(x.ai, y.ai));   // stable: the server's order otherwise
        return out;
    }

    // ---- SubDL, with your own key (Settings): 2,000 searches a day per key; season packs come as one zip ----

    static List<Result> subdl(Http http, String key, String imdb, int season, int episode, String lang) throws IOException {
        String code = lang.equals("pob") ? "BR_PT" : iso2(lang).toUpperCase(Locale.ROOT);
        if (!imdb.matches("tt\\d{1,10}") || code.isEmpty() || !key.matches("[\\w-]{8,100}")) return new ArrayList<>();
        String url = "https://api.subdl.com/api/v1/subtitles?api_key=" + key + "&imdb_id=" + imdb + "&languages=" + code + "&subs_per_page=30"
            + (season > 0 ? "&type=tv&season_number=" + season + "&episode_number=" + episode : "&type=movie");
        JSONObject answer = object(http, url);
        if (!answer.optBoolean("status")) throw new IOException("SubDL: " + answer.optString("error", "no answer"));
        JSONArray subs = answer.optJSONArray("subtitles");
        List<Result> out = new ArrayList<>();
        for (int i = 0; subs != null && i < subs.length(); i++) {
            JSONObject o = subs.optJSONObject(i);
            if (o == null || !o.optString("url").startsWith("/") || !code.equalsIgnoreCase(o.optString("language"))) continue;   // an unknown code lists every language
            // a file for another episode of the season (a pack, episode null, is searched inside the zip)
            if (season > 0 && !o.isNull("episode") && o.optInt("episode", episode) != episode) continue;
            Result r = new Result();
            r.url = "https://dl.subdl.com" + o.optString("url");
            r.lang = lang;
            r.release = o.optString("release_name", o.optString("name"));
            r.hearingImpaired = o.optBoolean("hi");
            r.source = "SubDL";
            out.add(r);
        }
        return out;
    }

    // ---- Subsource, with your own key (Settings): 7,200 requests a day; one list per film or per season, files come as a zip ----

    static final String SUBSOURCE = "https://api.subsource.net/api/v1/";

    static List<Result> subsource(Http http, String key, String imdb, int season, int episode, String lang) throws IOException {
        String name = lang.equals("pob") ? "brazilian_portuguese" : iso2(lang).isEmpty() ? "" : new Locale(iso2(lang)).getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT).replace(' ', '_');
        List<Result> out = new ArrayList<>();
        if (!imdb.matches("tt\\d{1,10}") || name.isEmpty() || !key.matches("[\\w-]{8,200}")) return out;
        JSONArray titles = object(http, SUBSOURCE + "movies/search?searchType=imdb&imdb=" + imdb + (season > 0 ? "&type=series&season=" + season : "&type=movie")
            + "&api_key=" + key).optJSONArray("data");
        long id = -1;
        for (int i = 0; titles != null && i < titles.length() && id < 0; i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t != null && (season > 0 ? t.optInt("season", -1) == season : t.isNull("season") || t.optInt("season", 0) == 0)) id = t.optLong("movieId", -1);
        }
        if (id < 0) return out;
        JSONArray subs = object(http, SUBSOURCE + "subtitles?movieId=" + id + "&language=" + name + "&limit=100&sort=popular&api_key=" + key).optJSONArray("data");
        java.util.regex.Pattern mine = season > 0 ? SubFetch.episodeName(season, episode) : null, any = java.util.regex.Pattern.compile("(?i)s\\d+[ ._-]*e\\d+|\\d+x\\d+");
        for (int i = 0; subs != null && i < subs.length(); i++) {
            JSONObject o = subs.optJSONObject(i);
            if (o == null || o.optLong("subtitleId", -1) < 0) continue;
            JSONArray info = o.optJSONArray("releaseInfo");
            String release = info == null || info.length() == 0 ? "" : info.optString(0);
            if (mine != null) {   // this episode's file, or a season pack (several files, searched inside the zip); another episode's file is dropped
                boolean hit = false, other = false;
                for (int k = 0; info != null && k < info.length(); k++) {
                    if (mine.matcher(info.optString(k)).find()) { hit = true; release = info.optString(k); }
                    else if (any.matcher(info.optString(k)).find()) other = true;
                }
                if (!hit && (other || o.optInt("files", 1) < 2)) continue;
            }
            Result r = new Result();
            r.url = SUBSOURCE + "subtitles/" + o.optLong("subtitleId") + "/download?api_key=" + key;
            r.lang = lang;
            r.release = release.isEmpty() ? "Subsource " + o.optLong("subtitleId") : release;
            r.downloads = Math.max(0, o.optInt("downloads"));
            r.hearingImpaired = o.optBoolean("hearingImpaired");
            r.source = "Subsource";
            out.add(r);
        }
        return out;
    }

    private static JSONObject object(Http http, String url) throws IOException {
        try (InputStream in = http.open(url)) {
            return new JSONObject(new String(read(in, MAX_ANSWER, "answer"), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("bad answer: " + e.getMessage());
        }
    }

    /** An OpenSubtitles code (ISO 639-2/B, "pob") as two letters ("pt-BR" is "pb", TheSubtitleDB's code); "" when there is none. */
    static String iso2(String os) {
        String c = os == null ? "" : os.trim().toLowerCase(Locale.ROOT);
        String[] odd = {"pob pb", "ger de", "fre fr", "dut nl", "chi zh", "cze cs", "rum ro", "per fa", "slo sk", "may ms", "scc sr", "alb sq",
            "arm hy", "baq eu", "bur my", "geo ka", "ice is", "mac mk", "wel cy", "gre el", "ell el"};
        for (String p : odd) if (p.startsWith(c + " ")) return p.substring(4);
        if (c.length() == 2) return c;
        for (String two : Locale.getISOLanguages()) {
            try { if (new Locale(two).getISO3Language().equals(c)) return two; } catch (java.util.MissingResourceException ignored) { }
        }
        return "";
    }

    /** Reads to the end of the stream, failing instead of passing max bytes. */
    static byte[] read(InputStream in, int max, String what) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (o.size() + n > max) throw new IOException(what + " too large");
            o.write(buf, 0, n);
        }
        return o.toByteArray();
    }
}
