public static void main(String[] args) throws Exception {

    File xsdFile = new File("schema.xsd");

    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(true);

    XSOMParser parser = new XSOMParser(factory);
    parser.parse(xsdFile);

    XSSchemaSet schemaSet = parser.getResult();

    for (XSSchema schema : schemaSet.getSchemas()) {
        for (XSElementDecl element : schema.getElementDecls().values()) {
            processElement(element, "");
        }
    }
}
