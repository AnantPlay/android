// JSON=~/.gradle/caches/modules-2/files-2.1/org.json/json/20240303/*/json-20240303.jar
// javac -nowarn -implicit:none -cp "$JSON:$SDK/platforms/android-34/android.jar:$(ls libs/*.jar | tr '\n' ':')" -sourcepath src:build -d /tmp/platform src/me/champdev/weblock/Catalog.java test/PlatformTest.java && java -cp "/tmp/platform:$JSON:$SDK/platforms/android-34/android.jar" me.champdev.weblock.PlatformTest
package me.champdev.weblock;

import java.util.List;

public class PlatformTest {
    static int fails;
    static void check(String name, Object got, Object want) {
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
    }

    public static void main(String[] a) {
        StringBuilder in = new StringBuilder(), us = new StringBuilder(), none = new StringBuilder();
        for (String[] s : Catalog.services("in")) in.append(s[0]).append(' ');
        for (String[] s : Catalog.services("US")) us.append(s[0]).append(' ');
        for (String[] s : Catalog.services("")) none.append(s[0]).append(' ');
        check("India adds its own services", in.toString().trim(), "nfx amp dnp jhs hbm atp zee pmp cru");
        check("the US adds its own", us.toString().trim(), "nfx amp dnp hbm atp hlu pcp pmp cru");
        check("no country: the ones that are everywhere", none.toString().trim(), "nfx amp dnp hbm atp pmp cru");
        check("address", Catalog.platformPath("series", "dnp"), Catalog.PLATFORMS + "/ZG5wOjpVUzowOjA6MDo%3D/catalog/series/dnp.json");

        String json = "{\"platforms\":{\"dnp\":{\"brands\":["
            + "{\"id\":\"marvel\",\"name\":\"Marvel\",\"color\":\"#E62429\",\"titles\":[{\"id\":\"tt4154796\",\"type\":\"movie\",\"name\":\"Avengers: Endgame\",\"year\":\"2019\",\"poster\":\"https://i.example/a.jpg\",\"tmdb\":\"299534\"},"
            + "{\"id\":\"tt9140560\",\"type\":\"series\",\"name\":\"WandaVision\",\"year\":\"2021\"},{\"id\":\"nope\",\"type\":\"movie\",\"name\":\"No IMDb id\"},{\"id\":\"tt1\",\"type\":\"movie\",\"name\":\"\"}]},"
            + "{\"id\":\"empty\",\"name\":\"Empty\",\"titles\":[]},{\"id\":\"\",\"name\":\"No id\",\"titles\":[{\"id\":\"tt2\",\"name\":\"X\"}]}]}}}";
        List<Catalog.Brand> b = Catalog.parseBrands(json, "dnp");
        check("brands kept (empty and unnamed dropped)", b.size(), 1);
        check("titles kept (no IMDb id, no name dropped)", b.get(0).titles.size(), 2);
        check("title fields", b.get(0).titles.get(0).name + " " + b.get(0).titles.get(0).type + " " + b.get(0).titles.get(0).tmdb + " | " + b.get(0).titles.get(1).type, "Avengers: Endgame movie 299534 | series");
        check("another platform has none", Catalog.parseBrands(json, "nfx").size(), 0);
        check("broken file", Catalog.parseBrands("{\"platforms\":", "dnp").size(), 0);

        System.out.println(fails == 0 ? "all platform expectations pass" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
