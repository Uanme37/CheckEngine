import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import java.nio.file.*;
import java.util.*;

public class NatSum {
    public static void main(String[] a) throws Exception {
        Map<String, Integer> t = new TreeMap<>();
        int n = 0;
        try (var s = Files.list(Path.of(a[0]))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".txt")).toList()) {
                var f = CrashTranslator.translate(Files.readString(p)).findings().get(0);
                n++;
                String title = f.title();
                int i = title.indexOf('(');
                if (i > 0 && !title.startsWith("Graphics")) title = title.substring(0, i) + "(...)";
                t.merge(title, 1, Integer::sum);
            }
        }
        System.out.println("total " + n);
        t.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> System.out.println("  " + e.getValue() + " " + e.getKey()));
    }
}
