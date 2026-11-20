package dev.renakoni.voicebox.inference

import java.util.Locale

/** Model roles used for session configuration and tracing. */
object ModelRoles {
    fun roleForModelFile(modelFileName: String): InferenceModelRole {
        val name = modelFileName.lowercase(Locale.ROOT)
        return when {
            "roberta" in name -> InferenceModelRole.ROBERTA
            "hubert" in name -> InferenceModelRole.HUBERT
            "speaker" in name || "sv_encoder" in name -> InferenceModelRole.SPEAKER_ENCODER
            "prompt_encoder" in name -> InferenceModelRole.PROMPT_ENCODER
            "vits" in name || "vocoder" in name -> InferenceModelRole.VOCODER
            "t2s" in name || "decoder" in name -> InferenceModelRole.T2S
            else -> InferenceModelRole.TEXT_FRONTEND
        }
    }

}
