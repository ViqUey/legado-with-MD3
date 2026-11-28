package io.legado.app.lib.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class EpubExportIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verifiedExportPreservesSpineTextIllustrationAndCover() {
        val file = temporary.newFile("recovered.epub")
        val expected = writeFixture(file)
        EpubExportIntegrity.verify(file, expected)
        assertEquals(listOf("EPUB/chapter-1.xhtml", "EPUB/chapter-2.xhtml"), inspectGeneratedEpub(file).spine)
        ZipFile(file).use { zip ->
            val chapter = zip.getInputStream(zip.getEntry("EPUB/chapter-1.xhtml")).reader().readText()
            assertTrue("&lt;任意 &amp; 标题&gt;" in chapter)
            assertTrue("正文 &lt;foo&gt; &amp; 原样" in chapter)
            assertTrue(zip.getEntry("EPUB/cover.png").size > 0)
        }
        System.getenv("LEGADO_EPUBCHECK_FIXTURES")?.let {
            file.copyTo(File(it, "verified-text-cover-illustration.epub"), overwrite = true)
        }
    }

    @Test fun rejectsArchiveWithSilentlyMissingImageEvenThoughZipOpens() {
        val file = temporary.newFile("original.epub")
        val expected = writeFixture(file)
        val broken = temporary.newFile("missing-image.epub")
        rewrite(file, broken) { name, bytes -> if (name == "EPUB/image-2.png") null else bytes }
        assertThrows(IOException::class.java) { EpubExportIntegrity.verify(broken, expected) }
    }

    @Test fun validXmlWithIncompleteTextFailsTheContentCheck() {
        val file = temporary.newFile("original.epub")
        val expected = writeFixture(file)
        val broken = temporary.newFile("truncated-chapter.epub")
        rewrite(file, broken) { name, bytes ->
            if (name == "EPUB/chapter-1.xhtml") bytes.decodeToString().replace("正文", "").toByteArray() else bytes
        }
        assertEquals(expected.spine, inspectGeneratedEpub(broken).spine)
        assertThrows(IOException::class.java) { EpubExportIntegrity.verify(broken, expected) }
    }

    @Test fun validZipAndXmlCannotSilentlyChangeMetadataOrNavigation() {
        val file = temporary.newFile("original.epub")
        val expected = writeFixture(file)
        val generatedNames = setOf(
            "mimetype", "META-INF/container.xml", "EPUB/book.opf", "EPUB/nav.xhtml", "EPUB/cover.png",
            "EPUB/chapter-1.xhtml", "EPUB/chapter-2.xhtml", "EPUB/image-2.png",
        )
        assertEquals(generatedNames, expected.contentDigests.keys)
        val changes = listOf(
            "EPUB/book.opf" to "书名 &amp; &lt;书&gt;",
            "EPUB/book.opf" to "作者 &amp; 朋友",
            "EPUB/book.opf" to "简介 &lt;安全&gt;",
            "EPUB/nav.xhtml" to "&lt;任意 &amp; 标题&gt;",
        )
        for ((index, change) in changes.withIndex()) {
            val (changedEntry, original) = change
            val changed = temporary.newFile("altered-$index.epub")
            rewrite(file, changed) { name, bytes ->
                if (name != changedEntry) bytes else bytes.decodeToString()
                    .also { assertTrue(original in it) }.replace(original, "另一文本").toByteArray()
            }
            assertEquals(expected.spine, inspectGeneratedEpub(changed).spine)
            assertEquals("Content changed: $changedEntry",
                assertThrows(IOException::class.java) { EpubExportIntegrity.verify(changed, expected) }.message)
        }
    }

    @Test fun matchingDigestsDoNotBypassRequiredPackageStructure() {
        val original = temporary.newFile("original.epub")
        val expected = writeFixture(original)
        val opf = "EPUB/book.opf"
        val nav = "EPUB/nav.xhtml"
        val cases = listOf(
            Triple(opf, "idref=\"c2\"", "idref=\"missing\"") to "Invalid spine item",
            Triple(opf, "href=\"image-2.png\"", "href=\"absent.png\"") to "Missing manifest resource: EPUB/absent.png",
            Triple(nav, "href=\"chapter-2.xhtml\"", "href=\"chapter-1.xhtml\"") to "Navigation omits or reorders spine chapters",
            Triple(nav, "<html ", "<!DOCTYPE html SYSTEM \"file:///forbidden\"><html ") to "DTD is outside the export profile",
        )
        cases.forEachIndexed { i, (change, message) ->
            val (entry, old, new) = change
            val edited = temporary.newFile("structural-$i.epub")
            rewrite(original, edited) { name, bytes ->
                if (name == entry) bytes.decodeToString().also { assertTrue(old in it) }.replace(old, new).toByteArray()
                else bytes
            }
            val matching = expected.copy(contentDigests = fileDigests(edited))
            assertEquals(message, assertThrows(IOException::class.java) { EpubExportIntegrity.verify(edited, matching) }.message)
        }
    }

    @Test fun comparingSpineWithTheConfirmedOrderIsIndependentOfArchiveDigests() {
        val original = temporary.newFile("in-user-order.epub")
        val expected = writeFixture(original)
        val differentOrder = expected.copy(spine = expected.spine.reversed())
        assertEquals("Chapter order or count changed",
            assertThrows(IOException::class.java) { EpubExportIntegrity.verify(original, differentOrder) }.message)
    }

    @Test fun missingChapterTruncatedZipAndDuplicateEntriesAreRejected() {
        val original = temporary.newFile("original.epub")
        val expected = writeFixture(original)
        val missing = temporary.newFile("no-chapter.epub")
        rewrite(original, missing) { name, bytes -> bytes.takeUnless { name == "EPUB/chapter-2.xhtml" } }
        assertEquals("Generated files changed",
            assertThrows(IOException::class.java) { EpubExportIntegrity.verify(missing, expected) }.message)
        val archived = original.readBytes()
        val truncated = temporary.newFile("truncated-zip.epub").apply { writeBytes(archived.copyOf(archived.size - 12)) }
        assertThrows(IOException::class.java) { EpubExportIntegrity.verify(truncated, expected) }
        val duplicate = temporary.newFile("duplicate.epub")
        val from = "EPUB/chapter-2.xhtml".toByteArray()
        val to = "EPUB/chapter-1.xhtml".toByteArray()
        val patched = archived.copyOf()
        var replacements = 0
        for (i in 0..patched.size - from.size) {
            if (from.indices.all { j -> patched[i + j] == from[j] }) {
                to.copyInto(patched, i)
                replacements++
            }
        }
        assertEquals(2, replacements) // local and central filename
        duplicate.writeBytes(patched)
        assertEquals("Duplicate ZIP entry",
            assertThrows(IOException::class.java) { EpubExportIntegrity.verify(duplicate, expected) }.message)
    }

    @Test fun corruptCentralCrcIsRejectedEvenWithTheOriginalDigest() {
        val original = temporary.newFile("original.epub")
        val expected = writeFixture(original)
        val name = "EPUB/chapter-2.xhtml"
        val damaged = temporary.newFile("crc.epub")
        val bytes = original.readBytes()
        val zip = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eocdOffset = bytes.size - 22
        assertEquals(0x06054b50, zip.getInt(eocdOffset))
        var offset = zip.getInt(eocdOffset + 16)
        while (true) {
            assertEquals(0x02014b50, zip.getInt(offset))
            val nameLength = zip.getShort(offset + 28).toInt() and 0xffff
            if (String(bytes, offset + 46, nameLength, Charsets.UTF_8) == name) {
                val crcOffset = offset + 16
                zip.putInt(crcOffset, zip.getInt(crcOffset) xor 1)
                break
            }
            val extraLength = zip.getShort(offset + 30).toInt() and 0xffff
            val commentLength = zip.getShort(offset + 32).toInt() and 0xffff
            offset += 46 + nameLength + extraLength + commentLength
        }
        damaged.writeBytes(bytes)
        assertEquals(expected.contentDigests, fileDigests(damaged))
        assertEquals("Invalid ZIP CRC or length: $name",
            assertThrows(IOException::class.java) { EpubExportIntegrity.verify(damaged, expected) }.message)
    }

    @Test fun imageAndCoverSubstitutionsAreDetectedAsByteMismatches() {
        val original = temporary.newFile("original.epub")
        val expected = writeFixture(original)
        for (name in listOf("EPUB/cover.png", "EPUB/image-2.png")) {
            val edited = temporary.newFile(name.substringAfterLast('/'))
            rewrite(original, edited) { entry, bytes -> if (entry == name) png(0x0077cc) else bytes }
            assertEquals("Content changed: $name",
                assertThrows(IOException::class.java) { EpubExportIntegrity.verify(edited, expected) }.message)
        }
    }

    @Test fun detailedChapterChecksCatchGeneratorErrorsIndependentlyOfDigests() {
        val file = temporary.newFile("broken-reference.epub")
        val expected = TextEpubExport("Book", "", null) {}.write(file) {
            chapter("Section", """<p>Body</p><img src="absent.png" alt=""/>""")
        }
        EpubExportIntegrity.verify(file, expected)
        assertEquals("Unresolved EPUB resource: absent.png",
            assertThrows(IOException::class.java) { inspectGeneratedEpub(file) }.message)
    }

    @Test fun runtimeAllowsChaptersLargerThanTheMetadataParsingLimit() {
        val file = temporary.newFile("large-chapter.epub")
        val expected = TextEpubExport("Book", "", null) {}.write(file) {
            chapter("Long chapter", "<p>${"a".repeat(32 * 1024 * 1024)}</p>")
        }
        EpubExportIntegrity.verify(file, expected)
        assertEquals("XML entry exceeds the export profile",
            assertThrows(IOException::class.java) { inspectGeneratedEpub(file) }.message)
    }

    private fun fileDigests(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().toList().associate { entry ->
            entry.name to zip.getInputStream(entry).use {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toHexString()
            }
        }
    }

    private fun writeFixture(file: File): EpubExportIntegrity.Expected {
        val image = TextEpubExport.Image(png(0), "png", "image/png")
        return TextEpubExport("书名 & <书>", "作者 & 朋友", "简介 <安全>") {}.write(file) {
            cover(image)
            val ref = image(image)
            chapter("<任意 & 标题>", "<p>${TextEpubExport.xml("正文 <foo> & 原样")}</p><img src=\"$ref\" alt=\"\"/>")
            chapter("第二章", "<p>短</p>")
        }
    }

    private fun png(rgb: Int): ByteArray = ByteArrayOutputStream().use {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, rgb) }
        ImageIO.write(image, "png", it)
        it.toByteArray()
    }

    private fun rewrite(input: File, output: File, change: (String, ByteArray) -> ByteArray?) {
        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream()).use { result ->
                for (entry in zip.entries()) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val updated = change(entry.name, bytes) ?: continue
                    result.putNextEntry(ZipEntry(entry.name).apply {
                        if (name == "mimetype") {
                            method = ZipEntry.STORED
                            size = updated.size.toLong()
                            crc = CRC32().apply { update(updated) }.value
                        }
                    })
                    result.write(updated)
                    result.closeEntry()
                }
            }
        }
    }
}
