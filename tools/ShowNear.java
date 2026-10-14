import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Shows, for every string constant of the pristine build that is missing from a source file,
 * the place in that source that most looks like it — so each mismatch can be reviewed by hand.
 */
public class ShowNear {
    public static void main(String[] args) throws IOException {
        String src = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        List<String> frags = Recover2.fragmentsOf(Paths.get(args[1]));
        StringBuilder sb = new StringBuilder();
        String[] lines = src.split("\n", -1);
        for (String f : frags) {
            if (src.contains(f)) continue;
            sb.append("MISSING: ").append(f).append('\n');
            // best matching window over the source
            int best = Integer.MAX_VALUE;
            int bestAt = -1;
            String bestWin = null;
            for (int i = 0; i + 2 <= src.length(); i += 1) {
                int len = Math.min(src.length() - i, f.length() + 4);
                String win = src.substring(i, i + len);
                if (Math.abs(win.length() - f.length()) > 6) continue;
                int d = dist(f, win);
                if (d < best) { best = d; bestAt = i; bestWin = win; }
            }
            if (bestWin != null) {
                int lineNo = 1;
                for (int k = 0; k < bestAt; k++) if (src.charAt(k) == '\n') lineNo++;
                sb.append("  nearest line ").append(lineNo).append(": ").append(bestWin.replace('\n', ' ')).append('\n');
                sb.append("  (distance ").append(best).append(")\n");
            }
            sb.append('\n');
        }
        Files.write(Paths.get(args[2]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("report written, checked " + frags.size() + " constants");
    }

    static int dist(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
