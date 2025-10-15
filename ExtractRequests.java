private static void putValue(com.fasterxml.jackson.databind.node.ObjectNode node,
                             String key, String v, boolean coerceNumbers) {
    if (!coerceNumbers) { node.put(key, v); return; }

    String t = (v == null) ? "" : v.trim();
    if (t.isEmpty()) { node.put(key, ""); return; }

    // 1) Strict canonical number: [-+]? (digits | digits.digits | .digits) (exp)?
    if (isCanonicalNumber(t)) {
        if (writeNumeric(node, key, t)) return;  // parsed OK
    }

    // 2) Relaxed: strip thousands separators, $, trailing %
    String relaxed = t.replace(",", "");
    if (relaxed.startsWith("$")) relaxed = relaxed.substring(1);
    if (relaxed.endsWith("%"))  relaxed = relaxed.substring(0, relaxed.length() - 1).trim();

    if (isCanonicalNumber(relaxed)) {
        if (writeNumeric(node, key, relaxed)) return; // parsed OK after cleaning
    }

    // 3) Fallback: keep as string
    node.put(key, v);
}

private static boolean isCanonicalNumber(String s) {
    // Accepts: 123  123.45  .45  -1.2e3  +10E-2
    return s.matches("^[+-]?(?:\\d+\\.\\d+|\\d+|\\.\\d+)(?:[eE][+-]?\\d+)?$");
}

private static boolean writeNumeric(com.fasterxml.jackson.databind.node.ObjectNode node,
                                    String key, String num) {
    try {
        if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
            // integer-ish → prefer long; if it overflows, fall through to BigDecimal
            long lv = Long.parseLong(num);
            node.put(key, lv);
        } else {
            node.put(key, new java.math.BigDecimal(num));
        }
        return true;
    } catch (Exception ignore) {
        try {
            // huge integer or corner cases → BigDecimal again
            node.put(key, new java.math.BigDecimal(num));
            return true;
        } catch (Exception ignore2) {
            return false;
        }
    }
}
