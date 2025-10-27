Path dir = Paths.get("inputs"); // folder with *.json
try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
  for (Path p : ds) {
    Map<String,Object> m = mapper.readValue(p.toFile(), new TypeReference<Map<String,Object>>() {});
    inputMap.putAll(m); // later files overwrite duplicate keys
  }
}
