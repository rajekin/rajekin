// If we see CDATA, peel out the inner XML to avoid returning it as a string
    int cdataStart = xml.indexOf("<![CDATA[");
    int cdataEnd   = xml.indexOf("]]>", cdataStart + 9);
    if (cdataStart >= 0 && cdataEnd > cdataStart) {
      xml = xml.substring(cdataStart + 9, cdataEnd).trim();
      // we already extracted the payload; no SOAP unwrapping needed now
      unwrapSoapBody = false;
    }

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
