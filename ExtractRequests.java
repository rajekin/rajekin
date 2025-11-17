<xsl:template match="*">
    <!-- property name -->
    <xsl:text>"</xsl:text>
    <xsl:value-of select="name()"/>
    <xsl:text>": </xsl:text>

    <xsl:choose>
        <!-- EMPTY ELEMENT: no children, no attributes, no text -> JSON null -->
        <xsl:when test="not(*) and not(@*) and normalize-space() = ''">
            <xsl:text>null</xsl:text>
        </xsl:when>

        <!-- NON-EMPTY: use your existing logic -->
        <xsl:otherwise>
            <xsl:call-template name="process-children">
                <xsl:with-param name="nodes" select="*"/>
            </xsl:call-template>
        </xsl:otherwise>
    </xsl:choose>
</xsl:template>
