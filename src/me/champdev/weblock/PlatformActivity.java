package me.champdev.weblock;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One streaming service: its brands as tiles (Disney+ > Marvel, Pixar...), then what is popular on it, movies or series.
 * Opened with "brand" it is that brand's page: chips that narrow it (Marvel: All, MCU, Avengers, Animation, Movies, Series),
 * rows of what is trending, popular and top rated, then all its movies and series, newest first.
 */
public class PlatformActivity extends Home {
    String kind;   // movie | series: the list showing
    boolean focusKind;   // a Movies / Series chip was just pressed: the remote stays on it
    String filter = "";   // brand page: "" all, movie | series, or one of the brand's groups ("mcu")

    @Override boolean tabs() { return false; }

    @Override void build() {
        col.removeAllViews();
        firstCard = null;
        nextPage = null;
        stepCards = 0;
        built++;
        String id = getIntent().getStringExtra("platform"), brand = getIntent().getStringExtra("brand");
        String[] s = Catalog.service(id);
        if (s == null) { finish(); return; }
        if (kind == null) kind = "series".equals(getIntent().getStringExtra("kind")) ? "series" : "movie";
        final int at = built;
        if (brand != null) { focusFirst = App.tv && !skipFocus; brandPage(id, brand, at); return; }

        TextView title = text(s[1], sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(6));
        col.addView(title);
        // Brands arrive a moment later (one small file, kept for 6 hours); a service without any keeps no gap
        LinearLayout brands = new LinearLayout(this);
        brands.setOrientation(LinearLayout.VERTICAL);
        col.addView(brands);
        App.io.execute(() -> {
            List<Catalog.Brand> list = Catalog.brands(id);
            runOnUiThread(() -> {
                if (at != built || list.isEmpty()) return;
                LinearLayout row = row(brands);
                for (Catalog.Brand b : list)
                    row.addView(platformTile(b.name, b.color, v -> startActivity(new Intent(this, PlatformActivity.class).putExtra("platform", id).putExtra("brand", b.id))));
            });
        });

        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, u(10, 6), 0, u(6, 4));
        for (String k : new String[]{"movie", "series"}) {
            TextView c = chip(v -> { kind = k; focusKind = true; build(); });
            c.setText(App.t(k.equals("movie") ? R.string.home_tab_movies : R.string.title_show));
            c.setTextColor(k.equals(kind) ? BG : TEXT);
            c.setBackground(rounded(k.equals(kind) ? TEXT : CARD, dp(22)));
            focusable(c, dp(22));
            if (App.tv && focusKind && k.equals(kind)) c.post(c::requestFocus);
            chips.addView(c);
        }
        col.addView(chips);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        col.addView(holder);
        loadPage(kind, Catalog.PLATFORM + id, 0, holder, new Grid(holder, posterCols()), built);
        focusFirst = App.tv && firstCard == null && !skipFocus && !focusKind;   // the list is not here yet: showPage hands the remote over
        android.view.View first = firstCard;   // a list that was here at once: the remote starts on its first poster (not when coming back to this page)
        if (App.tv && first != null && !skipFocus && !focusKind && !first.requestFocus()) col.post(first::requestFocus);
        focusKind = false;
    }

    void brandPage(String platform, String brand, int at) {
        TextView title = text("", sp(26, 22), TEXT, true);
        title.setPadding(0, 0, 0, dp(6));
        col.addView(title);
        App.io.execute(() -> {
            Catalog.Brand found = null;
            for (Catalog.Brand b : Catalog.brands(platform)) if (b.id.equals(brand)) found = b;
            final Catalog.Brand b = found;
            runOnUiThread(() -> {
                if (at != built) return;
                if (b == null) { col.addView(note(App.t(R.string.home_catalog_offline))); return; }
                title.setText(b.name);
                try { title.setTextColor(android.graphics.Color.parseColor(b.color)); } catch (RuntimeException ignored) { }
                LinearLayout body = new LinearLayout(this);
                body.setOrientation(LinearLayout.VERTICAL);
                col.addView(body);
                showBrand(b, body, false);
                if (App.tv && firstCard != null && !skipFocus) focusContent(firstCard);
            });
        });
    }

    /** The chips, then the titles the picked one keeps: movies, then series. A chip that was just pressed keeps the remote. */
    void showBrand(Catalog.Brand b, LinearLayout body, boolean pressed) {
        body.removeAllViews();
        firstCard = null;
        List<String[]> chips = new ArrayList<>();   // {filter, label}
        chips.add(new String[]{"", App.t(R.string.home_all)});
        for (String[] g : b.groups)
            chips.add(new String[]{g[0], g[0].equals("animation") ? App.t(R.string.home_animation) : g[0].equals("documentary") ? App.t(R.string.home_documentaries) : g[1]});
        boolean movies = false, series = false;
        for (Catalog.Title t : b.titles) { if (t.series()) series = true; else movies = true; }
        if (movies && series) {
            chips.add(new String[]{"movie", App.t(R.string.home_tab_movies)});
            chips.add(new String[]{"series", App.t(R.string.title_show)});
        }
        if (chips.size() > 1) {
            LinearLayout row = row(body);
            for (String[] c : chips) {
                boolean on = c[0].equals(filter);
                TextView chip = chip(v -> { filter = c[0]; showBrand(b, body, true); });
                chip.setText(c[1]);
                chip.setTextColor(on ? BG : TEXT);
                chip.setBackground(rounded(on ? TEXT : CARD, dp(22)));
                focusable(chip, dp(22));
                if (App.tv && pressed && on) chip.post(chip::requestFocus);
                row.addView(chip);
            }
        }
        List<Catalog.Title> kept = new ArrayList<>(), rated = new ArrayList<>();
        for (Catalog.Title t : b.titles) if (filter.isEmpty() || filter.equals(t.type) || t.tagged(filter)) kept.add(t);
        if (kept.size() >= 30) {   // a short list is all on screen already
            for (Catalog.Title t : kept) if (t.votes >= 200) rated.add(t);
            top(body, R.string.home_trending, kept, (x, y) -> Float.compare(y.pop, x.pop));
            top(body, R.string.home_popular, kept, (x, y) -> y.votes - x.votes);
            if (rated.size() >= 5) top(body, R.string.home_top_rated, rated, (x, y) -> Float.compare(number(y.rating), number(x.rating)));
        }
        for (String k : new String[]{"movie", "series"}) {
            List<Catalog.Title> list = new ArrayList<>();
            for (Catalog.Title t : kept) if (t.type.equals(k)) list.add(t);
            if (list.isEmpty()) continue;
            list.sort((x, y) -> y.year.compareTo(x.year));   // newest first; the job lists them so already, this keeps it true
            body.addView(heading(App.t(k.equals("movie") ? R.string.home_tab_movies : R.string.title_show), String.valueOf(list.size())));
            Grid grid = new Grid(body, posterCols());
            for (Catalog.Title t : list) grid.add(posterCard(t, grid.cell));
        }
    }

    static float number(String s) {
        try { return Float.parseFloat(s); } catch (NumberFormatException e) { return 0; }
    }

    /** A row of the 15 titles that come first in this order; none when the file has no numbers to order by (an older one). */
    void top(LinearLayout body, int name, List<Catalog.Title> from, java.util.Comparator<Catalog.Title> order) {
        List<Catalog.Title> list = new ArrayList<>(from);
        list.sort(order);
        if (order.compare(list.get(0), list.get(list.size() - 1)) == 0) return;
        body.addView(heading(App.t(name)));
        LinearLayout row = row(body);
        for (Catalog.Title t : list.subList(0, Math.min(15, list.size()))) row.addView(posterCard(t, posterW()));
    }
}
