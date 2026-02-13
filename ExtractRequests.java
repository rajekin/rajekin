import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;

public class XsdXPathPrinter {

    public static void main(String[] args) throws Exception {

        File file = new File("schema.xsd"); // change path if needed

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(file);
        doc.getDocumentElement().normalize();

        NodeList elements = doc.getElementsByTagNameNS("*", "element");

        for (int i = 0; i < elements.getLength(); i++) {
            Element el = (Element) elements.item(i);

            // Only process top-level elements
            if (el.getParentNode().getLocalName().equals("schema")) {
                processElement(el, "");
            }
        }
    }

    private static void processElement(Element element, String parentPath) {

        String name = element.getAttribute("name");
        if (name == null || name.isEmpty()) return;

        String currentPath = parentPath + "/" + name;

        // Process inline complexType
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {

            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element child = (Element) node;

            if ("complexType".equals(child.getLocalName())) {
                processComplexType(child, currentPath);
            }
        }

        // If element references type
        if (element.hasAttribute("type")) {
            String typeName = element.getAttribute("type");
            typeName = stripNamespace(typeName);

            NodeList complexTypes = element.getOwnerDocument()
                    .getElementsByTagNameNS("*", "complexType");

            for (int i = 0; i < complexTypes.getLength(); i++) {
                Element ct = (Element) complexTypes.item(i);
                if (typeName.equals(ct.getAttribute("name"))) {
                    processComplexType(ct, currentPath);
                }
            }
        }
    }

    private static void processComplexType(Element complexType, String parentPath) {

        NodeList children = complexType.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {

            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element el = (Element) node;

            String local = el.getLocalName();

            if ("sequence".equals(local) ||
                "choice".equals(local) ||
                "all".equals(local)) {

                processContainer(el, parentPath);
            }

            if ("attribute".equals(local)) {
                String attrName = el.getAttribute("name");
                if (!attrName.isEmpty()) {
                    System.out.println(parentPath + "/@" + attrName);
                }
            }
        }
    }

    private static void processContainer(Element container, String parentPath) {

        NodeList children = container.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {

            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element el = (Element) node;

            if ("element".equals(el.getLocalName())) {
                processElement(el, parentPath);
            }
        }
    }

    private static String stripNamespace(String value) {
        if (value == null) return null;
        int index = value.indexOf(":");
        return index >= 0 ? value.substring(index + 1) : value;
    }
}
