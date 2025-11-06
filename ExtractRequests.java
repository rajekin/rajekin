if (left.isArray()) {
    diffArrayUnorderedLoose(path,
        (com.fasterxml.jackson.databind.node.ArrayNode) left,
        (com.fasterxml.jackson.databind.node.ArrayNode) right,
        out);
    return;
}

// scalars (or any non-container values)
if (!equalIgnoringType(left, right)) {
    out.add(new Diff(path.toString(), jsonPointerToXPath(path),
            DiffKind.VALUE_MISMATCH, str(left), str(right)));
}
return;


// ---------- knobs you can tweak ----------
private static final boolean NORMALIZE_STRING_CASE = false; // true => "Approve" == "approve"
private static final Set<String> IGNORE_FIELDS = java.util.Set.of(); // add noisy fields to ignore globally
// ----------------------------------------

// Type-agnostic equality (numbers vs numeric strings, bool vs "true"/"false", trimmed strings, null~missing)
private static boolean equalIgnoringType(JsonNode a, JsonNode b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    if (a.isMissingNode() && b.isNull()) return true;
    if (b.isMissingNode() && a.isNull()) return true;

    if (a.isContainerNode() || b.isContainerNode()) {
        // handled elsewhere (objects/arrays recurse), this guard is for safety in case of odd calls
        return a.toString().equals(b.toString());
    }

    // normalize both into the same canonical scalar form
    String na = canonicalScalar(a);
    String nb = canonicalScalar(b);
    return java.util.Objects.equals(na, nb);
}

// Canonical scalar string for type-agnostic compare
private static String canonicalScalar(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) return "∅";
    if (n.isNumber()) {
        try {
            return "num:" + new java.math.BigDecimal(n.asText().trim())
                    .stripTrailingZeros().toPlainString();
        } catch (Exception e) { /* fall through to text */ }
    }
    if (n.isBoolean()) return "bool:" + (n.asBoolean() ? "true" : "false");

    // textual → try number/bool first, else normalized text
    String s = n.asText();
    String t = s == null ? "" : s.trim();

    // numeric string?
    try {
        java.math.BigDecimal bd = new java.math.BigDecimal(t);
        return "num:" + bd.stripTrailingZeros().toPlainString();
    } catch (Exception ignore) {}

    // boolean string?
    String tl = t.toLowerCase(java.util.Locale.ROOT);
    if ("true".equals(tl) || "false".equals(tl)) return "bool:" + tl;

    if (NORMALIZE_STRING_CASE) t = tl;
    return "str:" + t;
}

// ---------- unordered, keyless, type-agnostic array diff ----------
private static void diffArrayUnorderedLoose(JsonPointer path,
        com.fasterxml.jackson.databind.node.ArrayNode leftArr,
        com.fasterxml.jackson.databind.node.ArrayNode rightArr,
        java.util.List<Diff> out) {

    // Both empty
    if (leftArr.size() == 0 && rightArr.size() == 0) return;

    // If both are pure scalars → multiset on canonical scalars
    if (allScalars(leftArr) && allScalars(rightArr)) {
        multisetScalarLoose(path, leftArr, rightArr, out);
        return;
    }

    // General case (objects/arrays/mixed): group by relaxed canonical signature
    java.util.Map<String, java.util.Deque<JsonNode>> L = bagBySignatureLoose(leftArr);
    java.util.Map<String, java.util.Deque<JsonNode>> R = bagBySignatureLoose(rightArr);

    java.util.Set<String> sigs = new java.util.TreeSet<>();
    sigs.addAll(L.keySet()); sigs.addAll(R.keySet());

    for (String sig : sigs) {
        java.util.Deque<JsonNode> ld = L.getOrDefault(sig, new java.util.ArrayDeque<>());
        java.util.Deque<JsonNode> rd = R.getOrDefault(sig, new java.util.ArrayDeque<>());

        // Pair up as many as possible (semantically same items)
        int matches = Math.min(ld.size(), rd.size());
        for (int i = 0; i < matches; i++) {
            JsonNode li = ld.pollFirst();
            JsonNode ri = rd.pollFirst();
            // Recurse so inner field-level diffs still show if any
            diffJson(path, li, ri, out);
        }

        // Leftovers signal missing on the other side
        while (!ld.isEmpty()) {
            JsonNode n = ld.pollFirst();
            out.add(new Diff(path.toString(), jsonPointerToXPath(path),
                    DiffKind.MISSING_RIGHT, str(n), "∅"));
        }
        while (!rd.isEmpty()) {
            JsonNode n = rd.pollFirst();
            out.add(new Diff(path.toString(), jsonPointerToXPath(path),
                    DiffKind.MISSING_LEFT, "∅", str(n)));
        }
    }
}

