import com.sun.xml.xsom.*;
import com.sun.xml.xsom.parser.XSOMParser;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class XsdXpathExtractor {

    public static class Xpath {
        private String objectName;
        private String xpath;

        public Xpath(String objectName, String xpath) {
            this.objectName = objectName;
            this.xpath = xpath;
        }

        @Override
        public String toString() {
            return objectName + " -> " + xpath;
        }
    }

    private static Set<String> visitedTypes = new HashSet<>();

    public static void main(String[] args) throws Exception {

        File xsdFile = new File("schema.xsd");

        XSOMParser parser = new XSOMParser();
        parser.parse(xsdFile);

        XSSchemaSet schemaSet = parser.getResult();

        for (XSSchema schema : schemaSet.getSchemas()) {
            for (XSElementDecl element : schema.getElementDecls().values()) {
                processElement(element, "");
            }
        }
    }

    private static void processElement(XSElementDecl element, String parentPath) {

        String currentPath = parentPath + "/" + element.getName();

        XSType type = element.getType();

        if (type.isComplexType()) {
            processComplexType(type.asComplexType(), currentPath);
        }
    }

    private static void processComplexType(XSComplexType complexType, String parentPath) {

        if (visitedTypes.contains(complexType.getName())) return;
        visitedTypes.add(complexType.getName());

        // Handle attributes
        for (XSAttributeUse attrUse : complexType.getAttributeUses()) {
            XSAttributeDecl attr = attrUse.getDecl();
            System.out.println(new Xpath(attr.getName(), parentPath + "/@" + attr.getName()));
        }

        // Handle content model (sequence, choice, all)
        XSParticle particle = complexType.getContentType().asParticle();
        if (particle != null) {
            processParticle(particle, parentPath);
        }

        // Handle extension / inheritance
        XSType baseType = complexType.getBaseType();
        if (baseType != null && baseType.isComplexType()) {
            processComplexType(baseType.asComplexType(), parentPath);
        }
    }

    private static void processParticle(XSParticle particle, String parentPath) {

        XSTerm term = particle.getTerm();

        if (term.isElementDecl()) {
            XSElementDecl child = term.asElementDecl();
            processElement(child, parentPath);
        }

        else if (term.isModelGroup()) {
            XSModelGroup group = term.asModelGroup();
            for (XSParticle childParticle : group.getChildren()) {
                processParticle(childParticle, parentPath);
            }
        }
    }
}
