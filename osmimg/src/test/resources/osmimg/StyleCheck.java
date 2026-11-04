// Runs mkgmap's style rules on single OSM objects and prints what each becomes.
// Run with: java -cp mkgmap.jar StyleCheck.java <style dir> <style-option or ""> < cases
// Each input line is "node k=v|k=v" or "way k=v|k=v"; each output line is
// "<input> => <type> \"<label>\" ..." (or "=> -" when nothing is made), roads with class, speed and
// the access bits (from mkgmap's RoadDef: denied modes are listed after "no:").

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import uk.me.parabola.imgfmt.app.Coord;
import uk.me.parabola.imgfmt.app.net.GeneralRouteRestriction;
import uk.me.parabola.mkgmap.general.MapCollector;
import uk.me.parabola.mkgmap.general.MapElement;
import uk.me.parabola.mkgmap.general.MapLine;
import uk.me.parabola.mkgmap.general.MapPoint;
import uk.me.parabola.mkgmap.general.MapRoad;
import uk.me.parabola.mkgmap.general.MapShape;
import uk.me.parabola.mkgmap.osmstyle.StyleImpl;
import uk.me.parabola.mkgmap.osmstyle.StyledConverter;
import uk.me.parabola.mkgmap.reader.osm.Element;
import uk.me.parabola.mkgmap.reader.osm.Node;
import uk.me.parabola.mkgmap.reader.osm.Style;
import uk.me.parabola.mkgmap.reader.osm.Way;
import uk.me.parabola.util.EnhancedProperties;

public class StyleCheck {
    static final String[] MODES = {"foot", "bike", "car", "delivery", "truck", "bus", "taxi", "emergency"};

    public static void main(String[] args) throws Exception {
        EnhancedProperties props = new EnhancedProperties();
        props.setProperty("style-file", args[0]);
        if (args.length > 1 && !args[1].isEmpty()) props.setProperty("style-option", args[1]);
        props.setProperty("route", "true");
        Style style = StyleImpl.readStyle(props);

        // One converter for all cases (mkgmap's rule cache is tied to it); each case sits at its own
        // latitude so every result can be traced back to its input line.
        List<String> cases = new ArrayList<>();
        List<List<String>> results = new ArrayList<>();
        MapCollector c = new MapCollector() {
            public void addToBounds(Coord co) {}
            public void addPoint(MapPoint p) { add(p.getLocation(), describe(p)); }
            public void addLine(MapLine l) { add(l.getPoints().get(0), "line " + describe(l)); }
            public void addShape(MapShape s) { add(s.getPoints().get(0), "area " + describe(s)); }
            public void addRoad(MapRoad r) {
                byte acc = r.getRoadDef().getAccess();
                StringBuilder no = new StringBuilder();
                for (int i = 0; i < 8; i++) if ((acc & (1 << i)) == 0) no.append(no.length() == 0 ? "" : ",").append(MODES[i]);
                add(r.getPoints().get(0), "road " + describe(r) + " class=" + r.getRoadDef().getRoadClass()
                    + " speed=" + r.getRoadDef().getRoadSpeed() + " no:" + no);
            }
            public int addRestriction(GeneralRouteRestriction g) { return 0; }
            void add(Coord co, String what) {
                int i = (int) Math.round((co.getLatDegrees() - 38.0) * 100);
                if (i >= 0 && i < results.size()) results.get(i).add(what);
            }
        };
        StyledConverter conv = new StyledConverter(style, c, props);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String line;
        long id = 1;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int n = cases.size();
            cases.add(line);
            results.add(new ArrayList<>());
            double lat = 38.0 + n / 100.0;
            String kind = line.substring(0, line.indexOf(' '));
            String tags = line.substring(kind.length() + 1);
            Element e;
            if (kind.equals("node")) {
                e = new Node(id++, new Coord(lat, -9.10));
            } else {
                Way w = new Way(id++);
                w.addPoint(new Coord(lat, -9.100));
                w.addPoint(new Coord(lat + 0.001, -9.101));
                e = w;
            }
            for (String kv : tags.split("\\|")) {
                int eq = kv.indexOf('=');
                e.addTag(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
            if (e instanceof Node) conv.convertNode((Node) e); else conv.convertWay((Way) e);
        }
        conv.end();
        for (int i = 0; i < cases.size(); i++) {
            List<String> r = results.get(i);
            System.out.println(cases.get(i) + " => " + (r.isEmpty() ? "-" : String.join(" | ", r)));
        }
    }

    static String describe(MapElement m) {
        return String.format("0x%x", m.getType()) + (m.getName() == null ? "" : " \"" + m.getName() + "\"");
    }
}
