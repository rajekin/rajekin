<xsl:template match="*[not(*) and not(@*) and not(normalize-space())]">
  <xsl:text>null</xsl:text>
</xsl:template>
