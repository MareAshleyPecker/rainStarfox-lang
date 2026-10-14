import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Lists the recovered spots whose character was guessed from the prose corpus with weak evidence
 * (or not guessed at all) — the places most likely to still be wrong.
 */
public class WeakSpots {
    public static void main(String[] args) throws IOException {
        List<String> log = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int weak = 0;
        for (String line : log) {
            if (line.contains("(exact)")) continue;
            int score = -1;
            int at = line.indexOf("score=");
            if (at >= 0) {
                try { score = Integer.parseInt(line.substring(at + 6).trim()); } catch (Exception ignored) { }
            }
            if (score >= 4) continue;      // strong corpus evidence: keep
            weak++;
            sb.append(line).append('\n');
        }
        Files.write(Paths.get(args[1]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("weak spots: " + weak + " of " + log.size());
    }
}
