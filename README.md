# Chuibing

The handle that makes the hammer comfortable.

A Smartisan OS patch tool. Root required.

## What it does

- Edit `/data/system/revone_window_config.xml`
- Group apps by window size and display mode
- Switch TNT mirror / desktop / share, plus hot switch
- Voice commands and aliases
- System WebView provider switch

Saves a backup on first launch. Writes the system XML only when you tap save.

## Build

JDK 17, Android SDK.

Linux / macOS:

```sh
./gradlew assembleDebug
```

Windows:

```bat
gradlew.bat assembleDebug
```

APKs stay next to the repo in `BYFBBB`. Do not commit `local.properties`.

## Notes

- It edits system files. Uninstalling the app does not restore the XML.
- Backups live in app-private storage and vanish on uninstall. Export them first.
- Use only on your own rooted device.

## License

[MIT](LICENSE)
