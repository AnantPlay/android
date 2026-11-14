package me.champdev.weblock;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One streaming service: its brands as tiles (Disney+ > Marvel, Pixar...), then what is popular on it, movies or series.
 * Opened with "brand" it is that brand's page: all its movies and series, newest first.
 */
public class PlatformActivity extends Home {
    String kind;   // movie | series: the list showing

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
        if (brand != null) { brandPage(id, brand, at); return; }

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
            TextView c = chip(v -> { kind = k; build(); });
            c.setText(App.t(k.equals("movie") ? R.string.home_tab_movies : R.string.title_show));
            c.setTextColor(k.equals(kind) ? BG : TEXT);
            c.setBackground(rounded(k.equals(kind) ? TEXT : CARD, dp(22)));
            focusable(c, dp(22));
            chips.addView(c);
        }
        col.addView(chips);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        col.addView(holder);
        loadPage(kind, Catalog.PLATFORM + id, 0, holder, new Grid(holder, App.tv ? 8 : 3), built);
        focusFirst = App.tv && firstCard == null && !skipFocus;   // the list is not here yet: showPage hands the remote over
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
                for (String k : new String[]{"movie", "series"}) {
                    List<Catalog.Title> list = new ArrayList<>();
                    for (Catalog.Title t : b.titles) if (t.type.equals(k)) list.add(t);
                    if (list.isEmpty()) continue;
                    list.sort((x, y) -> y.year.compareTo(x.year));   // newest first; the job lists them so already, this keeps it true
                    col.addView(heading(App.t(k.equals("movie") ? R.string.home_tab_movies : R.string.title_show), String.valueOf(list.size())));
                    Grid grid = new Grid(col, App.tv ? 8 : 3);
                    for (Catalog.Title t : list) grid.add(posterCard(t, grid.cell));
                }
                if (App.tv && firstCard != null && !skipFocus) focusContent(firstCard);
            });
        });
    }
}
