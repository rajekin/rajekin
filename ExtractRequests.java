import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SoapCDataWrapper {

    // Builds a SOAP 1.1 envelope with <inv:inputXml><![CDATA[...]]></inv:inputXml>
    // Namespace/prefix match the screenshot.
    public static String buildSoapBodyForInvoker(String rawXml) {
        // CDATA cannot contain "]]>" — split it safely if it appears
        String safe = rawXml.replace("]]>", "]]]]><![CDATA[>");
        return ""
            + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:inv=\"http://invoker.ps.eos.fairisaac.com\">"
            + "<soapenv:Header/>"
            + "<soapenv:Body>"
            + "<inv:inputXml><![CDATA[" + safe + "]]></inv:inputXml>"
            + "</soapenv:Body>"
            + "</soapenv:Envelope>";
    }

    // Example: read your XML from a file and wrap it
    public static void main(String[] args) throws Exception {
        String xml = Files.readString(Path.of("your-input.xml"), StandardCharsets.UTF_8);
        String soap = buildSoapBodyForInvoker(xml);
        System.out.println(soap); // send this as the POST body
    }
}
