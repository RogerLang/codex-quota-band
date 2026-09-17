local lvgl = require("lvgl")

local TITLE_MARKER = "CQNOTIFY-47-A9F3"
local BODY_MARKER = "SEQ47-WEEK38-RUN2"
local ROOT = "/data/app/notifications"
local KNOWN_DB = ROOT .. "/noti_reply_sms.db"
local MAX_FILE_BYTES = 512 * 1024
local CHUNK_SIZE = 4096
local OVERLAP = math.max(#TITLE_MARKER, #BODY_MARKER) - 1
local MAX_SCANS = 8

local root = lvgl.Object(nil, { x = 0, y = 0, w = 336, h = 480, border_width = 0, bg_color = '#000000' })
root:clear_flag(lvgl.FLAG.SCROLLABLE)

lvgl.Label(root, { x = 18, y = 42, w = 300, h = 38, text = "STAGE 03F", font_size = 28, text_color = '#8ea0a8', bg_opa = 0 })
local resultLabel = lvgl.Label(root, { x = 18, y = 118, w = 300, h = 72, text = "NOT FOUND", font_size = 46, text_color = '#9ca8ad', bg_opa = 0 })
local detailLabel = lvgl.Label(root, { x = 18, y = 214, w = 300, h = 108, text = "waiting", font_size = 18, text_color = '#8ea0a8', bg_opa = 0 })
local sourceLabel = lvgl.Label(root, { x = 18, y = 330, w = 300, h = 42, text = "SRC --", font_size = 16, text_color = '#66747a', bg_opa = 0 })
lvgl.Label(root, { x = 18, y = 392, w = 300, h = 54, text = "notifications dir\nread-only lvgl.fs probe", font_size = 17, text_color = '#66747a', bg_opa = 0 })

local scanCount = 0
local finished = false

local function scanFile(path)
    local file = io.open(path, "rb")
    if not file then return false, false, false end
    local size = file:seek("end")
    if not size then file:close(); return false, false, false end
    local start = math.max(0, size - MAX_FILE_BYTES)
    file:seek("set", start)
    local remaining = size - start
    local tail = ""
    local titleFound = false
    local bodyFound = false
    while remaining > 0 and not (titleFound and bodyFound) do
        local chunk = file:read(math.min(CHUNK_SIZE, remaining))
        if not chunk or #chunk == 0 then break end
        remaining = remaining - #chunk
        local text = tail .. chunk
        if text:find(TITLE_MARKER, 1, true) then titleFound = true end
        if text:find(BODY_MARKER, 1, true) then bodyFound = true end
        if #text > OVERLAP then tail = text:sub(#text - OVERLAP + 1) else tail = text end
    end
    file:close()
    return true, titleFound, bodyFound
end

local function inspectRoot()
    local ok, dir = pcall(function() return lvgl.fs.open_dir(ROOT) end)
    if not ok or not dir then return false, 0, 0 end
    local entries = 0
    local files = 0
    while entries < 32 do
        local readOk, entry = pcall(function() return dir:read() end)
        if not readOk or not entry or entry == "" then break end
        entries = entries + 1
        if entry:sub(1, 1) ~= "/" then files = files + 1 end
    end
    pcall(function() dir:close() end)
    return true, entries, files
end

local function refresh()
    if finished then return end
    scanCount = scanCount + 1
    local rootOk, entries, files = inspectRoot()
    local dbReadable, titleFound, bodyFound = scanFile(KNOWN_DB)
    local detail = "SCAN " .. scanCount .. "/" .. MAX_SCANS .. " ROOT " .. (rootOk and "OK" or "FAIL")
        .. "\nENTRIES " .. entries .. " ROOTFILES " .. files
        .. "\nREPLYDB " .. (dbReadable and "READ" or "UNREADABLE")

    if titleFound and bodyFound then
        resultLabel:set { text = "FOUND BOTH", text_color = '#5fd3b3' }
        detailLabel:set { text = detail .. "\nnotification text visible", text_color = '#5fd3b3' }
        sourceLabel:set { text = "SRC noti_reply_sms.db", text_color = '#8ea0a8' }
        finished = true
    elseif titleFound or bodyFound then
        resultLabel:set { text = "PARTIAL", text_color = '#ffb84d' }
        detailLabel:set { text = detail .. "\n" .. (titleFound and "title marker only" or "body marker only"), text_color = '#ffb84d' }
        sourceLabel:set { text = "SRC noti_reply_sms.db", text_color = '#8ea0a8' }
    else
        resultLabel:set { text = "NOT FOUND", text_color = '#9ca8ad' }
        detailLabel:set { text = detail .. "\nno marker in known DB", text_color = rootOk and '#ffb84d' or '#ff6b6b' }
        sourceLabel:set { text = "SRC --", text_color = '#66747a' }
    end
    if scanCount >= MAX_SCANS then finished = true end
end

local timer = lvgl.Timer({ period = 15000, repeat_count = -1, cb = refresh })
timer:resume()
refresh()
