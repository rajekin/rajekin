<!-- One element -> "name": value -->
<xsl:template match="*">
    <!-- property name -->
    <xsl:text>"</xsl:text>
    <xsl:value-of select="name()"/>
    <xsl:text>": </xsl:text>

    <xsl:choose>
        <!-- Non-leaf (has children or attributes) – keep your existing object/array logic here -->
        <xsl:when test="* or @*">
            <!-- keep whatever you already had for complex elements -->
            <xsl:call-template name="process-children"/>
        </xsl:when>

        <!-- Leaf element: no children, no attributes -->
        <xsl:otherwise>
            <xsl:choose>
                <!-- *** THIS IS THE IMPORTANT PART *** -->
                <!-- If the element is empty or only whitespace -> JSON null -->
                <xsl:when test="normalize-space() = ''">
                    <xsl:text>null</xsl:text>
                </xsl:when>

                <!-- Otherwise, normal string value -->
                <xsl:otherwise>
                    <xsl:text>"</xsl:text>
                    <xsl:value-of select="normalize-space()"/>
                    <xsl:text>"</xsl:text>
                </xsl:otherwise>
            </xsl:choose>
        </xsl:otherwise>
    </xsl:choose>
</xsl:template>
