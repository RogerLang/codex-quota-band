local lvgl = require("lvgl")

local TITLE_MARKER = "CQNOTIFY-47-A9F3"
local BODY_MARKER = "SEQ47-WEEK38-RUN2"

local FIXED_FILES = {
    "/data/persist.db",
    "/data/persist.db.bk",
}
local NOTIFICATION_ROOT = "/data/app/notifications"
local CHUNK_SIZE = 4096
local MAX_DB_TAIL_BYTES = 512 * 1024
local MAX_NOTIFICATION_FILE_BYTES = 128 * 1024
local MAX_NOTIFICATION_FILES = 16
local MAX_SCANS = 12
local SCAN_PERIOD_MS = 15000
local OVERLAP = math.max(#TITLE_MARKER, #BODY_MARKER) - 1

local root = lvgl.Object(nil, {
    x = 0, y = 0,
    w = 336, h = 480,
    border_width = 0,
    bg_color = '#000000',
})
root:clear_flag(lvgl.FLAG.SCROLLABLE)

lvgl.Label(root, {
    x = 18, y = 42, w = 300, h = 38,
    text = "STAGE 03E",
    font_size = 28,
    text_color = '#8ea0a8',
    bg_opa = 0,
})

local markerLabel = lvgl.Label(root, {
    x = 18, y = 118, w = 300, h = 72,
    text = "NOT FOUND",
    font_size = 46,
    text_color = '#9ca8ad',
    bg_opa = 0,
})

local detailLabel = lvgl.Label(root, {
    x = 18, y = 214, w = 300, h = 88,
    text = "waiting for scan",
    font_size = 18,
    text_color = '#8ea0a8',
    bg_opa = 0,
})

local sourceLabel = lvgl.Label(root, {
    x = 18, y = 312, w = 300, h = 48,
    text = "SRC --",
    font_size = 16,
    text_color = '#66747a',
    bg_opa = 0,
})

lvgl.Label(root, {
    x = 18, y = 386, w = 300, h = 58,
    text = "NotifyApi -> system storage\nread-only limited probe",
    font_size = 17,
    text_color = '#66747a',
    bg_opa = 0,
})

local function positionNearTail(file, maxBytes)
    local ok, size = pcall(function() return file:seek("end") end)
    if not ok or type(size) ~= "number" then
        pcall(function() file:seek("set", 0) end)
        return
    end
    local start = math.max(0, size - maxBytes)
    pcall(function() file:seek("set", start) end)
end

local function scanFile(path, maxBytes, tailOnly)
    local file = io.open(path, "rb")
    if not file then
        return false, false, false, 0
    end

    if tailOnly then positionNearTail(file, maxBytes) end

    local titleFound = false
    local bodyFound = false
    local bytesRead = 0
    local tail = ""

    while bytesRead < maxBytes and not (titleFound and bodyFound) do
        local remaining = maxBytes - bytesRead
        local chunk = file:read(math.min(CHUNK_SIZE, remaining))
        if not chunk or #chunk == 0 then break end
        bytesRead = bytesRead + #chunk

        local text = tail .. chunk
        if not titleFound and text:find(TITLE_MARKER, 1, true) then titleFound = true end
        if not bodyFound and text:find(BODY_MARKER, 1, true) then bodyFound = true end

        if #text > OVERLAP then
            tail = text:sub(#text - OVERLAP + 1)
        else
            tail = text
        end
    end

    file:close()
    return true, titleFound, bodyFound, bytesRead
end

local function listNotificationFiles()
    local ok, pipe = pcall(io.popen, "find " .. NOTIFICATION_ROOT .. " -type f 2>/dev/null")
    if not ok or not pipe then
        return {}, "DIR_ENUM_UNAVAILABLE"
    end

    local files = {}
    local readOk = pcall(function()
        for line in pipe:lines() do
            if line and line ~= "" then
                files[#files + 1] = line
                if #files >= MAX_NOTIFICATION_FILES then break end
            end
        end
    end)
    pcall(function() pipe:close() end)

    if not readOk then
        return {}, "DIR_ENUM_UNAVAILABLE"
    end
    return files, "DIR_ENUM_OK"
end

local function shortPath(path)
    if not path then return "SRC --" end
    if path == "/data/persist.db" then return "SRC /data/persist.db" end
    if path == "/data/persist.db.bk" then return "SRC /data/persist.db.bk" end
    if path:find(NOTIFICATION_ROOT, 1, true) == 1 then return "SRC notifications/*" end
    return "SRC readable system file"
end

local scanCount = 0
local completed = false
local lastOutcome = "NOT FOUND"

local function refresh()
    if completed then return end
    if scanCount >= MAX_SCANS then
        detailLabel:set {
            text = "probe window ended\nreselect face to retry",
            text_color = '#8ea0a8',
        }
        sourceLabel:set { text = "SCAN STOPPED", text_color = '#66747a' }
        completed = true
        return
    end

    scanCount = scanCount + 1

    local titleFound = false
    local bodyFound = false
    local titleSource = nil
    local bodySource = nil
    local fixedReadable = 0
    local notificationReadable = 0

    for _, path in ipairs(FIXED_FILES) do
        local readable, hasTitle, hasBody = scanFile(path, MAX_DB_TAIL_BYTES, true)
        if readable then fixedReadable = fixedReadable + 1 end
        if hasTitle then
            titleFound = true
            titleSource = titleSource or path
        end
        if hasBody then
            bodyFound = true
            bodySource = bodySource or path
        end
    end

    local notificationFiles, enumStatus = listNotificationFiles()
    for _, path in ipairs(notificationFiles) do
        local readable, hasTitle, hasBody = scanFile(path, MAX_NOTIFICATION_FILE_BYTES, false)
        if readable then notificationReadable = notificationReadable + 1 end
        if hasTitle then
            titleFound = true
            titleSource = titleSource or path
        end
        if hasBody then
            bodyFound = true
            bodySource = bodySource or path
        end
        if titleFound and bodyFound then break end
    end

    local detail = "SCAN " .. tostring(scanCount) .. "/" .. tostring(MAX_SCANS)
        .. "  DB R " .. tostring(fixedReadable) .. "/2"
        .. "\nDIR R " .. tostring(notificationReadable) .. "  " .. enumStatus

    if titleFound and bodyFound then
        lastOutcome = "FOUND BOTH"
        markerLabel:set { text = lastOutcome, text_color = '#5fd3b3' }
        detailLabel:set { text = detail .. "\nnotification text visible", text_color = '#5fd3b3' }
        sourceLabel:set { text = shortPath(titleSource or bodySource), text_color = '#8ea0a8' }
        completed = true
        return
    end

    if titleFound or bodyFound then
        lastOutcome = "PARTIAL"
        markerLabel:set { text = lastOutcome, text_color = '#ffb84d' }
        local which = titleFound and "title marker only" or "body marker only"
        detailLabel:set { text = detail .. "\n" .. which, text_color = '#ffb84d' }
        sourceLabel:set { text = shortPath(titleSource or bodySource), text_color = '#8ea0a8' }
        return
    end

    lastOutcome = "NOT FOUND"
    markerLabel:set { text = lastOutcome, text_color = '#9ca8ad' }
    if fixedReadable > 0 or notificationReadable > 0 then
        detailLabel:set { text = detail .. "\nsources readable", text_color = '#ffb84d' }
    else
        detailLabel:set { text = detail .. "\nsources unreadable", text_color = '#ff6b6b' }
    end
    sourceLabel:set { text = "SRC --", text_color = '#66747a' }
end

local timer = lvgl.Timer({
    period = SCAN_PERIOD_MS,
    repeat_count = -1,
    cb = refresh,
})
timer:resume()
refresh()
