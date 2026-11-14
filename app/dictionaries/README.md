# Bundled pinyin dictionaries

LibIME-format dictionaries installed into the APK as built-in (system-layer)
dictionaries via the `prebuilt-assets` CMake component. At runtime
`DataManager.sync()` copies them to
`<dataDir>/usr/share/fcitx5/pinyin/dictionaries/`, where the pinyin engine's
`loadExtraDict()` auto-loads every `*.dict`.

## Source

- Repository: https://github.com/felixonmars/fcitx5-pinyin-zhwiki
- Release: 0.3.1 (upstream dump snapshots: zhwiki/zhwiktionary 2026-10-01,
  web-slang 2026-10-18)

| File | Bytes | SHA-256 |
|---|---|---|
| zhwiki-20261018.dict | 35,865,725 | `8213ca17acaffe3a3506cdfd1f26181104920047a7f80ef7045b1a961d352959` |
| zhwiktionary-20261018.dict | 5,235,292 | `b4a1b8017eeee9687d06ccbb1e205ab1661e5444ddd24c87f9808a535ce0e31b` |
| web-slang-20261018.dict | 9,516 | `d2b7b8a01b146e4745c63e31ca43ddc98e42477559d88982ab0ada5ea84d1b14` |

Hashes verified against the GitHub release `digest` fields. File format:
LibIME pinyin dict, magic `13 c6 0f 00`, format version 2.

## Maintenance notes

- Adding/replacing a `*.dict` here rebuilds `descriptor.json` automatically;
  removing one requires a `clean` (or `cleanFcitxComponents`) because
  `cmake --install` never deletes stale files from `assets/usr/`.
- A user can disable a bundled dict by creating
  `<externalFiles>/data/pinyin/dictionaries/<name>.dict.disable`
  (e.g. via the system file picker exposed by the app); the engine honors
  `<file>.disable` markers across all pinyin data layers.
- A same-named `.dict` in the user layer
  (`<externalFiles>/data/pinyin/dictionaries/`) shadows the bundled copy,
  because the user `PkgData` layer is scanned first.

## License

Compiled upstream from Chinese Wikipedia/Wiktionary content; the dictionary
data follows the Wikimedia licensing (CC BY-SA 4.0, Wikipedia text is
dual-licensed with GFDL). Upstream tooling is Unlicense. In-app attribution
is provided via Settings → Licenses.
