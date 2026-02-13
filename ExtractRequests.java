import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class XsdXPathPrinter {

    private static Map<String, Element> complexTypeMap = new HashMap<>();
    private static Set<String> visitedTypes = new HashSet<>();

    public static void main(String[] args) throws Exception {

        File file = new File("schema.xsd");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(file);
        doc.getDocumentElement().normalize();

        // Collect all complexTypes
        NodeList complexTypes = doc.getElementsByTagNameNS("*", "complexType");
        for (int i = 0; i < complexTypes.getLength(); i++) {
            Element ct = (Element) complexTypes.item(i);
            if (ct.hasAttribute("name")) {
                complexTypeMap.put(ct.getAttribute("name"), ct);
            }
        }

        // Process global elements first
        NodeList elements = doc.getElementsByTagNameNS("*", "element");
        boolean hasGlobalElements = false;

        for (int i = 0; i < elements.getLength(); i++) {
            Element el = (Element) elements.item(i);

            if (el.getParentNode().getLocalName().equals("schema")) {
                hasGlobalElements = true;
                processElement(el, "");
            }
        }

        // If no global elements exist, treat complexTypes as root
        if (!hasGlobalElements) {
            for (String typeName : complexTypeMap.keySet()) {
                visitedTypes.clear();
                processComplexType(complexTypeMap.get(typeName), "/" + typeName);
            }
        }
    }

    private static void processElement(Element element, String parentPath) {

        String name = element.getAttribute("name");
        if (name == null || name.isEmpty()) return;

        String currentPath = parentPath + "/" + name;
        System.out.println(currentPath);

        // Inline complexType
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {

            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element child = (Element) node;

            if ("complexType".equals(child.getLocalName())) {
                processComplexType(child, currentPath);
            }
        }

        // Referenced type
        if (element.hasAttribute("type")) {
            String typeName = stripNamespace(element.getAttribute("type"));
            Element referenced = complexTypeMap.get(typeName);

            if (referenced != null) {
                processComplexType(referenced, currentPath);
            }
        }
    }

    private static void processComplexType(Element complexType, String parentPath) {

        if (complexType == null) return;

        String typeName = complexType.getAttribute("name");
        if (!typeName.isEmpty()) {
            if (visitedTypes.contains(typeName)) return;
            visitedTypes.add(typeName);
        }

        NodeList children = complexType.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {

            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element el = (Element) node;
            String local = el.getLocalName();

            switch (local) {

                case "sequence":
                case "choice":
                case "all":
                    processContainer(el, parentPath);
                    break;

                case "attribute":
                    String attrName = el.getAttribute("name");
                    if (!attrName.isEmpty()) {
                        System.out.println(parentPath + "/@" + attrName);
                    }
                    break;

                case "complexContent":
                    processComplexContent(el, parentPath);
                    break;

                case "simpleContent":
                    processSimpleContent(el, parentPath);
                    break;
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

    private static void processComplexContent(Element complexContent, String parentPath) {

        NodeList children = complexContent.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {

            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element el = (Element) node;

            if ("extension".equals(el.getLocalName())) {

                String baseType = stripNamespace(el.getAttribute("base"));
                Element base = complexTypeMap.get(baseType);

                if (base != null) {
                    processComplexType(base, parentPath);
                }

                processComplexType(el, parentPath);
            }
        }
    }

    private static void processSimpleContent(Element simpleContent, String parentPath) {

        NodeList children = simpleContent.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {

            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element el = (Element) node;

            if ("extension".equals(el.getLocalName())) {

                NodeList attrs = el.getChildNodes();
                for (int j = 0; j < attrs.getLength(); j++) {
                    Node attrNode = attrs.item(j);
                    if (attrNode.getNodeType() != Node.ELEMENT_NODE) continue;

                    Element attrEl = (Element) attrNode;

                    if ("attribute".equals(attrEl.getLocalName())) {
                        String attrName = attrEl.getAttribute("name");
                        System.out.println(parentPath + "/@" + attrName);
                    }
                }
            }
        }
    }

    private static String stripNamespace(String value) {
        if (value == null) return null;
        int index = value.indexOf(":");
        return index >= 0 ? value.substring(index + 1) : value;
    }
}
