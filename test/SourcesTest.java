// JSON=~/.gradle/caches/modules-2/files-2.1/org.json/json/20240303/*/json-20240303.jar
// javac -nowarn -implicit:none -cp "$JSON:$SDK/platforms/android-34/android.jar:$(ls libs/*.jar | tr '\n' ':')" -sourcepath src:build -d /tmp/sources src/me/champdev/weblock/Sources.java src/me/champdev/weblock/Sites.java src/me/champdev/weblock/Cats.java test/SourcesTest.java && java -cp "/tmp/sources:$JSON:$SDK/platforms/android-34/android.jar" me.champdev.weblock.SourcesTest
package me.champdev.weblock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SourcesTest {
    static int fails;
    static void check(String name, Object got, Object want) {
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    public static void main(String[] a) {
        String json = "{\"name\":\"x\",\"sources\":["
            + "{\"id\":\"sites\",\"name\":\"Sites\",\"type\":\"sites\",\"url\":\"https://e.example/sites.json\",\"default\":true},"
            + "{\"id\":\"tv\",\"name\":\"TV\",\"type\":\"playlist\",\"url\":\"https://e.example/tv.m3u\",\"languages\":\"https://e.example/lang.m3u\"},"
            + "{\"id\":\"x\",\"name\":\"X\",\"type\":\"playlist\",\"url\":\"https://e.example/x.m3u\",\"adult\":true,\"default\":true},"
            + "{\"id\":\"dup\",\"name\":\"Dup\",\"type\":\"playlist\",\"url\":\"https://e.example/tv.m3u\"},"
            + "{\"id\":\"bad\",\"name\":\"Bad\",\"type\":\"playlist\",\"url\":\"ftp://e.example/a\"},"
            + "{\"id\":\"future\",\"name\":\"Future\",\"type\":\"addon\",\"url\":\"https://e.example/addon\"}]}";
        List<Sources.Source> s = Sources.parseIndex(json, "https://e.example/index.json");
        check("json count (duplicate and non-http dropped)", s.size(), 4);
        check("json sites", s.get(0).type + " " + s.get(0).isDefault + " " + s.get(0).index, "sites true https://e.example/index.json");
        check("json languages", s.get(1).languages, "https://e.example/lang.m3u");
        check("json adult", s.get(2).adult, true);
        check("unknown type kept but not readable", s.get(3).known(), false);
        check("broken json", Sources.parseIndex("{\"sources\": [", "i").size(), 0);

        String legacy = "# notes\n@deeplinks|https://e.example/deeplinks.json\nVerified|https://e.example/v.m3u|18\nPlain|https://e.example/p.m3u\n@other|https://e.example/o\nbroken line\n";
        List<Sources.Source> l = Sources.parseIndex(legacy, "https://e.example/sources.txt");
        check("legacy count", l.size(), 3);
        check("legacy deeplinks", l.get(0).type + " '" + l.get(0).name + "'", "deeplinks ''");
        check("legacy adult", l.get(1).type + " " + l.get(1).name + " " + l.get(1).adult, "playlist Verified true");
        check("legacy plain", l.get(2).adult + " " + l.get(2).isDefault, "false true");

        Map<String, List<Sites.Site>> rows = new LinkedHashMap<>(), langs = new LinkedHashMap<>();
        for (String c : Cats.ORDER) rows.put(c, new ArrayList<>());
        Sites.parse("{\"rows\":{\"movies\":[{\"name\":\"A\",\"url\":\"https://a.example\",\"mirrors\":[\"https://a2.example\",\"bad\"],\"tags\":[\"4K\",\"Dual Audio\"],\"group\":\"Multi\",\"pick\":true},"
            + "{\"name\":\"\",\"url\":\"https://noname.example\"},{\"name\":\"B\",\"url\":\"javascript:x\"}],\"live\":[{\"name\":\"L\",\"url\":\"https://l.example\"}]},"
            + "\"languages\":{\"Spanish\":[{\"name\":\"Es\",\"url\":\"https://es.example\"}]}}", rows, langs);
        Sites.parse("{\"rows\":{\"movies\":[{\"name\":\"A again\",\"url\":\"https://a.example\"},{\"name\":\"C\",\"url\":\"https://c.example\"}]}}", rows, langs);
        Sites.parse("not json", rows, langs);
        List<Sites.Site> movies = rows.get(Cats.MOVIES);
        check("movies merged, first source wins", movies.size() + " " + movies.get(0).name + " " + movies.get(1).name, "2 A C");
        check("site fields", movies.get(0).tags + " | " + movies.get(0).mirrors + " | " + movies.get(0).group + " " + movies.get(0).pick, "4K · Dual Audio | [https://a2.example] | Multi true");
        check("live row by key", rows.get(Cats.LIVE).size(), 1);
        check("languages", langs.get("Spanish").get(0).name, "Es");

        System.out.println(fails == 0 ? "all source expectations pass" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
