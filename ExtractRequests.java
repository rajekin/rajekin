<xsl:when test="not(*) and not(@*)">
    <xsl:choose>
        <!-- If the element is empty or only whitespace -> JSON null -->
        <xsl:when test="normalize-space() = ''">
            <xsl:text>null</xsl:text>
        </xsl:when>

        <!-- Otherwise output the string value as before -->
        <xsl:otherwise>
            <xsl:text>"</xsl:text>
            <xsl:value-of select="normalize-space()"/>
            <xsl:text>"</xsl:text>
        </xsl:otherwise>
    </xsl:choose>
</xsl:when>
