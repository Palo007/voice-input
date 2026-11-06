# Voice commands and field formatting: device checklist

Spec: [voice commands and field formatting](../specs/voice-commands-field-formatting.md). JVM tests cover the parser, formatter and `ImeInsertionSession` against a fake editor. They do not exercise Android editors. Every item below is **pending** until it is run on a phone and its result is recorded here with date, device, Android version, APK commit and model.

Setup: install the `dev` debug APK, enable Settings > Voice commands and Delete commands, select Voice Input as the keyboard. Repeat the plain-text rows once with a final-only model (ASR4ALL Medium) and once with live text (Nemotron, or Parakeet with Live text at pauses).

Test fields: the app's Settings > Test dictation field and a native `EditText` app (e.g. Android Messages), a Compose app's field (e.g. a Compose-based notes app), a WebView/Chrome page with a `<textarea>` and `<input type=email>`, a login form password field.

## Native `EditText` (multiline message field)

- [ ] pending: "Hello comma how are you question mark" → `Hello, how are you?`
- [ ] pending: "First line new line second line" → two lines, second capitalized
- [ ] pending: "new paragraph" gives an empty line between paragraphs
- [ ] pending: field `Hello.` + dictation "comma and more" → `Hello, and more.` (period replaced, no space before comma)
- [ ] pending: field `I think ` + dictation "John will come" → `I think John will come.` (capital kept)
- [ ] pending: "Good morning." then a second dictation "Delete that." → `Good morning.` removed, cursor where it was
- [ ] pending: dictate, type one character with another keyboard, then "Delete that." → nothing removed
- [ ] pending: "One. Two. Delete that. Three." → `One. Three.`
- [ ] pending: "Please delete that file" → inserted as text
- [ ] pending: "a literal new line of products" → `a new line of products`
- [ ] pending: with Delete commands off, "Delete that." is inserted as text
- [ ] pending: with Voice commands off, "comma" is inserted as text
- [ ] pending: live text shows `,` and line breaks while speaking, never the words "comma" / "new line" after they are recognized; the final text has each command applied once
- [ ] pending: with editing buttons on, an edit during live text, then "comma …" — no duplicated text
- [ ] pending: SK | EN set to SK: "Ahoj čiarka ako sa máš otáznik" → `Ahoj, ako sa máš?`; "nový riadok" breaks the line
- [ ] pending: single-line field (e.g. a search box): "new line" is dropped and nothing is sent/searched

## Compose text field

- [ ] pending: punctuation and new line rows above behave the same
- [ ] pending: "Delete that." after a dictation removes it (checks `replaceRange` with Compose's input connection)

## WebView / Chrome

- [ ] pending: `<textarea>`: "comma", "new line", "delete that" as above
- [ ] pending: page with a contenteditable or editor that reports no extracted text: final-only delivery still attaches "comma" to the previous word
- [ ] pending: `<input type=email>`: "anna at example dot com" → `anna@example.com`
- [ ] pending: `<input type=url>` / address bar: "example dot com slash news" → `example.com/news`
- [ ] pending: `<input type=tel>`: "plus 421 905 123 456" → `+421905123456`
- [ ] pending: `<input type=number>`: "it is forty two" / "42" → `42`

## Email field (native)

- [ ] pending: "John dot Smith at example dot com" → `john.smith@example.com`, no trailing period, no spaces
- [ ] pending: "comma" is not treated as a command

## Password field

- [ ] pending: dictated text is inserted unchanged (no commands, no personal vocabulary replacements)
- [ ] pending: "Delete that." is inserted as text, previous text is not removed
- [ ] pending: no new Audio history entry is created (with Audio history on)
- [ ] pending: no transcript-inclusive diagnostic capture is created (with that diagnostic on and S1 cleanup on)
- [ ] pending: a field with `IME_FLAG_NO_PERSONALIZED_LEARNING` (e.g. Chrome incognito text field): punctuation commands work, no audio history entry, personal vocabulary not applied, "delete that" inserted as text

## Logcat and diagnostics

- [ ] pending: `adb logcat` during the rows above shows no dictated text, field text or command phrases
- [ ] pending: an exported standard bug report contains no dictated text
