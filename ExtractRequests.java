opt.useXslt = true;                                     // turn it on
opt.xsltLocation = xsltPath;                            // e.g. "classpath:xsl/xml-to-json.xsl" or "C:\\x\\xml-to-json.xsl"
opt.xsltOutputIsJson = true;                            // make sure the transform emits JSON
opt.xsltParams = xsltParams;                            // keep your params



XmlToJsonUnifiedService.Options saveOpt = new XmlToJsonUnifiedService.Options();
saveOpt.unwrapSoapBody = opt.unwrapSoapBody;
saveOpt.parseCdataXml  = opt.parseCdataXml;
saveOpt.lowercaseRoot  = opt.lowercaseRoot;
saveOpt.flattenAttributes = opt.flattenAttributes;
saveOpt.coerceNumbers  = opt.coerceNumbers;
saveOpt.useXslt = true;                                 // force XSLT for saved files
saveOpt.xsltLocation = opt.xsltLocation;
saveOpt.xsltOutputIsJson = true;
saveOpt.xsltParams = opt.xsltParams;

Utils.runFiles(converter, saveOpt);
