local lvgl = require("lvgl")

local CROSS_APP_PATH = "/data/files/io.github.rogerlang.codexquota.validation/watchface_sequence.txt"

local root = lvgl.Object(nil, {
    x = 0, y = 0,
    w = 336, h = 480,
    border_width = 0,
    bg_color = '#000000',
})
root:clear_flag(lvgl.FLAG.SCROLLABLE)

local title = lvgl.Label(root, {
    x = 18, y = 52, w = 300, h = 40,
    text = "STAGE 03A",
    font_size = 28,
    text_color = '#8ea0a8',
    bg_opa = 0,
})

local sequenceLabel = lvgl.Label(root, {
    x = 18, y = 145, w = 300, h = 100,
    text = "SEQ --",
    font_size = 72,
    text_color = '#ffffff',
    bg_opa = 0,
})

local statusLabel = lvgl.Label(root, {
    x = 18, y = 278, w = 300, h = 60,
    text = "WAITING FOR RPK FILE",
    font_size = 18,
    text_color = '#5fd3b3',
    bg_opa = 0,
})

local hintLabel = lvgl.Label(root, {
    x = 18, y = 374, w = 300, h = 54,
    text = "42 -> close RPK -> 43",
    font_size = 18,
    text_color = '#66747a',
    bg_opa = 0,
})

local function readSequence()
    local file = io.open(CROSS_APP_PATH, "r")
    if not file then return nil, "CROSS FILE UNREADABLE" end
    local content = file:read("*all")
    file:close()
    if not content then return nil, "EMPTY FILE" end
    local value = content:match("^%s*(%d+)%s*$")
    local number = value and tonumber(value) or nil
    if not number or number < 0 or number > 999999 then return nil, "INVALID FILE" end
    return number, "RPK FILE READ OK"
end

local function refresh()
    local sequence, status = readSequence()
    if sequence then
        sequenceLabel:set { text = "SEQ " .. tostring(sequence), text_color = '#ffffff' }
        statusLabel:set { text = status, text_color = '#5fd3b3' }
    else
        sequenceLabel:set { text = "SEQ --", text_color = '#9ca8ad' }
        statusLabel:set { text = status, text_color = '#ffb84d' }
    end
end

local timer = lvgl.Timer({
    period = 1000,
    repeat_count = -1,
    cb = refresh,
})
timer:resume()
refresh()
