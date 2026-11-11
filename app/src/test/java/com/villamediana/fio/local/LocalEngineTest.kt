package com.villamediana.fio.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files(): LocalModel.Files = LocalModel.Files(tmp.newFile("model.gguf").apply { writeText("pesos") }, null)

    @Test
    fun theSnapshotIsOfTheInstructionsAndTheModel() {
        val engine = LocalEngine(tmp.newFolder("cache"))
        val files = files()
        val a = engine.snapshotFile(files, "instruções")!!
        // The same instructions on the same model: the same file, whatever the app's version.
        assertEquals(a, engine.snapshotFile(files, "instruções"))
        assertFalse(a.name.contains(com.villamediana.fio.BuildConfig.VERSION_NAME))
        // Other instructions, or another model: another file.
        assertNotEquals(a, engine.snapshotFile(files, "instruções novas"))
        files.model.appendText("+")
        assertNotEquals(a, engine.snapshotFile(files, "instruções"))
    }

    @Test
    fun keptOnlyWhenTheSnapshotOfTheseInstructionsIsThere() {
        val engine = LocalEngine(tmp.newFolder("cache"))
        val files = files()
        assertFalse(engine.kept(files, "instruções"))
        engine.snapshotFile(files, "instruções")!!.writeText("estado")
        assertTrue(engine.kept(files, "instruções"))
        assertFalse(engine.kept(files, "instruções novas"))
        // No folder to keep it in: never kept.
        assertFalse(LocalEngine(null).kept(files, "instruções"))
    }
}
