package com.raj.utilities.web;

import com.raj.utilities.service.XmlToJsonService;
import com.raj.utilities.service.XmlToJsonService.ConversionResult;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Controller
public class XmlToJsonController {

    private final XmlToJsonService service = new XmlToJsonService();

    @GetMapping("/xml-to-json")
    public String page() { return "xml-to-json"; }

    @PostMapping(path = "/api/xml-to-json", consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> convert(@RequestBody String xml,
                                       @RequestParam(defaultValue = "true") boolean unwrapSoapBody,
                                       @RequestParam(defaultValue = "true") boolean parseCdataXml,
                                       @RequestParam(defaultValue = "true") boolean flattenAttributes,
                                       @RequestParam(defaultValue = "true") boolean coerceNumbers) throws Exception {
        ConversionResult res = service.convert(xml, unwrapSoapBody, parseCdataXml, flattenAttributes, coerceNumbers);
        return Map.of(
                "json", res.prettyJson,
                "xmlAttributeCount", res.xmlAttributeCount,
                "jsonAttributeCount", res.jsonAttributeCount,
                "allAttributesConverted", res.allAttributesConverted,
                "missingAttributes", res.missingAttributes
        );
    }
}
