local lvgl = require("lvgl")

local TITLE_MARKER = "CQNOTIFY-47-A9F3"
local BODY_MARKER = "SEQ47-WEEK38-RUN2"

local FIXED_FILES = {
    "/data/persist.db",
    "/data/persist.db.bk",
}
local NOTIFICATION_ROOT = "/data/app/notifications"
local CHUNK_SIZE = 4096
local MAX_DB_BYTES = 16 * 1024 * 1024
local MAX_NOTIFICATION_FILE_BYTES = 2 * 1024 * 1024
local MAX_NOTIFICATION_FILES = 64
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
    x = 18, y = 214, w = 300, h = 74,
    text = "waiting for scan",
    font_size = 18,
    text_color = '#8ea0a8',
    bg_opa = 0,
})

local sourceLabel = lvgl.Label(root, {
    x = 18, y = 304, w = 300, h = 54,
    text = "SRC --",
    font_size = 16,
    text_color = '#66747a',
    bg_opa = 0,
})

lvgl.Label(root, {
    x = 18, y = 386, w = 300, h = 58,
    text = "NotifyApi -> system storage\nread-only probe",
    font_size = 17,
    text_color = '#66747a',
    bg_opa = 0,
})

local function scanFile(path, maxBytes)
    local file = io.open(path, "rb")
    if not file then
        return false, false, false, 0
    end

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

local function refresh()
    local titleFound = false
    local bodyFound = false
    local titleSource = nil
    local bodySource = nil
    local fixedReadable = 0
    local notificationReadable = 0

    for _, path in ipairs(FIXED_FILES) do
        local readable, hasTitle, hasBody = scanFile(path, MAX_DB_BYTES)
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
        local readable, hasTitle, hasBody = scanFile(path, MAX_NOTIFICATION_FILE_BYTES)
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

    local detail = "DB R " .. tostring(fixedReadable) .. "/2"
        .. "  DIR R " .. tostring(notificationReadable)
        .. "\n" .. enumStatus

    if titleFound and bodyFound then
        markerLabel:set { text = "FOUND BOTH", text_color = '#5fd3b3' }
        detailLabel:set { text = detail .. "\nnotification text visible", text_color = '#5fd3b3' }
        sourceLabel:set { text = shortPath(titleSource or bodySource), text_color = '#8ea0a8' }
        return
    end

    if titleFound or bodyFound then
        markerLabel:set { text = "PARTIAL", text_color = '#ffb84d' }
        local which = titleFound and "title marker only" or "body marker only"
        detailLabel:set { text = detail .. "\n" .. which, text_color = '#ffb84d' }
        sourceLabel:set { text = shortPath(titleSource or bodySource), text_color = '#8ea0a8' }
        return
    end

    markerLabel:set { text = "NOT FOUND", text_color = '#9ca8ad' }
    if fixedReadable > 0 or notificationReadable > 0 then
        detailLabel:set { text = detail .. "\nsources readable", text_color = '#ffb84d' }
    else
        detailLabel:set { text = detail .. "\nsources unreadable", text_color = '#ff6b6b' }
    end
    sourceLabel:set { text = "SRC --", text_color = '#66747a' }
end

local timer = lvgl.Timer({
    period = 5000,
    repeat_count = -1,
    cb = refresh,
})
timer:resume()
refresh()
