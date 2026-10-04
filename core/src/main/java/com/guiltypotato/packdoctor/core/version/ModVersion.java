package com.guiltypotato.packdoctor.core.version;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A mod version compared exactly the way NeoForge does it: Maven's ComparableVersion rules.
 * So "1.0" == "1.0.0", alpha &lt; beta &lt; rc &lt; snapshot &lt; release &lt; sp, and a "-" starts a sub-version:
 * "1.21.1-3.9.9" is newer than "1.21-3.5.0" because 1.21.1 &gt; 1.21 is decided first.
 */
public final class ModVersion implements Comparable<ModVersion> {
    private final String raw;
    private final ListItem items;

    private ModVersion(String raw, ListItem items) {
        this.raw = raw;
        this.items = items;
    }

    public static ModVersion parse(String raw) {
        String version = raw.trim().toLowerCase(Locale.ENGLISH);
        ListItem root = new ListItem();
        ListItem list = root;
        Deque<ListItem> stack = new ArrayDeque<>();
        stack.push(list);
        boolean isDigit = false;
        int start = 0;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '.') {
                list.add(i == start ? IntItem.ZERO : parseItem(isDigit, version.substring(start, i)));
                start = i + 1;
            } else if (c == '-') {
                list.add(i == start ? IntItem.ZERO : parseItem(isDigit, version.substring(start, i)));
                start = i + 1;
                ListItem sub = new ListItem();
                list.add(sub);
                list = sub;
                stack.push(list);
            } else if (Character.isDigit(c)) {
                if (!isDigit && i > start) {
                    list.add(new StringItem(version.substring(start, i), true));
                    start = i;
                    ListItem sub = new ListItem();
                    list.add(sub);
                    list = sub;
                    stack.push(list);
                }
                isDigit = true;
            } else {
                if (isDigit && i > start) {
                    list.add(parseItem(true, version.substring(start, i)));
                    start = i;
                    ListItem sub = new ListItem();
                    list.add(sub);
                    list = sub;
                    stack.push(list);
                }
                isDigit = false;
            }
        }
        if (version.length() > start) list.add(parseItem(isDigit, version.substring(start)));
        while (!stack.isEmpty()) stack.pop().normalize();
        return new ModVersion(raw.trim(), root);
    }

    private static Item parseItem(boolean isDigit, String s) {
        if (isDigit) {
            String t = s.replaceFirst("^0+(?=.)", "");
            return t.length() <= 18 ? new IntItem(Long.parseLong(t)) : new BigIntItem(new java.math.BigInteger(t));
        }
        return new StringItem(s, false);
    }

    @Override
    public int compareTo(ModVersion o) {
        return items.compareTo(o.items);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ModVersion v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return items.toString().hashCode(); // normalized form, so "1.0" and "1.0.0" agree
    }

    @Override
    public String toString() {
        return raw;
    }

    // ---- Maven ComparableVersion items ----

    private sealed interface Item permits IntItem, BigIntItem, StringItem, ListItem {
        int compareTo(Item other); // other may be null (= "nothing here")

        boolean isNull();
    }

    private record IntItem(long value) implements Item {
        static final IntItem ZERO = new IntItem(0);

        public int compareTo(Item other) {
            if (other == null) return value == 0 ? 0 : 1;
            if (other instanceof IntItem i) return Long.compare(value, i.value);
            if (other instanceof BigIntItem) return -1;
            return 1; // 1.1 > 1-sp, 1.1 > 1-1
        }

        public boolean isNull() {
            return value == 0;
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    private record BigIntItem(java.math.BigInteger value) implements Item {
        public int compareTo(Item other) {
            if (other == null) return value.signum() == 0 ? 0 : 1;
            if (other instanceof BigIntItem b) return value.compareTo(b.value);
            if (other instanceof IntItem) return 1;
            return 1;
        }

        public boolean isNull() {
            return value.signum() == 0;
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private static final List<String> QUALIFIERS = List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");
    private static final String RELEASE_INDEX = String.valueOf(QUALIFIERS.indexOf(""));

    private static final class StringItem implements Item {
        private final String value;

        StringItem(String value, boolean followedByDigit) {
            if (followedByDigit && value.length() == 1) {
                value = switch (value.charAt(0)) {
                    case 'a' -> "alpha";
                    case 'b' -> "beta";
                    case 'm' -> "milestone";
                    default -> value;
                };
            }
            this.value = switch (value) {
                case "ga", "final", "release" -> "";
                case "cr" -> "rc";
                default -> value;
            };
        }

        static String comparable(String q) {
            int i = QUALIFIERS.indexOf(q);
            return i == -1 ? QUALIFIERS.size() + "-" + q : String.valueOf(i);
        }

        public int compareTo(Item other) {
            if (other == null) return comparable(value).compareTo(RELEASE_INDEX); // 1-rc < 1, 1-sp > 1
            if (other instanceof StringItem s) return comparable(value).compareTo(comparable(s.value));
            return -1; // 1.any < 1.1, 1.any < 1-1
        }

        public boolean isNull() {
            return comparable(value).equals(RELEASE_INDEX);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private static final class ListItem extends ArrayList<Item> implements Item {
        void normalize() {
            for (int i = size() - 1; i >= 0; i--) {
                Item last = get(i);
                if (last.isNull()) remove(i);
                else if (!(last instanceof ListItem)) break;
            }
        }

        public int compareTo(Item other) {
            if (other == null) return isEmpty() ? 0 : get(0).compareTo(null);
            if (other instanceof IntItem || other instanceof BigIntItem) return -1; // 1-1 < 1.0.x
            if (other instanceof StringItem) return 1; // 1-1 > 1-sp
            ListItem o = (ListItem) other;
            int n = Math.max(size(), o.size());
            for (int i = 0; i < n; i++) {
                Item l = i < size() ? get(i) : null;
                Item r = i < o.size() ? o.get(i) : null;
                int result = l == null ? (r == null ? 0 : -r.compareTo(null)) : l.compareTo(r);
                if (result != 0) return result;
            }
            return 0;
        }

        public boolean isNull() {
            return isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("(");
            for (Item i : this) {
                if (sb.length() > 1) sb.append(i instanceof ListItem ? '-' : '.');
                sb.append(i);
            }
            return sb.append(')').toString();
        }
    }
}
