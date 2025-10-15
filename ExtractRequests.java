@RestController
@RequestMapping("/api/xml-to-json")
public class XmlToJsonApi {

  private final XmlToJsonService service = new XmlToJsonService();

  @PostMapping(
      consumes = { MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE },
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public Map<String, Object> convert(
      @RequestBody byte[] body,
      @RequestParam(name = "unwrapSoapBody",   required = false, defaultValue = "true")  boolean unwrapSoapBody,
      @RequestParam(name = "parseCdataXml",    required = false, defaultValue = "true")  boolean parseCdataXml,
      @RequestParam(name = "flattenAttributes",required = false, defaultValue = "true")  boolean flattenAttributes,
      @RequestParam(name = "coerceNumbers",    required = false, defaultValue = "true")  boolean coerceNumbers
  ) throws Exception {

    String xml = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
    var res = service.convert(xml, unwrapSoapBody, parseCdataXml, flattenAttributes, coerceNumbers);

    return Map.of(
        "json", res.prettyJson,
        "xmlAttributeCount", res.xmlAttributeCount,
        "jsonAttributeCount", res.jsonAttributeCount,
        "allAttributesConverted", res.allAttributesConverted,
        "missingAttributes", res.missingAttributes
    );
  }
}
