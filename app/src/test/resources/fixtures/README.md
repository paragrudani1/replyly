# Screen-tree fixtures

Each file is one `ScreenSnapshot` as JSON, exactly what the debug screen dumps (`ScreenSnapshotJson`).
Load them in tests with `Fixtures.snapshot("<app>/<name>.json")`.

- `whatsapp/`, `whatsapp_business/`, `telegram/`: real dumps from the S23, one folder per app.
- `sample/`: small hand-written trees for format tests. Not a real app.

Name files after the case they cover: `one_to_one.json`, `group.json`, `same_text_twice.json`, `hinglish.json`.
When an app update breaks a parser, add the new dump as a new fixture; keep the old one.
Real dumps contain chat text: use chats with test contacts, or edit names and text before committing.