private static boolean allScalars(com.fasterxml.jackson.databind.node.ArrayNode a) {
    for (JsonNode n : a) if (n.isContainerNode()) return false;
    return true;
}

private static void multisetScalarLoose(JsonPointer path,
        com.fasterxml.jackson.databind.node.ArrayNode leftArr,
        com.fasterxml.jackson.databind.node.ArrayNode rightArr,
        java.util.List<Diff> out) {

    java.util.Map<String, Integer> L = new java.util.HashMap<>();
    java.util.Map<String, Integer> R = new java.util.HashMap<>();
    java.util.Map<String, String> sample = new java.util.HashMap<>();

    for (JsonNode n : leftArr) {
        String k = canonicalScalar(n);
        L.put(k, L.getOrDefault(k, 0) + 1);
        sample.putIfAbsent(k, str(n));
    }
    for (JsonNode n : rightArr) {
        String k = canonicalScalar(n);
        R.put(k, R.getOrDefault(k, 0) + 1);
        sample.putIfAbsent(k, str(n));
    }

    java.util.Set<String> keys = new java.util.TreeSet<>();
    keys.addAll(L.keySet()); keys.addAll(R.keySet());

    for (String k : keys) {
        int lc = L.getOrDefault(k, 0), rc = R.getOrDefault(k, 0);
        if (lc > rc) {
            for (int i = 0; i < lc - rc; i++)
                out.add(new Diff(path.toString(), jsonPointerToXPath(path),
                        DiffKind.MISSING_RIGHT, sample.get(k), "∅"));
        } else if (rc > lc) {
            for (int i = 0; i < rc - lc; i++)
                out.add(new Diff(path.toString(), jsonPointerToXPath(path),
                        DiffKind.MISSING_LEFT, "∅", sample.get(k)));
        }
    }
}

// Group array items by a relaxed canonical signature (objects: fields sorted & values normalized; arrays: children signatures sorted)
private static java.util.Map<String, java.util.Deque<JsonNode>> bagBySignatureLoose(com.fasterxml.jackson.databind.node.ArrayNode arr) {
    java.util.Map<String, java.util.Deque<JsonNode>> m = new java.util.HashMap<>();
    for (JsonNode n : arr) {
        String sig = canonicalSignatureLoose(n);
        m.computeIfAbsent(sig, k -> new java.util.ArrayDeque<>()).add(n);
    }
    return m;
}

private static String canonicalSignatureLoose(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) return "null";

    if (n.isValueNode()) return canonicalScalar(n); // already normalized with type-agnostic rules

    if (n.isObject()) {
        java.util.SortedMap<String,String> parts = new java.util.TreeMap<>();
        n.fieldNames().forEachRemaining(fn -> {
            if (!IGNORE_FIELDS.contains(fn)) {
                parts.put(fn, canonicalSignatureLoose(n.get(fn)));
            }
        });
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String,String> e : parts.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(escapeSig(e.getKey())).append(":").append(e.getValue());
        }
        return sb.append("}").toString();
    }

    // array: normalize each child then sort so order doesn't matter
    java.util.List<String> children = new java.util.ArrayList<>();
    for (JsonNode c : n) children.add(canonicalSignatureLoose(c));
    java.util.Collections.sort(children);
    return "[" + String.join(",", children) + "]";
}

private static String escapeSig(String s) {
    return s.replace("\\","\\\\").replace(":","\\:").replace(",","\\,");
}
