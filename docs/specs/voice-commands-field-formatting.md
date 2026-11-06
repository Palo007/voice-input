# Voice commands and field-aware formatting

Roadmap item #2 in [the feature roadmap](../research/android-offline-asr-feature-roadmap.md#2-field-aware-voice-commands-and-formatting). Keyboard (IME) only. Deterministic: a phrase table, no grammar engine and no LLM.

## Where it runs

Commands and field formatting are the last step before text reaches the field:

```text
recognition → S1 → Harper → personal vocabulary → [commands + field formatting] → ImeInsertionSession
```

- They run inside `ImeInsertionSession` because they need the field type and the text before the cursor. `DictationFormatter` (pure Kotlin) decides; the session applies the result with its existing ownership checks.
- Audio history, retranscription, transcript-inclusive diagnostics and raw comparison output are not changed. They keep the transcript as recognized, including spoken command words.
- `RecognizeActivity` returns text to another app and has no field, so it applies no commands.
- Text passed to the formatter is editor content and dictated text. It is never logged or persisted.

## Phrase table

Matching ignores case and punctuation that the recognizer attached to the words ("New line." matches "new line"). A multi-word phrase does not match across a sentence break ("new. Line" is not a command). Each language has its own table. The table is chosen by the SK | EN button when it is shown, otherwise by the model's reported language. With no language, English is used.

| Command | English | Slovak | Result |
|---|---|---|---|
| New line | new line | nový riadok | `\n` (multiline fields only) |
| New paragraph | new paragraph | nový odsek | `\n\n` (multiline fields only) |
| Comma | comma | čiarka | `,` |
| Period | period, full stop | bodka | `.` |
| Question mark | question mark | otáznik | `?` |
| Exclamation mark | exclamation mark, exclamation point | výkričník | `!` |
| Colon | colon | dvojbodka | `:` |
| Delete that * | delete that | vymaž to | removes the last segment |
| Undo dictation * | undo dictation | zruš diktovanie | removes the whole dictation |

\* Destructive. Recognized only when the separate setting is on, and only as a sentence on its own: at the start of the dictation, after a sentence end or after another command, and followed by the end of the dictation or a sentence end. "Please delete that file" and "Delete that comma" are plain text (the latter with a comma).

**Literal words.** "literal" (Slovak "doslova") before a phrase inserts the phrase as text: "a literal new line of products" → "a new line of products". Commands that are off are always plain text.

## Spacing and capitalization

- Punctuation attaches to the word before it: spaces and one recognizer-added `.` or `,` before it are replaced ("Hello. Comma" → "Hello,"). Recognizer punctuation right after the command is dropped. One space follows when more text follows.
- At the start of a dictation, punctuation attaches to the field text: spaces before the cursor and one trailing `.` or `,` are removed, as the existing `?` editing button does ("Hello." + "comma and more" → "Hello, and more").
- A new line or paragraph removes spaces before it. Text after it starts without a space.
- After `.`, `?`, `!`, a new line or paragraph, the next letter is capitalized. After `,` or `:`, a plainly capitalized next word is lowercased.
- At the cursor: if the field text before it ends a sentence (or is empty) and the field asks for sentence capitals, a first word without capitals is capitalized ("iPhone" is kept). A capital after text that continues a sentence is kept, because it may be a name; the Aa→a button fixes it.
- "Plainly capitalized" uses the lowercase button's rule: one capital, at the start; "I", "USA", "iPhone", "O'Brien" are kept.
- `TYPE_TEXT_FLAG_CAP_CHARACTERS` uppercases everything; `TYPE_TEXT_FLAG_CAP_WORDS` capitalizes each word.

## Field types

| Field (`EditorInfo.inputType`) | Commands | Formatting |
|---|---|---|
| Text, single line | yes; new line and new paragraph are dropped | prose rules above |
| Text, `TYPE_TEXT_FLAG_MULTI_LINE` | yes | prose rules above |
| Email address (incl. web) | no | lowercase; spoken "at", "dot", "underscore", "dash"/"hyphen" (SK "zavináč", "bodka", "podčiarkovník", "pomlčka") become `@ . _ -`; no spaces or commas; no trailing period |
| URI | no | spoken "dot", "slash", "colon" become `. / :`; no spaces; no trailing period; first letter not capitalized |
| Number | no | digits only; with `FLAG_SIGNED` a leading `-` ("minus"); with `FLAG_DECIMAL` one separator between digits. Spoken single digits ("five", "päť") become digits. Compound number words ("twenty", "one hundred", "point") are passed on unchanged for the field's own filter, so they never turn into a wrong number |
| Phone | no | digits and `+ * #` ("plus", "star", "hash"/"pound") |
| Date/time | no | unchanged |
| Password (any password variation) | no | unchanged |

Single-line "new line" is dropped instead of sending the editor action. Sending would trigger Send/Search/Go on a misrecognized phrase, which cannot be undone.

## Partial and final text

Live text (Nemotron, Moonshine, live text at pauses) is a composing region that every update replaces as a whole. The formatter is a pure function of the whole utterance, so rendering commands in every partial cannot apply them twice.

- **Partial:** punctuation and layout commands are rendered; destructive phrases are hidden and act only inside the utterance. Nothing outside the composing region is deleted.
- Live text that formats to nothing (only "delete that" so far) is not written: editors drop an empty composing span, which would look like lost ownership. The earlier live text stays until the final result replaces it.
- With a selection, the result replaces the selection only; no text before it is deleted.
- **Final:** the same rendering plus edits outside the utterance: removing spaces or a trailing `.`/`,` before attached punctuation, and deleting the previous dictation.
- The raw command words never reach the field after a command is recognized; the dictation-screen preview may still show them.

## Delete that and undo dictation

- With text before the command in the same dictation, "delete that" removes the last sentence of it and "undo dictation" removes all of it.
- As the first words of a dictation, both remove the previous dictation in this field. The keyboard keeps only the last committed text for the current field, in memory, for five minutes. It deletes it only if the text before the cursor still ends with exactly that text and nothing is selected. Otherwise nothing is deleted.
- One level only: the deleted dictation cannot be restored by voice.

## Privacy guardrails

A field is private when its variation is a password (`TEXT_VARIATION_PASSWORD`, `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `NUMBER_VARIATION_PASSWORD`) or `imeOptions` has `IME_FLAG_NO_PERSONALIZED_LEARNING`. In a private field:

- personal vocabulary is not applied (partial or final, and not as the legacy Whisper prompt);
- no audio-history recording or transcript is saved, and no transcript-inclusive diagnostic capture is made;
- destructive commands are off, and the committed text is not remembered for "delete that".

## Settings

Settings > Voice commands:

- Voice commands: off by default.
- Delete commands ("delete that", "undo dictation"): off by default, needs voice commands.
- A help list of the phrases for each language.

Field formatting and privacy guardrails are always on.

## Open decisions

1. Defaults: commands off for existing users (opt-in, like the editing buttons)?
2. Single-line "new line": drop (current) or send the editor action?
3. A dictation continuing a sentence keeps the recognizer's first capital ("I think" + "We should go" → "I think We should go"), to protect names. Lowercase it anyway, like Gboard?
4. Passwords: insert unchanged (current), including the recognizer's trailing period?
5. S1 and Harper still run in email, number and password fields before formatting. Skip them there?
6. "period" and "colon" also match everyday words ("grace period", "colon cancer"); the "literal" prefix is the escape. Keep these phrases, or require a pause / sentence end after punctuation words?
