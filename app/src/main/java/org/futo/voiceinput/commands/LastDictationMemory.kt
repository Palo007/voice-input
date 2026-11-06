package org.futo.voiceinput.commands

import org.futo.voiceinput.ImeEditorKey

/**
 * The text last committed by dictation in one field, so "delete that" in the next dictation can
 * remove it. In memory only, one field, one level, and expiring: the keyboard is rebound for every
 * dictation, so this outlives the input session. Dictated content: never log or persist it.
 */
internal class LastDictationMemory(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var key: ImeEditorKey? = null
    private var text: String? = null
    private var at = 0L

    @Synchronized fun remember(key: ImeEditorKey, text: String) {
        if (text.isEmpty()) return forget()
        this.key = key
        this.text = text
        at = clock()
    }

    @Synchronized fun recall(key: ImeEditorKey): String? {
        if (this.key != key || clock() - at > TTL_MS) return null
        return text
    }

    @Synchronized fun forget() {
        key = null
        text = null
    }

    companion object {
        const val TTL_MS = 5 * 60 * 1000L
        /** Shared by keyboard sessions in this process. */
        val shared = LastDictationMemory()
    }
}
