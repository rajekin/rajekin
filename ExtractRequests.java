<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:id="http://idanalytics.com/products/idscore/result"
    exclude-result-prefixes="id">

    <xsl:output method="xml" indent="yes"/>

    <!-- Root -->
    <xsl:template match="/">
        <IDAOLNAttributesList>
            <!-- Iterate through each Item -->
            <xsl:for-each select="//Item">
                <IDAOLNAttributes>

                    <!-- Group name -->
                    <xsl:attribute name="GroupName">
                        <xsl:value-of select="id:OutputRecord/id:Indicators/id:Group/@name"/>
                    </xsl:attribute>

                    <!-- Iterate through Indicators -->
                    <xsl:for-each select="id:OutputRecord/id:Indicators/id:Group/id:Indicator">
                        <xsl:variable name="attribName" select="@name"/>
                        <xsl:attribute name="{$attribName}">
                            <xsl:value-of select="normalize-space(.)"/>
                        </xsl:attribute>
                    </xsl:for-each>

                </IDAOLNAttributes>
            </xsl:for-each>
        </IDAOLNAttributesList>
    </xsl:template>

</xsl:stylesheet>






    import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.File;

public class XsltTest {

    public static void main(String[] args) {
        try {
            // Input XML
            File xmlFile = new File("input.xml");

            // XSLT
            File xsltFile = new File("transform.xslt");

            // Output (optional file)
            File outputFile = new File("output.xml");

            TransformerFactory factory = TransformerFactory.newInstance();

            // IMPORTANT for some IBM / Saxon environments
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, false);

            Transformer transformer = factory.newTransformer(new StreamSource(xsltFile));

            // Pretty print
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            // Run transform
            transformer.transform(
                    new StreamSource(xmlFile),
                    new StreamResult(outputFile)
            );

            System.out.println("XSLT Transformation successful.");
            System.out.println("Output written to: " + outputFile.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
