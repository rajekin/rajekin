if (left.isArray()) {
    diffArrayUnordered(path,
        (com.fasterxml.jackson.databind.node.ArrayNode) left,
        (com.fasterxml.jackson.databind.node.ArrayNode) right,
        out);
    return;
}


/** Order-insensitive, keyless array compare using canonical content signatures. */
private static void diffArrayUnordered(JsonPointer path,
                                       com.fasterxml.jackson.databind.node.ArrayNode leftArr,
                                       com.fasterxml.jackson.databind.node.ArrayNode rightArr,
                                       List<Diff> out) {

    // Fast path: both empty
    if (leftArr.isEmpty() && rightArr.isEmpty()) return;

    // Group items by canonical signature (objects: fields sorted; arrays: children signatures sorted)
    Map<String, Deque<JsonNode>> L = bagBySignature(leftArr);
    Map<String, Deque<JsonNode>> R = bagBySignature(rightArr);

    // Compare multiset of signatures
    Set<String> sigs = new TreeSet<>();
    sigs.addAll(L.keySet());
    sigs.addAll(R.keySet());

    for (String sig : sigs) {
        Deque<JsonNode> ld = L.getOrDefault(sig, new ArrayDeque<>());
        Deque<JsonNode> rd = R.getOrDefault(sig, new ArrayDeque<>());

        // Pair as many as possible and recurse to surface inner differences
        int matches = Math.min(ld.size(), rd.size());
        for (int i = 0; i < matches; i++) {
            JsonNode lItem = ld.pollFirst();
            JsonNode rItem = rd.pollFirst();
            diffJson(path, lItem, rItem, out);  // keep same base path; report shows values
        }

        // Leftovers = missing on the other side
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

private static Map<String, Deque<JsonNode>> bagBySignature(com.fasterxml.jackson.databind.node.ArrayNode arr) {
    Map<String, Deque<JsonNode>> m = new HashMap<>();
    for (JsonNode n : arr) {
        String sig = canonicalSignature(n);
        m.computeIfAbsent(sig, k -> new ArrayDeque<>()).add(n);
    }
    return m;
}

/** Deterministic, keyless, order-free signature. */
private static String canonicalSignature(JsonNode n) {
    if (n == null || n.isNull()) return "null";
    if (n.isValueNode()) {
        if (n.isNumber())
            return "num:" + new java.math.BigDecimal(n.asText()).stripTrailingZeros().toPlainString();
        if (n.isBoolean()) return "bool:" + n.asText();
        return "str:" + n.asText();
    }
    if (n.isObject()) {
        // sort fields by name, then sign each value
        java.util.SortedMap<String,String> parts = new java.util.TreeMap<>();
        n.fieldNames().forEachRemaining(fn -> parts.put(fn, canonicalSignature(n.get(fn))));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String,String> e : parts.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(escapeSig(e.getKey())).append(":").append(e.getValue());
        }
        return sb.append("}").toString();
    }
    // array: compute child signatures, then sort them so order doesn't matter
    java.util.List<String> children = new java.util.ArrayList<>();
    for (JsonNode c : n) children.add(canonicalSignature(c));
    java.util.Collections.sort(children);
    return "[" + String.join(",", children) + "]";
}

private static String escapeSig(String s) {
    return s.replace("\\","\\\\").replace(":","\\:").replace(",","\\,");
}
