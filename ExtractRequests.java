private static double resolveNumericValue(
        FsmlModel.Variable var,
        String value,
        String operator) {

    if (value == null || value.trim().isEmpty()) {
        return var.min;
    }

    if ("LOW".equals(value)) {
        return var.min;
    }

    if ("HIGH".equals(value)) {
        return var.max;
    }

    return Double.parseDouble(value);
}
