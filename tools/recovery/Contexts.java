import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Lists control characters (lost bytes) with context, so they can be repaired by hand. */
public class Contexts {
    public static void main(String[] args) throws IOException {
        String t = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        String[] lines = t.split("\n", -1);
        int offset = 0;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c < ' ' && c != '\t' && c != '\r') {
                    int a = Math.max(0, i - 22), b = Math.min(line.length(), i + 22);
                    sb.append("line ").append(li + 1).append("  cp=U+")
                      .append(String.format("%04X", (int) c))
                      .append("  ...[").append(line.substring(a, i)).append("<").append(line.substring(i + 1, b)).append(">...\n");
                }
            }
            offset += line.length() + 1;
        }
        Files.write(Paths.get(args[1]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("contexts written");
    }
}
