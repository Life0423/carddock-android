package com.life0423.carddock.core.port

import com.life0423.carddock.core.model.CardRecord

/** Persists verified card configuration and its last known execution state. */
interface CardStore {
    /** Null means first launch; an empty list means the user removed every card. */
    suspend fun load(): List<CardRecord>?

    /** Replace the collection atomically, or throw without committing a partial file. */
    suspend fun save(cards: List<CardRecord>)
}
