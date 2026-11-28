package io.legado.app.lib.epub

import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Detailed checks of the Writer's XHTML profile, independent of its expected byte digests. */
internal fun inspectGeneratedEpub(file: File): EpubExportIntegrity.Structure = ZipFile(file).use { zip ->
    val structure = EpubExportIntegrity.readPackage(zip)
    for (path in structure.manifest.filter { it.endsWith(".xhtml") }) {
        val document = EpubExportIntegrity.readXml(zip, path)
        if (document.localName != "html" || document.namespaceURI != "http://www.w3.org/1999/xhtml") {
            throw IOException("Invalid generated XHTML: $path")
        }
        val elements = document.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            for (attribute in listOf("href", "src", "xlink:href")) {
                if (!element.hasAttribute(attribute)) continue
                val reference = element.getAttribute(attribute)
                if (EpubExportIntegrity.resolve(path, reference) !in structure.manifest) {
                    throw IOException("Unresolved EPUB resource: $reference")
                }
            }
        }
    }
    structure
}
