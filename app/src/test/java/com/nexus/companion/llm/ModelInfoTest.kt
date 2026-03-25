package com.nexus.companion.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInfoTest {

    @Test
    fun allModelsContainsFiveModels() {
        assertEquals(5, ModelInfo.ALL_MODELS.size)
    }

    @Test
    fun findByIdReturnsCorrectModel() {
        val noromaid = ModelInfo.findById("noromaid-7b")
        assertNotNull(noromaid)
        assertEquals("Noromaid 7B", noromaid!!.displayName)
        assertEquals(PromptFormat.ALPACA, noromaid.promptFormat)
    }

    @Test
    fun findByIdReturnsNullForUnknown() {
        assertNull(ModelInfo.findById("nonexistent-model"))
    }

    @Test
    fun allModelsHaveValidDownloadUrls() {
        ModelInfo.ALL_MODELS.forEach { model ->
            assertTrue(
                "${model.id} URL should start with https://",
                model.downloadUrl.startsWith("https://")
            )
            assertTrue(
                "${model.id} URL should point to a .gguf file",
                model.downloadUrl.endsWith(".gguf")
            )
        }
    }

    @Test
    fun allModelsHavePositiveSizes() {
        ModelInfo.ALL_MODELS.forEach { model ->
            assertTrue("${model.id} sizeGb should be > 0", model.sizeGb > 0)
            assertTrue("${model.id} sizeBytes should be > 0", model.sizeBytes > 0)
        }
    }

    @Test
    fun allModelsHaveUniqueIds() {
        val ids = ModelInfo.ALL_MODELS.map { it.id }
        assertEquals("Model IDs should be unique", ids.size, ids.distinct().size)
    }

    @Test
    fun allModelsHaveUniqueFileNames() {
        val names = ModelInfo.ALL_MODELS.map { it.fileName }
        assertEquals("File names should be unique", names.size, names.distinct().size)
    }

    @Test
    fun gemmaModelUsesGemmaFormat() {
        val gemma = ModelInfo.findById("gemma-2b")
        assertNotNull(gemma)
        assertEquals(PromptFormat.GEMMA, gemma!!.promptFormat)
    }

    @Test
    fun mythoMaxModelUsesAlpacaFormat() {
        val mythomax = ModelInfo.findById("mythomax-13b")
        assertNotNull(mythomax)
        assertEquals(PromptFormat.ALPACA, mythomax!!.promptFormat)
    }

    @Test
    fun gemma3HereticModelExists() {
        val model = ModelInfo.findById("gemma3-4b-heretic")
        assertNotNull(model)
        assertEquals(PromptFormat.GEMMA, model!!.promptFormat)
        assertTrue(model.sizeGb < 3.0f)
    }

    @Test
    fun qwen3AbliteratedModelExists() {
        val model = ModelInfo.findById("qwen3-4b-abliterated")
        assertNotNull(model)
        assertEquals(PromptFormat.CHATML, model!!.promptFormat)
        assertTrue(model.sizeGb < 3.0f)
    }

    @Test
    fun downloadStateTypes() {
        val idle = ModelManager.DownloadState.Idle
        val downloading = ModelManager.DownloadState.Downloading("test", 0.5f)
        val error = ModelManager.DownloadState.Error("test error")

        assertTrue(idle is ModelManager.DownloadState)
        assertEquals("test", downloading.modelId)
        assertEquals(0.5f, downloading.progress, 0.001f)
        assertEquals("test error", error.message)
    }
}
