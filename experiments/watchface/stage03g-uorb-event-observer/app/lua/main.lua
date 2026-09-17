local lvgl = require("lvgl")

local topic_ok, topic = pcall(require, "topic")

local root = lvgl.Object(nil, {
    x = 0, y = 0,
    w = 336, h = 480,
    border_width = 0,
    bg_color = '#000000',
    pad_all = 0,
})
root:clear_flag(lvgl.FLAG.SCROLLABLE)

lvgl.Label(root, {
    x = 18, y = 28, w = 300, h = 34,
    text = "STAGE 03G",
    font_size = 24,
    text_color = '#8ea0a8',
    bg_opa = 0,
})

local phaseLabel = lvgl.Label(root, {
    x = 18, y = 68, w = 300, h = 32,
    text = topic_ok and "WARMUP 10s" or "TOPIC MODULE FAIL",
    font_size = 18,
    text_color = topic_ok and '#ffb84d' or '#ff6b6b',
    bg_opa = 0,
})

local uptimeLabel = lvgl.Label(root, {
    x = 220, y = 32, w = 96, h = 24,
    text = "UP 0s",
    font_size = 14,
    text_color = '#66747a',
    bg_opa = 0,
})

local topics = {
    { name = "miwear_event", label = "MIWEAR" },
    { name = "system_event", label = "SYSTEM" },
    { name = "app_data_update", label = "APP_UPDATE" },
    { name = "event_data_sync", label = "DATA_SYNC" },
}

local rowLabels = {}
local state = {}
local subscriptions = {}
local ready = false
local uptime = 0

local function valueLength(value)
    if type(value) == "string" then return #value end
    if type(value) == "table" then
        local ok, n = pcall(function() return #value end)
        if ok then return n end
    end
    return -1
end

local function compactType(value)
    local t = type(value)
    if t == "table" then return "tbl" end
    if t == "string" then return "str" end
    if t == "number" then return "num" end
    if t == "boolean" then return "bool" end
    if t == "userdata" then return "ud" end
    if t == "nil" then return "nil" end
    return t:sub(1, 4)
end

local function renderRow(index)
    local item = topics[index]
    local s = state[index]
    local label = rowLabels[index]
    if not label then return end

    if not s.subscribed then
        label:set {
            text = string.format("%-10s SUB ERR", item.label),
            text_color = '#ff6b6b',
        }
        return
    end

    local status = s.lastStatus or "--"
    local kind = s.lastType or "--"
    local len = s.lastLen ~= nil and tostring(s.lastLen) or "-"
    label:set {
        text = string.format("%-10s %3d  s:%s t:%s n:%s", item.label, s.count, status, kind, len),
        text_color = s.count > 0 and '#5fd3b3' or '#b5c0c5',
    }
end

local function resetCounters()
    for i = 1, #topics do
        state[i].count = 0
        state[i].lastStatus = "--"
        state[i].lastType = "--"
        state[i].lastLen = nil
        renderRow(i)
    end
end

for i, item in ipairs(topics) do
    state[i] = {
        subscribed = false,
        count = 0,
        lastStatus = "--",
        lastType = "--",
        lastLen = nil,
    }

    rowLabels[i] = lvgl.Label(root, {
        x = 18, y = 120 + (i - 1) * 58, w = 300, h = 44,
        text = item.label .. " SUB --",
        font_size = 16,
        text_color = '#b5c0c5',
        bg_opa = 0,
    })
end

local infoLabel = lvgl.Label(root, {
    x = 18, y = 365, w = 300, h = 52,
    text = "counts only; payload never rendered",
    font_size = 15,
    text_color = '#66747a',
    bg_opa = 0,
})

lvgl.Label(root, {
    x = 18, y = 420, w = 300, h = 42,
    text = "wait READY -> send one NotifyApi marker",
    font_size = 14,
    text_color = '#66747a',
    bg_opa = 0,
})

local function makeCallback(index)
    return function(...)
        if not ready then return end

        local argc = select('#', ...)
        local a1, a2, a3 = ...
        local status = "--"
        local value = nil

        if argc >= 3 then
            status = tostring(a2)
            value = a3
        elseif argc == 2 then
            status = tostring(a1)
            value = a2
        elseif argc == 1 then
            value = a1
        end

        local s = state[index]
        s.count = math.min(s.count + 1, 999)
        s.lastStatus = status
        s.lastType = compactType(value)
        s.lastLen = valueLength(value)
        renderRow(index)
    end
end

if topic_ok and topic then
    for i, item in ipairs(topics) do
        local callback = makeCallback(i)
        local ok, sub = pcall(function()
            return topic.subscribe(item.name, 0, callback)
        end)
        if not ok then
            ok, sub = pcall(function()
                return topic.subscribe(item.name, callback)
            end)
        end

        state[i].subscribed = ok
        if ok then subscriptions[#subscriptions + 1] = sub end
        renderRow(i)
    end
end

local secondTimer
secondTimer = lvgl.Timer({
    period = 1000,
    repeat_count = -1,
    cb = function()
        uptime = uptime + 1
        uptimeLabel:set { text = "UP " .. tostring(uptime) .. "s" }
        if topic_ok and not ready and uptime >= 10 then
            resetCounters()
            ready = true
            phaseLabel:set { text = "READY", text_color = '#5fd3b3' }
            infoLabel:set { text = "baseline reset; observe event deltas only" }
        end
    end,
})
secondTimer:resume()
