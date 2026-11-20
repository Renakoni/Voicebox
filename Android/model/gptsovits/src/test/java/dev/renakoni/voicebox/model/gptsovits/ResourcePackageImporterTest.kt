package dev.renakoni.voicebox.model.gptsovits

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResourcePackageImporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun installsAndReplacesOnlyTheRuntimeDirectory() {
        val root = temporary.newFolder()
        val models = root.resolve("CharacterModels").apply { mkdir() }.resolve("keep").apply { writeText("voice") }
        val old = root.resolve("RuntimeAssets").apply { mkdir() }.resolve("old").apply { writeText("old") }
        ResourcePackageImporter(root).importRuntime(zipOf(runtimeEntries()))
        assertFalse(old.exists())
        assertEquals("voice", models.readText())
        runtimeEntries().forEach { (path, content) -> assertEquals(content, root.resolve(path).readText()) }
        assertFalse(root.listFiles()!!.any { it.name.startsWith(".") })
    }

    @Test fun rejectsIncompleteAndEscapingPackagesWithoutChangingTheInstalledRuntime() {
        val root = temporary.newFolder()
        val existing = root.resolve("RuntimeAssets").apply { mkdir() }.resolve("keep").apply { writeText("previous") }
        val importer = ResourcePackageImporter(root)
        for (entries in listOf(
            listOf("RuntimeAssets/../outside" to "bad"),
            listOf("/RuntimeAssets/outside" to "bad"),
            listOf("CharacterModels/model.onnx" to "bad"),
            runtimeEntries().dropLast(1),
            runtimeEntries().drop(1),
            runtimeEntries() + ("RuntimeAssets/language_frontends/english/external/encoder.onnx" to "missing weights"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { importer.importRuntime(zipOf(entries)) }
            assertEquals("previous", existing.readText())
            assertEquals(listOf("RuntimeAssets"), root.list()!!.toList())
        }
    }

    @Test fun cancelledExtractionKeepsTheInstalledRuntimeAndRemovesStagingFiles() {
        val root = temporary.newFolder()
        val existing = root.resolve("RuntimeAssets").apply { mkdir() }.resolve("keep").apply { writeText("previous") }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            ResourcePackageImporter(root).importRuntime(zipOf(runtimeEntries())) {
                if (++checks == 4) throw CancellationException()
            }
        }
        assertEquals("previous", existing.readText())
        assertEquals(listOf("RuntimeAssets"), root.list()!!.toList())
    }

    private fun runtimeEntries(): List<Pair<String, String>> = listOf(
        "RuntimeAssets/language_frontends/english/encoder.onnx" to "graph",
        "RuntimeAssets/language_frontends/english/decoder.onnx" to "graph",
        "RuntimeAssets/language_frontends/english/names.tsv" to "names",
        "RuntimeAssets/language_frontends/english/dictionary.tsv" to "dictionary",
        "RuntimeAssets/language_frontends/english/pos-weights.json" to "{}",
        "RuntimeAssets/language_frontends/english/pos-classes.json" to "{}",
        "RuntimeAssets/language_frontends/english/pos-tagdict.json" to "{}",
        "RuntimeAssets/language_frontends/japanese/sys.dic" to "dictionary",
        "RuntimeAssets/language_frontends/japanese/unk.dic" to "dictionary",
        "RuntimeAssets/language_frontends/japanese/char.bin" to "classes",
        "RuntimeAssets/language_frontends/japanese/matrix.bin" to "matrix",
        "RuntimeAssets/chinese-hubert-base/chinese-hubert-base.onnx" to "hubert",
        "RuntimeAssets/chinese-hubert-base/chinese-hubert-base_weights_fp16.bin" to "weights",
        "RuntimeAssets/chinese-hubert-base/chinese-hubert-base_weights_fp16_manifest.json" to "{}",
        "RuntimeAssets/speaker_encoder.onnx" to "speaker",
        "RuntimeAssets/roberta-wwm-ext-large-onnx/model.onnx" to "roberta",
        "RuntimeAssets/roberta-wwm-ext-large-onnx/roberta_tokenizer/tokenizer.json" to "{}",
    )

    private fun zipOf(entries: List<Pair<String, String>>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

}
