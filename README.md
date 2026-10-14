# Parley

Chat translation for Minecraft 1.8.9 (Forge, works in Lunar Client) that runs entirely on your own PC.
No Google, DeepL or Bing behind it, so there are no API keys, no rate limits and nothing stops working after a few hundred messages.

Incoming chat in another language gets a translated line under it, tagged with the language it came from:

```
[VIP] Mateo: alguien quiere jugar bedwars conmigo?
[ES] [VIP] Mateo: Anybody want to play BedWars with me?
```

Hover the translated line to see the original. You can also write in English and have Parley send your messages in another language.

## Install

1. Download `parley-1.0.0.jar` from the releases page.
2. Put it in your Forge 1.8.9 mods folder. For Lunar Client that is `%USERPROFILE%\.lunarclient\profiles\1.8\mods\forge-1.8.9\`.
3. Start the game.

The first time a language shows up in chat, Parley downloads the model for it (about 110 MB, once). Messages in that language are translated as soon as the download finishes. Models are stored in `.minecraft\config\parley\models\`.

Windows 64-bit only for now.

## Commands

| Command | What it does |
| --- | --- |
| `/tr` | Status and help |
| `/tr in on` / `off` | Translate chat you receive into English (on by default) |
| `/tr out <language>` | Translate everything you type into that language. Commands are never touched |
| `/tr out off` | Send messages as you type them again |
| `/tr say <language> <message>` | Translate and send a single message |
| `/tr langs` | Supported languages and which models are downloaded |
| `/tr ignore <language>` | Stop translating a language you can read. Run it again to undo |
| `/tr download <language>` | Download a language's models now instead of on first use |
| `/tr auto on` / `off` | Download models automatically when a new language appears |

Languages can be given as a code (`es`) or a name (`spanish`). `/parley` works as an alias for `/tr`.

## What it avoids

- **English is never sent through a model.** Every line is checked first, and English chat, slang like `gg ez` or `rn`, and lines Parley isn't sure about are left alone. If a translation comes back almost identical to the original, it is dropped.
- **Game terms stay intact.** BedWars, SkyWars, SkyBlock, UHC and similar names are kept as they are instead of becoming "guerras de cama".
- **Chat shorthand is expanded before translating**, so `u`, `pls` and `idk` in your messages, or `xq`, `vc` and `tmb` in other people's, come out as real words.

## Performance

Translation runs on one low priority background thread and never on the render thread, so it doesn't cost frames. A typical chat line takes 100 to 400 ms on one CPU core. Each loaded model takes about 280 MB of memory. At most two are loaded at once, and a model that hasn't been needed for five minutes is unloaded. Repeated messages are cached.

## Languages

Reading (into English): Spanish, Portuguese, French, German, Italian, Dutch, Polish, Russian, Ukrainian, Turkish, Czech, Slovak, Swedish, Danish, Norwegian, Finnish, Estonian, Indonesian, Tagalog, Romanian, Hungarian, Croatian, Slovenian, Lithuanian, Latvian, Catalan, Afrikaans, Vietnamese, Bulgarian, Japanese, Chinese, Korean, Arabic, Thai, Hindi, Persian, Greek and Hebrew.

Writing (from English): all of the above except Slovak, Tagalog and Korean. Languages marked with `*` in `/tr langs` go through a shared multilingual model and come out rougher than the rest.

Quality is good for normal sentences and weaker for heavy slang or typos. It is a small offline model, not DeepL.

## How it works

Parley uses the [Opus-MT](https://github.com/Helsinki-NLP/Opus-MT) translation models from the University of Helsinki, in the ONNX versions published by [Xenova](https://huggingface.co/Xenova), and runs them with [ONNX Runtime](https://onnxruntime.ai). Text is split into tokens by a small SentencePiece implementation in plain Java. Languages are detected from character patterns and word frequencies; Japanese, Chinese, Korean, Arabic and other scripts are recognised by their alphabet.

## Building

Needs JDK 8.

```
gradlew setupCIWorkspace build
```

The jar ends up in `build/libs`. The engine tests compare against reference output and need downloaded models; point `PARLEY_MODELS` at a folder containing `es-en`, `en-es`, `ru-en`, `mul-en` and `en-ROMANCE`, or they are skipped.

## Credits

- Translation models: Opus-MT by Helsinki-NLP (Apache 2.0), downloaded at runtime, not included in the jar.
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT), included in the jar.
- Language profiles from Shuyo Nakatani's language-detection, as packaged by [optimaize/language-detector](https://github.com/optimaize/language-detector) (Apache 2.0).
- Word frequency lists from [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) (CC BY-SA 4.0), trimmed to the top 3000 words per language.
- Part of the English abbreviation list from [c-Cyril-l/Internet-Abbreviations](https://github.com/c-Cyril-l/Internet-Abbreviations) (MIT).

See `NOTICE` for details. Parley itself is MIT licensed.
