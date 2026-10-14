import com.guiltypotato.checkengine.core.crash.CrashTranslator;
import java.nio.file.*;
import java.util.*;
public class Summary { public static void main(String[] a) throws Exception {
  Map<String,Integer> codes = new TreeMap<>(); int total=0, generic=0;
  try (var s = Files.list(Path.of(a[0]))) { for (Path p : s.filter(f->f.toString().endsWith(".txt")).toList()) {
    var f = CrashTranslator.translate(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8)).findings().get(0);
    total++; codes.merge(f.code(), 1, Integer::sum);
    if (f.code().equals("crash") && f.title().equals("Game crashed") || f.code().equals("unknown-crash") || f.title().equals("Mod loading problem")) generic++;
  } }
  System.out.println("total " + total + ", no mod named: " + generic);
  codes.entrySet().stream().sorted((x,y)->y.getValue()-x.getValue()).forEach(e->System.out.println("  " + e.getValue() + " " + e.getKey()));
}}
