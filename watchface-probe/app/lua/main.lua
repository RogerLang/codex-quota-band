local lvgl = require("lvgl")

local APP_ID = "io.github.rogerlang.codexquota.validation"
local FILE_NAME = "watchface_sequence.txt"
local FAST_PATHS = {
    "/data/files/" .. APP_ID .. "/" .. FILE_NAME,
    "/data/quickapp/files/" .. APP_ID .. "/" .. FILE_NAME,
}

local root = lvgl.Object(nil, {
    x = 0, y = 0,
    w = 336, h = 480,
    border_width = 0,
    bg_color = '#000000',
})
root:clear_flag(lvgl.FLAG.SCROLLABLE)

local title = lvgl.Label(root, {
    x = 18, y = 46, w = 300, h = 40,
    text = "STAGE 03A2",
    font_size = 28,
    text_color = '#8ea0a8',
    bg_opa = 0,
})

local sequenceLabel = lvgl.Label(root, {
    x = 18, y = 132, w = 300, h = 100,
    text = "SEQ --",
    font_size = 72,
    text_color = '#ffffff',
    bg_opa = 0,
})

local statusLabel = lvgl.Label(root, {
    x = 18, y = 264, w = 300, h = 62,
    text = "SEARCHING RPK FILE",
    font_size = 18,
    text_color = '#5fd3b3',
    bg_opa = 0,
})

local pathLabel = lvgl.Label(root, {
    x = 18, y = 330, w = 300, h = 42,
    text = "PATH --",
    font_size = 16,
    text_color = '#8ea0a8',
    bg_opa = 0,
})

local hintLabel = lvgl.Label(root, {
    x = 18, y = 392, w = 300, h = 54,
    text = "send 42 / 43 from phone",
    font_size = 17,
    text_color = '#66747a',
    bg_opa = 0,
})

local discoveredPath = nil
local discoveredSource = nil
local scanTicks = 0
local SCAN_INTERVAL_TICKS = 10

local function readNumber(path)
    local file = io.open(path, "r")
    if not file then return nil end
    local content = file:read("*all")
    file:close()
    if not content then return nil end
    local value = content:match("^%s*(%d+)%s*$")
    local number = value and tonumber(value) or nil
    if not number or number < 0 or number > 999999 then return nil end
    return number
end

local function tryFastPaths()
    for index, path in ipairs(FAST_PATHS) do
        local value = readNumber(path)
        if value then
            discoveredPath = path
            discoveredSource = "FAST " .. tostring(index)
            return value
        end
    end
    return nil
end

local function scanForFile()
    local tmpDir = SCRIPT_PATH .. "/stage03a_tmp"
    local scanOut = tmpDir .. "/find.txt"

    local execOk = pcall(function()
        os.execute("mkdir -p " .. tmpDir)
        os.execute("find /data -type f -name " .. FILE_NAME .. " 2>/dev/null > " .. scanOut)
    end)
    if not execOk then return nil, "SCAN EXEC UNAVAILABLE" end

    local result = io.open(scanOut, "r")
    if not result then return nil, "SCAN OUTPUT UNREADABLE" end

    local foundAny = false
    local foundUnreadable = false
    for line in result:lines() do
        if line and line ~= "" then
            foundAny = true
            if line:find(APP_ID, 1, true) then
                local value = readNumber(line)
                if value then
                    result:close()
                    pcall(os.remove, scanOut)
                    discoveredPath = line
                    discoveredSource = "SCAN FOUND"
                    return value, "SCAN FOUND"
                end
                foundUnreadable = true
            end
        end
    end
    result:close()
    pcall(os.remove, scanOut)

    if foundUnreadable then return nil, "FOUND BUT UNREADABLE" end
    if foundAny then return nil, "OTHER FILES ONLY" end
    return nil, "SCAN NO FILE"
end

local function sourceText()
    if discoveredSource == "FAST 1" then return "PATH /data/files" end
    if discoveredSource == "FAST 2" then return "PATH /data/quickapp/files" end
    if discoveredSource == "SCAN FOUND" then return "PATH DISCOVERED BY SCAN" end
    return "PATH --"
end

local function refresh()
    if discoveredPath then
        local value = readNumber(discoveredPath)
        if value then
            sequenceLabel:set { text = "SEQ " .. tostring(value), text_color = '#ffffff' }
            statusLabel:set { text = "RPK FILE READ OK", text_color = '#5fd3b3' }
            pathLabel:set { text = sourceText(), text_color = '#8ea0a8' }
            return
        end
        discoveredPath = nil
        discoveredSource = nil
    end

    local fastValue = tryFastPaths()
    if fastValue then
        sequenceLabel:set { text = "SEQ " .. tostring(fastValue), text_color = '#ffffff' }
        statusLabel:set { text = "RPK FILE READ OK", text_color = '#5fd3b3' }
        pathLabel:set { text = sourceText(), text_color = '#8ea0a8' }
        return
    end

    scanTicks = scanTicks + 1
    local scanStatus = "SEARCHING RPK FILE"
    if scanTicks == 1 or scanTicks >= SCAN_INTERVAL_TICKS then
        scanTicks = 0
        local scanValue, status = scanForFile()
        scanStatus = status or scanStatus
        if scanValue then
            sequenceLabel:set { text = "SEQ " .. tostring(scanValue), text_color = '#ffffff' }
            statusLabel:set { text = "RPK FILE READ OK", text_color = '#5fd3b3' }
            pathLabel:set { text = sourceText(), text_color = '#8ea0a8' }
            return
        end
    end

    sequenceLabel:set { text = "SEQ --", text_color = '#9ca8ad' }
    statusLabel:set { text = scanStatus, text_color = '#ffb84d' }
    pathLabel:set { text = "PATH SEARCH ACTIVE", text_color = '#8ea0a8' }
end

local timer = lvgl.Timer({
    period = 1000,
    repeat_count = -1,
    cb = refresh,
})
timer:resume()
refresh()
