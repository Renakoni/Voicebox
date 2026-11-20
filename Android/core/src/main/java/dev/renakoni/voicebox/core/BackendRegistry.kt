package dev.renakoni.voicebox.core

import dev.renakoni.voicebox.bridge.BackendProvider
import dev.renakoni.voicebox.bridge.CharacterModel

class BackendRegistry(val providers: List<BackendProvider>) {
    private val byId = providers.associateBy { it.id }.also { require(it.size == providers.size) }

    fun provider(id: String) = byId.getValue(id)
    fun provider(model: CharacterModel) = provider(model.ref.backendId)
    fun discoverCharacterModels() = providers.flatMap { it.discoverCharacterModels() }
    fun inspect(model: CharacterModel) = provider(model).inspect(model)
    fun findReferenceExample(model: CharacterModel) = provider(model).findReferenceExample(model)
}
