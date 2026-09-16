# Stage 03A Lua watchface probe

Target: Xiaomi Smart Band 9 Pro, 336x480, EasyFace device type `367`.

This is a validation probe, not the product watchface. It reads one integer from:

`/data/files/io.github.rogerlang.codexquota.validation/watchface_sequence.txt`

and renders `SEQ <n>` once per second.

The purpose is to test a disputed platform boundary: whether a Band 9 Pro Lua watchface can read the
validation Quick App sandbox on the user's firmware. Do not treat cross-sandbox access as established
until the real-device `42 -> 43` test passes.

Source references used for the probe structure:

- `m0tral/MiWatchLuaWatchfaces`, MiBand9Pro examples: device type 367 and 336x480 Lua widget layout.
- `Ziyimiao5054/Xiaomi-Smart-Band-9Pro-Lua-Watchface-CPUload`: real Band 9 Pro Lua/LVGL and `io.open` usage.

## Build

`Stage03AProbe.fprj` and `app/lua/main.lua` are the source-of-truth files. Packaging to `.face` is a
local build step because the community compiler is not committed to this repository.

A build agent may use a locally installed EasyFace compiler or a temporary checkout of
`FangAiden/LuaDevTemplate`/compatible community tooling. If the compiler requires a preview bitmap,
generate a local 336x480 black `preview.png`; it is a build aid and must not be committed unless the
project later adopts it as a real asset.

Expected artifact name: `CodexQuota-Stage03A-watchface.face`.
