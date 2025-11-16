<!-- Remove ANY empty element before JSON conversion -->
<xsl:template match="[not() and not(@*) and not(normalize-space())]" />
