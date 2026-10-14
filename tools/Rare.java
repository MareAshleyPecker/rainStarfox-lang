import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Flags characters in a file that never occur anywhere else in the project.
 *
 * The prose of this project uses a small, consistent character set, so a character that appears
 * nowhere else is a strong hint that it was guessed wrong when the file was recovered.
 */
public class Rare {
    public static void main(String[] args) throws IOException {
        Path target = Paths.get(args[0]);
        Path root = Paths.get(args[1]);
        int threshold = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        Map<Character, Integer> freq = new HashMap<>();
        Files.walk(root)
             .filter(p -> {
                 String s = p.toString();
                 return (s.endsWith(".kt") || s.endsWith(".md") || s.endsWith(".rsf") || s.endsWith(".java"))
                         && !s.contains("build") && !s.contains(".gradle") && !s.endsWith("Checker.kt")
                         && !s.contains("tools");
             })
             .forEach(p -> {
                 try {
                     String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                     for (int i = 0; i < t.length(); i++) {
                         char c = t.charAt(i);
                         if (c > 0x7F) freq.merge(c, 1, Integer::sum);
                     }
                 } catch (IOException ignored) { }
             });
        String[] lines = new String(Files.readAllBytes(target), StandardCharsets.UTF_8).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c <= 0x7F || freq.getOrDefault(c, 0) > threshold) continue;
                int a = Math.max(0, i - 18), b = Math.min(line.length(), i + 18);
                sb.append("line ").append(li + 1).append("  U+").append(String.format("%04X", (int) c))
                  .append(" freq=").append(freq.getOrDefault(c, 0))
                  .append("  ...").append(line, a, i).append('[').append(c).append(']')
                  .append(line, i + 1, b).append("...\n");
                n++;
            }
        }
        Files.write(Paths.get(args[2]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("flagged spots: " + n);
    }
}
