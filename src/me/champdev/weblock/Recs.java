package me.champdev.weblock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * More like this: titles of the same kind as the one playing, with no key. Anime: what AniList's members recommend for it (AniList ids
 * turned into IMDb ones by arm's table). Movies and series: the app's own lists, scored: its language's Popular and New (the
 * language whose list it is on), the most popular of its genres, then popularity and closeness in years. Off the main thread.
 */
final class Recs {
    static final int MAX = 40;

    static List<Catalog.Title> like(String imdb, String type) {
        if (imdb == null || !imdb.startsWith("tt")) return new ArrayList<>();
        if (Catalog.isAnime(imdb)) {
            List<Catalog.Title> a = anime(imdb);
            if (!a.isEmpty()) return a;
        }
        Catalog.Title t = new Catalog.Title();
        t.id = imdb;
        t.type = type;
        Catalog.fill(t);
        Map<String, Double> score = new HashMap<>();
        Map<String, Catalog.Title> byId = new LinkedHashMap<>();
        // its language: the country's languages whose list it is on (a Telugu film is on Telugu's), else a little of each
        List<String> langs = new ArrayList<>();
        for (String c : t.country.split(",\\s*")) for (String iso : Locale.getISOCountries())
            if (!c.isEmpty() && new Locale("", iso).getDisplayCountry(Locale.ENGLISH).equalsIgnoreCase(c.trim()))
                for (String l : Catalog.languages(iso)) if (!langs.contains(l)) langs.add(l);
        boolean found = false;
        for (String lang : langs) {
            List<Catalog.Title> pop = Catalog.rank(type, "popular:" + lang, false), fresh = Catalog.rank(type, "new:" + lang, false);
            if (has(pop, imdb) || has(fresh, imdb)) {
                add(score, byId, pop, 2.0);
                add(score, byId, fresh, 1.2);
                found = true;
                break;
            }
        }
        if (!found) for (int i = 0; i < Math.min(2, langs.size()); i++) add(score, byId, Catalog.rank(type, "popular:" + langs.get(i), false), 0.8);
        // its genres: a title on several of their lists is closer
        List<String> genres = new ArrayList<>();
        for (String g : t.genres.split(" · ")) for (String known : Catalog.GENRES) if (known.equalsIgnoreCase(g.trim()) && genres.size() < 3) genres.add(known);
        for (String g : genres) add(score, byId, Catalog.list(type, "genre=" + g), 1.5 / genres.size());
        score.remove(imdb);
        int year = number(t.year);
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(score.entrySet());
        for (Map.Entry<String, Double> e : ranked) {
            int y = number(byId.get(e.getKey()).year);
            if (year > 0 && y > 0 && Math.abs(y - year) <= 5) e.setValue(e.getValue() + 0.3);
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Catalog.Title> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) if (out.size() < MAX) out.add(byId.get(e.getKey()));
        return out;
    }

    /** Each title of a list scores `weight`, falling to half of it down the list. */
    private static void add(Map<String, Double> score, Map<String, Catalog.Title> byId, List<Catalog.Title> list, double weight) {
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            Catalog.Title t = list.get(i);
            if (!t.id.startsWith("tt")) continue;
            score.merge(t.id, weight * (1 - 0.5 * i / list.size()), Double::sum);
            byId.putIfAbsent(t.id, t);
        }
    }

    private static boolean has(List<Catalog.Title> list, String id) {
        if (list != null) for (Catalog.Title t : list) if (t.id.equals(id)) return true;
        return false;
    }

    private static int number(String year) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(String.valueOf(year));
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    // ---- anime ----

    static List<Catalog.Title> anime(String imdb) {
        List<Catalog.Title> out = new ArrayList<>();
        try {
            String arm = Catalog.get("https://arm.haglund.dev/api/v2/imdb?id=" + imdb, true);
            if (arm == null) return out;
            JSONArray entries = new JSONArray(arm);
            long anilist = -1;
            for (int i = 0; i < entries.length() && anilist < 0; i++) anilist = entries.getJSONObject(i).optLong("anilist", -1);
            if (anilist < 0) return out;
            String q = "{\"query\":\"{Media(id:" + anilist + "){recommendations(sort:RATING_DESC,perPage:30){nodes{mediaRecommendation{id format "
                + "title{english romaji} coverImage{large} startDate{year}}}}}}\"}";
            JSONArray nodes = new JSONObject(post("https://graphql.anilist.co", q)).getJSONObject("data").getJSONObject("Media")
                .getJSONObject("recommendations").getJSONArray("nodes");
            JSONArray ask = new JSONArray();
            List<JSONObject> media = new ArrayList<>();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject m = nodes.getJSONObject(i).optJSONObject("mediaRecommendation");
                if (m == null) continue;
                media.add(m);
                ask.put(new JSONObject().put("anilist", m.getLong("id")));
            }
            if (media.isEmpty()) return out;
            JSONArray ids = new JSONArray(post("https://arm.haglund.dev/api/v2/ids", ask.toString()));   // same order as asked; null when unknown
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < media.size() && i < ids.length() && out.size() < MAX; i++) {
                JSONObject m = media.get(i), id = ids.optJSONObject(i);
                String tt = id == null ? "" : id.optString("imdb");
                if (!tt.startsWith("tt") || tt.equals(imdb) || !seen.add(tt)) continue;   // the seasons of one show share an IMDb id
                Catalog.Title t = new Catalog.Title();
                t.id = tt;
                t.type = "MOVIE".equals(m.optString("format")) ? "movie" : "series";
                JSONObject title = m.optJSONObject("title");
                t.name = title == null ? "" : !title.isNull("english") ? title.optString("english") : title.optString("romaji");
                t.poster = m.optJSONObject("coverImage") == null ? "" : m.getJSONObject("coverImage").optString("large");
                t.year = m.optJSONObject("startDate") == null || m.getJSONObject("startDate").isNull("year") ? "" : String.valueOf(m.getJSONObject("startDate").optInt("year"));
                t.anime = true;
                if (!t.name.isEmpty()) out.add(t);
            }
        } catch (IOException | JSONException e) {
            android.util.Log.w("Recs", "anime " + imdb + ": " + e.getMessage());
        }
        return out;
    }

    /** A POST with a JSON body, its answer kept a day (same as the catalogs' cache). */
    private static String post(String url, String body) throws IOException {
        File f = Catalog.file(url + "#" + body);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) return Sites.readAll(new java.io.FileInputStream(f));
        java.net.HttpURLConnection c = Warp.connect(url, java.net.Proxy.NO_PROXY, 6000, 15000);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            String text;
            try (java.io.InputStream in = c.getInputStream()) { text = Sites.readAll(in); }
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
            return text;
        } finally {
            c.disconnect();
        }
    }
}
