# Bundled pinyin dictionaries

LibIME-format dictionaries installed into the APK as built-in (system-layer)
dictionaries via the `prebuilt-assets` CMake component. At runtime
`DataManager.sync()` copies them to
`<dataDir>/usr/share/fcitx5/pinyin/dictionaries/`, where the pinyin engine's
`loadExtraDict()` auto-loads every `*.dict` (a same-stem `.disable` file in a
user layer can still disable one).

## Source

- Repository: https://github.com/felixonmars/fcitx5-pinyin-zhwiki
- Release: 0.3.1 (data snapshot 2026-10-18)

| File | Bytes |
|---|---|
| zhwiki-20261018.dict | 35,865,725 |
| zhwiktionary-20261018.dict | 5,235,292 |
| web-slang-20261018.dict | 9,516 |

## License

Derived from Chinese Wikipedia/Wiktionary content, compiled by upstream into
LibIME dictionaries. See the upstream repository for license details
(CC BY-SA for wiki-derived word lists).
