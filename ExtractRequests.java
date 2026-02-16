<xsl:template match="
  /DmInternalVars/application/CreditApplication/PersonalApplicant/
  SentiLinkResponse[
    not(normalize-space(SentiLinkScoresResponse))
  ]
"/>
