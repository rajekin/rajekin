

static Map<String,Object> readNameRealValueMap(ObjectMapper mapper, File jsonFile) throws Exception {
    JsonNode root = mapper.readTree(jsonFile);
    JsonNode params = root.at("/pmmlmodelServicePayload/request/modelInput/parameters");

    Map<String,Object> out = new LinkedHashMap<>();
    if (params != null && params.isArray()) {
      for (JsonNode p : params) {
        String key = p.path("name").asText(null);
        JsonNode v = p.get("realValue");  // adjust if you also have stringValue/intValue
        Object val = (v == null || v.isNull()) ? null
                    : v.isNumber() ? v.numberValue()
                    : v.isTextual() ? v.textValue()
                    : mapper.convertValue(v, Object.class);
        if (key != null) out.put(key, val);
      }
    }
    return out;
  }
}
