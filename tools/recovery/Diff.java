import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Prints the lines that differ between two files (for reviewing an automated repair). */
public class Diff {
    public static void main(String[] args) throws IOException {
        String[] a = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8).split("\n", -1);
        String[] b = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int[] idx = align(a, b);
        int i = 0, j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) { i++; j++; continue; }
            sb.append("A ").append(i + 1).append(": ").append(i < a.length ? a[i] : "").append('\n');
            sb.append("B ").append(j + 1).append(": ").append(j < b.length ? b[j] : "").append('\n');
            i++; j++;
        }
        Files.write(Paths.get(args[2]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("diff written");
    }

    static int[] align(String[] a, String[] b) { return new int[] { 0, 0 }; }
}
