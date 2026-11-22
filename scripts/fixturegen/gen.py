"""Generator for the synthetic parser fixtures (`synthetic-*.json`, ticket #2).

Usage, from the repo root:
    python3 -I scripts/fixturegen/gen.py app/src/test/resources/fixtures

It rewrites every synthetic fixture; real dumps (no `synthetic-` prefix) are never touched.
Edit the screens here rather than the JSON, then rerun and check `git diff`. See docs/PARSER_FIXTURES.md.
"""
import json
import math
import os
import sys

W, H = 1080, 2340
# 23:45:30 on Saturday 21 November 2026 in India: after every "today" message on the screens
# (a capture never comes before a message it shows; ChatAssembler.checkDates relies on that).
CAPTURED = "2026-11-21T18:15:30Z"
NNBSP = " "  # Android 14+ puts a narrow no-break space before am/pm.
NBSP = " "


def b(l, t, r, bot):
    return f"[{l},{t}][{r},{bot}]"


def node(cls, bounds, text=None, vid=None, cd=None, hint=None, children=None, **flags):
    n = {"className": cls}
    if vid:
        n["viewId"] = vid
    if text is not None:
        n["text"] = text
    if cd is not None:
        n["contentDescription"] = cd
    if hint is not None:
        n["hint"] = hint
    n["bounds"] = bounds
    for k in ("editable", "focused", "password", "visible", "clickable", "scrollable"):
        if k in flags:
            n[k] = flags[k]
    if children:
        n["children"] = children
    return n


def snapshot(pkg, root, captured=CAPTURED):
    return {
        "formatVersion": 1,
        "packageName": pkg,
        "capturedAt": captured,
        "screenWidth": W,
        "screenHeight": H,
        "root": root,
    }


# ---------------------------------------------------------------- WhatsApp

LIST_TOP, LIST_BOTTOM = 257, 2052


class Wa:
    def __init__(self, pkg, ids=True):
        self.pkg = pkg
        self.ids = ids

    def id(self, name):
        return f"{self.pkg}:id/{name}" if self.ids else None

    def toolbar(self, title, subtitle):
        kids = [
            node("android.widget.ImageButton", b(0, 120, 100, 247), cd="Navigate up", clickable=True),
            node("android.widget.ImageView", b(100, 128, 210, 240), vid=self.id("conversation_contact_photo")),
            node(
                "android.widget.LinearLayout",
                b(220, 120, 700, 247),
                vid=self.id("conversation_contact"),
                clickable=True,
                children=[
                    node("android.widget.TextView", b(220, 128, 700, 190), text=title,
                         vid=self.id("conversation_contact_name")),
                ]
                + ([node("android.widget.TextView", b(220, 190, 700, 240), text=subtitle,
                         vid=self.id("conversation_contact_status"))] if subtitle else []),
            ),
            node("android.widget.ImageButton", b(730, 130, 840, 240), cd="Video call", clickable=True),
            node("android.widget.ImageButton", b(850, 130, 960, 240), cd="Voice call", clickable=True),
            node("android.widget.ImageButton", b(970, 130, 1080, 240), cd="More options", clickable=True),
        ]
        return node("android.view.ViewGroup", b(0, 110, 1080, 257), vid=self.id("toolbar"), children=kids)

    def input_bar(self, draft=None, composer_quote=None):
        kids = []
        top = 2052
        if composer_quote:
            qs, qt = composer_quote
            kids.append(
                node("android.widget.LinearLayout", b(30, 1900, 1050, 2052), vid=self.id("quoted_message_holder"),
                     children=[
                         node("android.widget.TextView", b(60, 1915, 900, 1970), text=qs, vid=self.id("quoted_title")),
                         node("android.widget.TextView", b(60, 1975, 900, 2035), text=qt, vid=self.id("quoted_text")),
                         node("android.widget.ImageButton", b(940, 1930, 1030, 2020), cd="Cancel quote",
                              clickable=True),
                     ]))
        kids += [
            node("android.widget.ImageButton", b(30, 2085, 130, 2185), cd="Emoji", clickable=True),
            node("android.widget.EditText", b(140, 2075, 760, 2190), text=draft, hint="Message",
                 vid=self.id("entry"), editable=True, focused=True, clickable=True),
            node("android.widget.ImageButton", b(770, 2085, 860, 2185), cd="Attach", clickable=True),
            node("android.widget.ImageButton", b(870, 2085, 960, 2185), cd="Camera", clickable=True),
            node("android.widget.ImageButton", b(970, 2075, 1070, 2190),
                 cd="Send" if draft else "Voice message", vid=self.id("send" if draft else "voice_note_btn"),
                 clickable=True),
        ]
        return node("android.widget.LinearLayout", b(0, 1900 if composer_quote else top, 1080, 2214),
                    vid=self.id("input_layout"), children=kids)

    @staticmethod
    def lines(text):
        longest = max(len(p) for p in text.split("\n"))
        n = sum(max(1, math.ceil(len(p) / 30)) for p in text.split("\n"))
        return n, longest

    def message_row(self, y, m, group):
        out = m["kind"] == "out"
        text = m["text"]
        nlines, longest = self.lines(text)
        width = 820 if nlines > 1 else min(820, 70 + 24 * longest + 170 + (60 if out else 0))
        inline = nlines == 1 and not m.get("quote") and not m.get("edited")
        if m.get("quote"):
            width = max(width, 600)
        if m.get("name"):
            width = max(width, 70 + 24 * len(m["name"]) + (230 if m.get("pushname") else 0))
            width = min(width, 820)
        height = 24 + (52 if m.get("name") else 0) + (150 if m.get("quote") else 0) + \
            (48 if m.get("forwarded") else 0) + nlines * 56 + (4 if inline else 44)
        row_top = y
        inner_top = y + 6
        divider = None
        if m.get("divider_inside"):
            divider = node("android.widget.TextView", b(450, inner_top, 630, inner_top + 70),
                           text=m["divider_inside"], vid=self.id("conversation_row_date_divider"))
            inner_top += 90
        if out:
            right = 1040
            left = right - width
        else:
            left = 125 if group else 40
            right = left + width
        top, bottom = inner_top, inner_top + height
        cy = top + 18
        kids = []
        if m.get("name"):
            name_kids = [node("android.widget.TextView", b(left + 30, cy, left + 30 + 24 * len(m["name"]), cy + 48),
                              text=m["name"], vid=self.id("name_in_group_tv"))]
            if m.get("pushname"):
                px = left + 50 + 24 * len(m["name"])
                name_kids.append(node("android.widget.TextView", b(px, cy, min(right - 20, px + 220), cy + 48),
                                      text=m["pushname"], vid=self.id("pushname_in_group_tv")))
            kids.append(node("android.widget.LinearLayout", b(left + 30, cy, right - 20, cy + 48),
                             vid=self.id("name_in_group"), children=name_kids))
            cy += 52
        if m.get("forwarded"):
            kids.append(node("android.widget.TextView", b(left + 30, cy, left + 260, cy + 44), text="Forwarded",
                             vid=self.id("forwarded_label")))
            cy += 48
        if m.get("quote"):
            qs, qt = m["quote"]
            kids.append(node("android.widget.LinearLayout", b(left + 16, cy, right - 16, cy + 140),
                             vid=self.id("quoted_message"), clickable=True, children=[
                                 node("android.widget.TextView", b(left + 40, cy + 12, right - 40, cy + 62), text=qs,
                                      vid=self.id("quoted_title")),
                                 node("android.widget.TextView", b(left + 40, cy + 70, right - 40, cy + 126), text=qt,
                                      vid=self.id("quoted_text")),
                             ]))
            cy += 150
        shown = text + (NBSP * 12 if m.get("pad") else "")
        text_right = right - 30 if nlines > 1 else min(right - 30, left + 40 + 24 * longest)
        kids.append(node("android.widget.TextView", b(left + 30, cy, text_right, cy + nlines * 56),
                         text=shown, vid=self.id("message_text")))
        cy += nlines * 56
        if inline:
            cy -= 44
        date_kids = []
        dx = right - 30 - (60 if out else 0)
        if m.get("edited"):
            date_kids.append(node("android.widget.TextView", b(dx - 300, cy, dx - 160, cy + 40), text="Edited",
                                  vid=self.id("edited_label")))
        date_kids.append(node("android.widget.TextView", b(dx - 150, cy, dx, cy + 40), text=m["time"],
                              vid=self.id("date")))
        if out:
            date_kids.append(node("android.widget.ImageView", b(dx + 5, cy, dx + 55, cy + 40),
                                  cd=m.get("status", "Read"), vid=self.id("status")))
        kids.append(node("android.widget.LinearLayout", b(dx - 300, cy, right - 20, cy + 40),
                         vid=self.id("date_wrapper"), children=date_kids))
        bubble = node("android.widget.LinearLayout", b(left, top, right, bottom), vid=self.id("main_layout"),
                      clickable=True, children=kids)
        row_kids = ([divider] if divider else []) + [bubble]
        row_bottom = bottom + 8
        return node("android.view.ViewGroup", b(0, row_top, 1080, row_bottom), clickable=True,
                    children=row_kids), row_bottom

    def center_row(self, y, text, vid, width=None, height=70):
        if width is None:
            width = min(900, 40 + 24 * len(text))
        nlines = 1
        if len(text) > 34:
            nlines = math.ceil(len(text) / 34)
            width = 900
            height = 30 + nlines * 52
        left = (1080 - width) // 2
        child = node("android.widget.TextView", b(left, y + 8, left + width, y + 8 + height), text=text,
                     vid=self.id(vid))
        return node("android.view.ViewGroup", b(0, y, 1080, y + height + 16), children=[child]), y + height + 16

    def banner_row(self, y, text):
        child = node("android.widget.TextView", b(0, y + 8, 1080, y + 78), text=text,
                     vid=self.id("unread_divider_tv"))
        return node("android.view.ViewGroup", b(0, y, 1080, y + 86), children=[child]), y + 86

    def chat(self, title, subtitle, items, start_y, group=False, draft=None, composer_quote=None, fab=None,
             captured=CAPTURED):
        rows = []
        y = start_y
        for it in items:
            k = it["kind"]
            if k in ("in", "out"):
                row, y = self.message_row(y, it, group)
            elif k == "divider":
                row, y = self.center_row(y, it["text"], "conversation_row_date_divider")
            elif k == "system":
                row, y = self.center_row(y, it["text"], "info")
            elif k == "banner":
                row, y = self.banner_row(y, it["text"])
            else:
                raise ValueError(k)
            rows.append(row)
        assert y <= LIST_BOTTOM, f"{title}: content overflows list ({y})"
        clipped = [clip(r, LIST_TOP, LIST_BOTTOM) for r in rows]
        clipped = [r for r in clipped if r is not None]
        lst = node("android.widget.ListView", b(0, LIST_TOP, 1080, LIST_BOTTOM),
                   vid="android:id/list" if self.ids else None, scrollable=True, children=clipped)
        kids = [self.toolbar(title, subtitle), lst, self.input_bar(draft, composer_quote)]
        if fab:
            kids.append(node("android.widget.ImageButton", b(940, 1780, 1040, 1880), cd="Scroll to bottom",
                             vid=self.id("scroll_bottom"), clickable=True, children=[
                                 node("android.widget.TextView", b(1000, 1770, 1050, 1820), text=fab,
                                      vid=self.id("badge"))]))
        root = node("android.widget.FrameLayout", b(0, 0, W, H), children=kids)
        return snapshot(self.pkg, root, captured)


def parse_bounds(s):
    import re
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", s)
    return [int(x) for x in m.groups()]


def clip(n, top, bottom):
    l, t, r, bt = parse_bounds(n["bounds"])
    if bt <= top or t >= bottom:
        return None
    n = dict(n)
    n["bounds"] = b(l, max(t, top), r, min(bt, bottom))
    if "children" in n:
        kids = [clip(c, top, bottom) for c in n["children"]]
        kids = [k for k in kids if k is not None]
        if kids:
            n["children"] = kids
        else:
            del n["children"]
    return n


def t(s):
    """'8:02 am' with the narrow no-break space Android 14+ uses."""
    return s.replace(" ", NNBSP)


WA_ONE_TO_ONE = [
    {"kind": "system",
     "text": "Messages and calls are end-to-end encrypted. No one outside of this chat, not even WhatsApp, "
             "can read or listen to them. Tap to learn more."},
    {"kind": "divider", "text": "YESTERDAY"},
    {"kind": "in", "text": "Are you coming to Riya's birthday on Saturday?", "time": "9:15 pm"},
    {"kind": "out", "text": "Yes! What time does it start?", "time": "9:20 pm", "status": "Read"},
    {"kind": "divider", "text": "TODAY"},
    {"kind": "in", "text": "Today", "time": "10:02 am"},
    {"kind": "in", "text": "7 pm at her place", "time": "10:02 am"},
    {"kind": "out", "text": "Perfect, I'll bring the cake 🎂", "time": "10:05 am", "status": "Read",
     "quote": ("Asha Patel", "7 pm at her place")},
    {"kind": "out", "text": "10:30", "time": "10:06 am", "status": "Delivered"},
    {"kind": "in", "text": "This message was deleted", "time": "10:07 am"},
    {"kind": "in", "text": "Can you also pick up candles?", "time": "10:08 am", "pad": True},
]

WA_GROUP = [
    {"kind": "in", "text": "Also bring a torch", "time": t("6:10 pm")},
    {"kind": "in", "name": "Asha", "text": "Good morning all ☀️", "time": t("8:02 am"), "divider_inside": "TODAY"},
    {"kind": "in", "text": "Trek is on for Sunday?", "time": t("8:03 am")},
    {"kind": "system", "text": "Asha added Meera"},
    {"kind": "in", "name": "Meera", "text": "Hi everyone! Thanks for adding me", "time": t("8:10 am")},
    {"kind": "out", "text": "Welcome Meera! 🎉", "time": t("8:12 am"), "status": "Read"},
    {"kind": "banner", "text": "2 UNREAD MESSAGES"},
    {"kind": "in", "name": "+91 98765 43210", "pushname": "~ Kiran", "text": "Sunday works. What time?",
     "time": t("8:15 am"), "quote": ("You", "Welcome Meera! 🎉")},
    {"kind": "in", "name": "Ravi", "forwarded": True, "text": "Trek checklist: water, shoes, cap",
     "time": t("8:20 am")},
    {"kind": "in", "text": "ok", "time": t("8:21 am")},
    {"kind": "out", "text": "ok", "time": t("8:22 am"), "status": "Delivered"},
]

WA_SAME_TWICE = [
    {"kind": "divider", "text": "TODAY"},
    {"kind": "in", "text": "Reached?", "time": "9:00 pm"},
    {"kind": "out", "text": "ok", "time": "9:02 pm", "status": "Read"},
    {"kind": "out", "text": "ok", "time": "9:02 pm", "status": "Read"},
    {"kind": "in", "text": "ok", "time": "9:03 pm"},
    {"kind": "in", "text": "ok", "time": "9:03 pm"},
]

WA_HINGLISH = [
    {"kind": "divider", "text": "TODAY"},
    {"kind": "in", "text": "Bhai kal ka plan kya hai? 😅", "time": "11:40 am"},
    {"kind": "out", "text": "Kal 6:30 tak aa jaunga, tension mat le", "time": "11:42 am", "status": "Read"},
    {"kind": "in", "text": "Acha theek hai 👍", "time": "11:43 am", "edited": True},
    {"kind": "in", "text": "Aur haan, Mummy ne bola ghar pe khana khake jana", "time": "11:43 am"},
    {"kind": "out", "text": "Haan haan pakka 🙏🏽", "time": "11:45 am", "status": "Read"},
    {"kind": "in", "text": "Kem cho? Majama?", "time": "11:50 am"},
    {"kind": "out", "text": "Majama! Tame kaho", "time": "11:51 am", "status": "Delivered"},
]

# Dates (issue #26). Captured Wednesday 2 December 2026, 11:30 in India.
WA_DATES_CAPTURED = "2026-12-02T06:00:00Z"
WA_DATES = [
    # Separator at the very top, in the long form WhatsApp uses for days over a week ago.
    {"kind": "divider", "text": "21 NOVEMBER 2026"},
    {"kind": "in", "text": "Did the parcel arrive?", "time": "6:40 pm"},
    {"kind": "out", "text": "Not yet, tomorrow maybe", "time": "6:45 pm", "status": "Read"},
    {"kind": "divider", "text": "THURSDAY"},
    {"kind": "in", "text": "It came! Thanks", "time": "9:05 am"},
    {"kind": "divider", "text": "YESTERDAY"},
    {"kind": "out", "text": "Are we still on for Friday dinner?", "time": "8:30 pm", "status": "Read"},
    # Today's divider inside the first row of the day; a reply that quotes my message.
    {"kind": "in", "text": "Yes, 8 pm works", "time": "10:12 am", "divider_inside": "TODAY",
     "quote": ("You", "Are we still on for Friday dinner?")},
    {"kind": "in", "text": "Book a table for four", "time": "10:13 am"},
]

# No separator on screen: every message has an unknown date.
WA_NO_SEPARATOR = [
    {"kind": "in", "text": "Are you home?", "time": "7:10 pm"},
    {"kind": "out", "text": "Reaching in 10", "time": "7:12 pm", "status": "Read"},
    {"kind": "in", "text": "ok, bring milk", "time": "7:13 pm"},
]

# Midnight rollover: captured at 00:05 on Sunday 22 November 2026 in India (18:35 UTC on the 21st).
WA_MIDNIGHT_CAPTURED = "2026-11-21T18:35:00Z"
WA_MIDNIGHT = [
    {"kind": "in", "text": "Still up?", "time": "11:52 pm"},
    {"kind": "divider", "text": "YESTERDAY"},
    {"kind": "out", "text": "Yes, watching the match", "time": "11:58 pm", "status": "Read"},
    {"kind": "divider", "text": "TODAY"},
    {"kind": "in", "text": "Happy birthday!! 🎂", "time": "12:01 am"},
    {"kind": "out", "text": "Thank you 😄", "time": "12:03 am", "status": "Read"},
]

# A chat kept open across midnight (S1 of the PR #27 review), captured at 00:05 on Sunday 22
# November 2026 in India, like WA_MIDNIGHT. WhatsApp does not redraw rows at midnight, so the divider
# drawn on the 21st still says "TODAY" above that evening's messages: a stale "Today".
WA_STALE_TODAY = [
    {"kind": "divider", "text": "TODAY"},
    {"kind": "in", "text": "Leaving the party now", "time": "11:48 pm"},
    {"kind": "out", "text": "Message when you reach", "time": "11:50 pm", "status": "Read"},
]

# The same chat after a message arrived past midnight: WhatsApp adds a new "TODAY" divider below the
# stale one, so the screen shows two "TODAY"s (separators out of order).
WA_TWO_TODAYS = WA_STALE_TODAY + [
    {"kind": "divider", "text": "TODAY"},
    {"kind": "in", "text": "Reached home 🏠", "time": "12:02 am"},
]

# WhatsApp's floating date pill read as a divider (S3 of the PR #27 review round 2): right after a
# scroll it shows the top messages' day over the inline divider of that same day, with no message
# between the two. Captured at CAPTURED (23:45 on 21 November in India).
WA_DATE_PILL = [
    {"kind": "divider", "text": "YESTERDAY"},
    {"kind": "divider", "text": "YESTERDAY"},
    {"kind": "in", "text": "Did you book the cab?", "time": "10:12 pm"},
    {"kind": "out", "text": "Yes, 6 am pickup", "time": "10:15 pm", "status": "Read"},
    {"kind": "divider", "text": "TODAY"},
    {"kind": "in", "text": "Cab is here 🚕", "time": "6:02 am"},
]

# Hinglish, with day-first numeric separators (WhatsApp Business, Indian English).
WA_NUMERIC = [
    {"kind": "divider", "text": "12/11/2026"},
    {"kind": "in", "text": "Order ka status kya hai?", "time": "4:10 pm"},
    {"kind": "out", "text": "Kal tak dispatch ho jayega ji", "time": "4:20 pm", "status": "Read"},
    {"kind": "divider", "text": "TODAY"},
    {"kind": "in", "text": "Bhaiya abhi tak nahi aaya 😕", "time": "10:05 am"},
]

# Hinglish chat on a phone set to Hindi: Hindi separators (Saturday 21 November 2026 in India).
WA_HINDI = [
    {"kind": "divider", "text": "बुधवार"},
    {"kind": "in", "text": "Kal milte hai na?", "time": "8:15 pm"},
    {"kind": "divider", "text": "कल"},
    {"kind": "out", "text": "Haan pakka, 7 baje", "time": "6:50 pm", "status": "Read"},
    {"kind": "divider", "text": "आज"},
    {"kind": "in", "text": "Main nikal gaya 🚗", "time": "6:58 pm"},
]


def wa_chat_list(pkg):
    rows = []
    y = LIST_TOP + 150
    for name, last, when in [("Asha Patel", "Can you also pick up candles?", "10:08 am"),
                             ("Weekend Trek", "Asha: Done 👍", "8:25 am"), ("Dad", "ok", "Yesterday")]:
        rows.append(node("android.widget.RelativeLayout", b(0, y, 1080, y + 200), clickable=True, children=[
            node("android.widget.TextView", b(220, y + 30, 800, y + 90), text=name,
                 vid=f"{pkg}:id/conversations_row_contact_name"),
            node("android.widget.TextView", b(850, y + 30, 1040, y + 90), text=when,
                 vid=f"{pkg}:id/conversations_row_date"),
            node("android.widget.TextView", b(220, y + 100, 1040, y + 160), text=last,
                 vid=f"{pkg}:id/single_msg_tv"),
        ]))
        y += 200
    root = node("android.widget.FrameLayout", b(0, 0, W, H), children=[
        node("android.view.ViewGroup", b(0, 110, 1080, 257), vid=f"{pkg}:id/toolbar", children=[
            node("android.widget.TextView", b(40, 140, 500, 230), text="WhatsApp")]),
        node("android.widget.EditText", b(40, 270, 1040, 390), hint="Ask Meta AI or Search",
             vid=f"{pkg}:id/search_input", editable=True, clickable=True),
        node("androidx.recyclerview.widget.RecyclerView", b(0, 400, 1080, 2100), vid="android:id/list",
             scrollable=True, children=rows),
        node("android.widget.ImageButton", b(880, 1950, 1040, 2100), cd="New chat", vid=f"{pkg}:id/fab",
             clickable=True),
    ])
    return snapshot(pkg, root)


# ---------------------------------------------------------------- Telegram

TG = "org.telegram.messenger"


def tg_header(title, subtitle):
    return node("android.widget.FrameLayout", b(0, 110, 1080, 257), children=[
        node("android.widget.ImageView", b(0, 120, 110, 247), cd="Go back", clickable=True),
        node("android.widget.FrameLayout", b(110, 115, 850, 252), clickable=True, children=[
            node("android.view.View", b(130, 130, 240, 240)),
            node("android.view.View", b(260, 130, 850, 190), text=title),
            node("android.view.View", b(260, 192, 850, 240), text=subtitle),
        ]),
        node("android.widget.ImageView", b(960, 130, 1070, 240), cd="More options", clickable=True),
    ])


def tg_input():
    return node("android.widget.FrameLayout", b(0, 2060, 1080, 2214), children=[
        node("android.widget.ImageView", b(10, 2085, 120, 2190), cd="Emoji, stickers, and GIFs", clickable=True),
        node("android.widget.EditText", b(130, 2075, 820, 2195), hint="Message", editable=True, focused=True,
             clickable=True),
        node("android.widget.ImageView", b(830, 2085, 940, 2190), cd="Attach media", clickable=True),
        node("android.widget.ImageView", b(950, 2085, 1070, 2190), cd="Record voice message", clickable=True),
    ])


def tg_chat(title, subtitle, cells, captured=CAPTURED):
    """cells: ("msg", description) or ("action", text). Cells span the full width, like ChatMessageCell."""
    y = 262
    kids = []
    for kind, value in cells:
        lines = value.count("\n") + 1
        h = 60 + 56 * lines if kind == "msg" else 90
        if kind == "msg":
            kids.append(node("android.view.ViewGroup", b(0, y, 1080, y + h), cd=value, clickable=True))
        else:
            kids.append(node("android.view.View", b(0, y, 1080, y + h), text=value))
        y += h
    assert y <= 2060, f"{title}: overflow {y}"
    lst = node("androidx.recyclerview.widget.RecyclerView", b(0, 257, 1080, 2060), scrollable=True,
               children=kids)
    fragment = node("android.widget.FrameLayout", b(0, 0, W, H), children=[tg_header(title, subtitle), lst,
                                                                            tg_input()])
    return snapshot(TG, fragment, captured)


def rx(text, time):
    return f"{text}\nReceived today at {time}"


def sx(text, time, state="Read"):
    return f"{text}\nSent today at {time}, {state}"


TG_ONE_TO_ONE = [
    ("action", "Today"),
    ("msg", rx("Are you coming to Riya's birthday on Saturday?", "9:15 PM")),
    ("msg", sx("Yes! What time does it start?", "9:20 PM")),
    ("action", "Asha Patel joined Telegram"),
    ("msg", rx("Today", "10:02 AM")),
    ("msg", rx("7 pm at her place", "10:02 AM")),
    ("action", "Unread messages"),
    ("msg", sx("10:30", "10:06 AM", "Unread")),
    ("msg", rx("Can you also pick up candles?\nAnd a card", "10:08 AM")),
]

TG_GROUP = [
    ("action", "Today"),
    ("msg", rx("Asha\nGood morning all ☀️", "8:02 AM")),
    ("msg", rx("Asha\nTrek is on for Sunday?", "8:03 AM")),
    ("action", "Asha added Meera"),
    ("msg", rx("Meera\nHi everyone!\nThanks for adding me", "8:10 AM")),
    ("msg", sx("Welcome Meera! 🎉", "8:12 AM")),
    ("msg", rx("Ravi\nForwarded from Trek Club\nTrek checklist: water, shoes, cap", "8:20 AM")),
    ("msg", rx("Ravi\nok", "8:21 AM")),
    ("msg", sx("ok", "8:22 AM", "Unread")),
]

TG_SAME_TWICE = [
    ("action", "Today"),
    ("msg", rx("Reached?", "9:00 PM")),
    ("msg", sx("ok", "9:02 PM")),
    ("msg", sx("ok", "9:02 PM")),
    ("msg", rx("ok", "9:03 PM")),
    ("msg", rx("ok", "9:03 PM")),
]

TG_HINGLISH = [
    ("action", "Today"),
    ("msg", rx("Bhai kal ka plan kya hai? 😅", "11:40 AM")),
    ("msg", sx("Kal 6:30 tak aa jaunga, tension mat le", "11:42 AM")),
    ("msg", rx("Acha theek hai 👍\nAur haan, Mummy ne bola ghar pe khana khake jana", "11:43 AM")),
    ("msg", sx("Haan haan pakka 🙏🏽", "11:45 AM")),
    ("msg", rx("Kem cho? Majama?", "11:50 AM")),
    ("msg", sx("Majama! Tame kaho", "11:51 AM", "Unread")),
]


# Dates (issue #26): every status line names its day; header cells sit between days. Captured on
# Sunday 22 November 2026, 11:00 in India.
TG_DATES_CAPTURED = "2026-11-22T05:30:00Z"
TG_DATES = [
    ("action", "November 21, 2025"),
    ("msg", "Happy birthday Rohan!\nReceived 21.11.25 at 6:02 PM"),
    ("action", "November 19"),
    ("msg", "Did you get the tickets?\nReceived Nov 19 at 7:40 PM"),
    ("msg", "Yes, 2 for Saturday\nSent Nov 19 at 7:45 PM, Read"),
    ("action", "Yesterday"),
    ("msg", "See you at the gate\nSent yesterday at 9:15 PM, Read"),
    ("action", "Today"),
    ("msg", rx("Movie was great 🍿", "10:45 AM")),
]

# Header cells only: the status lines name no day, so each cell takes the header's date above it.
TG_HEADERS_ONLY = [
    ("msg", "Top cell above any header\nReceived at 9:58 PM"),
    ("action", "Saturday"),
    ("msg", "Dinner at 9?\nReceived at 8:01 PM"),
    ("action", "Today"),
    ("msg", "Done 👍\nSent at 10:40 AM, Read"),
]


def tg_geometry_fallback():
    """Cells exposed as bubble-sized views with child text (no Sent/Received description)."""
    y = 300
    kids = []

    def bubble(side, text, time, status=None):
        nonlocal y
        width = min(820, 80 + 24 * len(text) + 170)
        left, right = (40, 40 + width) if side == "in" else (1040 - width, 1040)
        cell = [
            node("android.widget.TextView", b(left + 30, y + 20, right - 200, y + 76), text=text),
            node("android.widget.TextView", b(right - 190, y + 80, right - 70, y + 120), text=time),
        ]
        if status:
            cell.append(node("android.widget.ImageView", b(right - 65, y + 80, right - 20, y + 120), cd=status))
        kids.append(node("android.view.ViewGroup", b(left, y, right, y + 140), clickable=True, children=cell))
        y += 156

    kids.append(node("android.view.View", b(440, y, 640, y + 70), text="Today"))
    y += 90
    bubble("in", "Are you free tonight?", "7:01 PM")
    bubble("out", "Yes, after 8", "7:03 PM", "Read")
    kids.append(node("android.view.View", b(0, y, 1080, y + 70), text="Unread messages"))
    y += 90
    bubble("in", "Dinner at 9?", "7:05 PM")
    bubble("in", "9:30", "7:05 PM")
    bubble("out", "Done 👍", "7:06 PM", "Unread")
    lst = node("androidx.recyclerview.widget.RecyclerView", b(0, 257, 1080, 2060), scrollable=True,
               children=kids)
    root = node("android.widget.FrameLayout", b(0, 0, W, H),
                children=[tg_header("Asha Patel", "last seen recently"), lst, tg_input()])
    return snapshot(TG, root)


# ---------------------------------------------------------------- Generic

def instagram_dm():
    pkg = "com.instagram.android"
    y = 400
    kids = [node("android.widget.TextView", b(300, y, 780, y + 60), text="Today 6:40 PM")]
    y += 90

    def bubble(side, text):
        nonlocal y
        width = min(800, 80 + 26 * len(text))
        left, right = (150, 150 + width) if side == "in" else (1040 - width, 1040)
        kids.append(node("android.view.ViewGroup", b(0, y, 1080, y + 130), children=[
            node("android.widget.FrameLayout", b(left, y + 10, right, y + 120), children=[
                node("android.widget.TextView", b(left + 40, y + 35, right - 40, y + 95), text=text,
                     vid=f"{pkg}:id/direct_text_message_text_view")])]))
        y += 140

    bubble("in", "hey are you around?")
    bubble("out", "yes, what's up")
    bubble("in", "can you send the notes")
    bubble("out", "sending now")
    kids.append(node("android.widget.TextView", b(920, y, 1040, y + 50), text="Seen"))
    lst = node("androidx.recyclerview.widget.RecyclerView", b(0, 257, 1080, 2050), scrollable=True,
               children=kids)
    header = node("android.view.ViewGroup", b(0, 110, 1080, 257), children=[
        node("android.widget.ImageView", b(0, 120, 110, 247), cd="Back", clickable=True),
        node("android.widget.TextView", b(240, 125, 700, 185), text="asha.patel"),
        node("android.widget.TextView", b(240, 190, 700, 240), text="Active now"),
        node("android.widget.ImageView", b(850, 130, 950, 240), cd="Audio call", clickable=True),
    ])
    inp = node("android.widget.LinearLayout", b(0, 2060, 1080, 2214), children=[
        node("android.widget.EditText", b(140, 2080, 900, 2190), hint="Message...", editable=True, focused=True)])
    return snapshot(pkg, node("android.widget.FrameLayout", b(0, 0, W, H), children=[header, lst, inp]))


def generic_feed():
    pkg = "com.example.news"
    return snapshot(pkg, node("android.widget.FrameLayout", b(0, 0, W, H), children=[
        node("android.widget.TextView", b(40, 140, 600, 230), text="Top stories"),
        node("androidx.recyclerview.widget.RecyclerView", b(0, 257, 1080, 2214), scrollable=True, children=[
            node("android.widget.TextView", b(40, 300, 1040, 420), text="Monsoon arrives early this year"),
            node("android.widget.TextView", b(40, 440, 1040, 560), text="Markets close higher"),
        ]),
    ]))


# ---------------------------------------------------------------- X (issue #30)
#
# Every view id below is a guess (parser/x/XIds.kt) until real dumps come back
# (DEVICE_CHECKLIST 15). Handles are obviously fake; the text is made up.

XPKG = "com.twitter.android"


class X:
    """Builds X screens. With ids=False every view id is left out (the geometry route); with
    joined=True a compact header is one node: "Name @handle · 2h"."""

    def __init__(self, ids=True, joined=False):
        self.ids = ids
        self.joined = joined

    def vid(self, name):
        return f"{XPKG}:id/{name}" if self.ids else None

    def text(self, l, t, r, bot, text, vid=None, **flags):
        return node("android.widget.TextView", b(l, t, r, bot), text=text, vid=self.vid(vid) if vid else None, **flags)

    def header(self, x, y, name, handle, time):
        """A compact header row at (x, y): name, @handle, "· time". Returns nodes."""
        if self.joined:
            line = f"{name} @{handle} · {time}"
            return [self.text(x, y, min(1040, x + 22 * len(line)), y + 55, line)]
        nw = 22 * len(name)
        hw = 20 * (len(handle) + 1)
        return [
            self.text(x, y, x + nw, y + 55, name, "tweet_header_name"),
            self.text(x + nw + 12, y, x + nw + 12 + hw, y + 55, "@" + handle, "tweet_header_username"),
            self.text(x + nw + hw + 24, y, x + nw + hw + 24 + 110, y + 55, "· " + time, "tweet_header_timestamp"),
        ]

    def lines(self, text):
        return max(1, math.ceil(len(text) / 38)) + text.count("\n")

    def body(self, x, y, text, vid="tweet_content_text"):
        h = 55 * self.lines(text)
        return self.text(x, y, 1040, y + h, text, vid), y + h

    def actions(self, x, y, counts=("3", "12", "48")):
        kids = []
        for i, (label, count) in enumerate(zip(("Reply", "Repost", "Like"), counts)):
            left = x + i * 220
            kids.append(node("android.widget.ImageButton", b(left, y, left + 70, y + 70), cd=label, clickable=True))
            kids.append(self.text(left + 75, y + 8, left + 160, y + 62, count))
        kids.append(node("android.widget.ImageButton", b(960, y, 1030, y + 70), cd="Share", clickable=True))
        return kids, y + 80

    def media(self, x, y, cd):
        return node("android.widget.ImageView", b(x, y, 1040, y + 420), cd=cd, vid=self.vid("tweet_media")), y + 440

    def card(self, x, y, title, domain):
        kids = [
            node("android.widget.ImageView", b(x, y, 1040, y + 300)),
            self.text(x + 20, y + 310, 1020, y + 365, title, "card_title"),
            self.text(x + 20, y + 370, 1020, y + 420, domain, "card_domain"),
        ]
        return node("android.view.ViewGroup", b(x, y, 1040, y + 430), clickable=True, children=kids), y + 450

    def quote_card(self, x, y, name, handle, time, text):
        hx = x + 100
        kids = [node("android.widget.ImageView", b(x + 30, y + 20, x + 80, y + 70), cd=name)]
        kids += self.header(hx, y + 18, name, handle, time)
        t_node, end = self.body(x + 30, y + 85, text)
        kids.append(t_node)
        container = node("android.view.ViewGroup", b(x, y, 1040, end + 20), vid=self.vid("quote_tweet_view"),
                         clickable=True, children=kids)
        return container, end + 40

    def post(self, y, name, handle, time, text, replying=None, alts=(), card=None, quote=None, counts=None):
        """A compact post row. Returns (row, bottom)."""
        x = 180
        kids = [node("android.widget.ImageView", b(40, y + 20, 150, y + 130), cd=name, clickable=True)]
        kids += self.header(x, y + 20, name, handle, time)
        cy = y + 85
        if replying:
            kids.append(self.text(x, cy, 1040, cy + 50, replying, "reply_context_text"))
            cy += 60
        if text:
            t_node, cy = self.body(x, cy, text)
            kids.append(t_node)
        for alt in alts:
            m, cy = self.media(x, cy + 10, alt)
            kids.append(m)
        if card:
            c, cy = self.card(x, cy + 10, *card)
            kids.append(c)
        if quote:
            q, cy = self.quote_card(x, cy + 10, *quote)
            kids.append(q)
        acts, cy = self.actions(x, cy + 10, counts or ("3", "12", "48"))
        kids += acts
        return node("android.view.ViewGroup", b(0, y, 1080, cy + 10), vid=self.vid("row"), children=kids), cy + 20

    def focal(self, y, name, handle, text, absolute, alts=()):
        """The detail view's focal post: name above handle, full-width text, absolute time line."""
        kids = [node("android.widget.ImageView", b(40, y + 20, 150, y + 130), cd=name, clickable=True),
                self.text(180, y + 25, 180 + 22 * len(name), y + 80, name, "tweet_header_name"),
                self.text(180, y + 85, 180 + 20 * (len(handle) + 1), y + 135, "@" + handle, "tweet_header_username"),
                node("android.widget.Button", b(840, y + 40, 1040, y + 110), text="Follow", clickable=True)]
        t_node, cy = self.body(40, y + 160, text)
        kids.append(t_node)
        for alt in alts:
            m, cy = self.media(40, cy + 10, alt)
            kids.append(m)
        kids.append(self.text(40, cy + 20, 1040, cy + 75, absolute, "tweet_date"))
        cy += 90
        kids.append(self.text(40, cy, 300, cy + 55, "12 Reposts"))
        kids.append(self.text(320, cy, 600, cy + 55, "48 Likes"))
        acts, cy = self.actions(40, cy + 70, ("", "", ""))
        kids += [k for k in acts if k.get("text", None) != ""]
        return node("android.view.ViewGroup", b(0, y, 1080, cy + 10), vid=self.vid("row"), children=kids), cy + 20

    def toolbar(self, title=None, left="Navigate up", right=None):
        kids = [node("android.widget.ImageButton", b(0, 120, 110, 247), cd=left, clickable=True)]
        if title:
            kids.append(self.text(150, 150, 500, 215, title))
        if right:
            kids.append(node("android.widget.Button", b(840, 140, 1040, 225), text=right, clickable=True,
                             vid=self.vid("button_tweet")))
        return node("android.view.ViewGroup", b(0, 110, 1080, 257), children=kids)

    def composer_row(self, y, hint, draft=None, account=None, replying=None, vid="tweet_text"):
        """Optional "Replying to" line, then my avatar and the focused field."""
        kids = []
        if replying:
            kids.append(self.text(180, y, 1040, y + 50, replying, "reply_context_text"))
            y += 60
        kids.append(node("android.widget.ImageView", b(40, y + 10, 150, y + 120), cd=account or "Profile picture",
                         vid=self.vid("composer_avatar")))
        kids.append(node("android.widget.EditText", b(180, y, 1040, y + 130), text=draft if draft else hint,
                         hint=hint, vid=self.vid(vid), editable=True, focused=True, clickable=True))
        return kids, y + 140

    def screen(self, kids, captured=CAPTURED):
        return snapshot(XPKG, node("android.widget.FrameLayout", b(0, 0, W, H), children=kids), captured)

    def stack(self, y, items):
        """Lays out [(kind, args...)] from y; returns rows and the bottom."""
        rows = []
        for kind, kw in items:
            if kind == "post":
                row, y = self.post(y, **kw)
            else:
                row, y = self.focal(y, **kw)
            rows.append(row)
        return rows, y


X_THREAD = [
    ("post", dict(name="Ravi K", handle="ravi_k", time="5h",
                  text="Shipping the offline mode this week. Small teams can still move fast.",
                  quote=("Asha Dev", "asha_dev", "Nov 19", "What is the one feature you would ship first?"))),
    ("post", dict(name="Asha Dev", handle="asha_dev", time="4h", replying="Replying to @ravi_k",
                  text="Offline first is underrated. How are you handling sync conflicts?")),
    ("focal", dict(name="Meera Iyer", handle="meera_writes",
                   text="We tried last-write-wins and regretted it. Per-field merge was worth the extra week.",
                   absolute="6:42 PM · 21 Nov 26 · 1,204 Views")),
    ("post", dict(name="Dev N", handle="dev_null_test", time="1h", replying="Replying to @meera_writes",
                  text="Same here, CRDTs saved us.")),
]


def x_reply_thread(x):
    rows, y = x.stack(270, X_THREAD)
    lst = node("androidx.recyclerview.widget.RecyclerView", b(0, 257, 1080, 1960), scrollable=True, children=rows)
    comp, _ = x.composer_row(1980, "Post your reply", draft="Per-field merge is the way",
                             replying="Replying to @meera_writes")
    return x.screen([x.toolbar("Post"), lst, node("android.view.ViewGroup", b(0, 1970, 1080, 2200), children=comp)])


def x_reply_composer_image(x):
    row, y = x.post(270, name="Asha Dev", handle="asha_dev", time="2h",
                    text="Sketched the new data flow on the office whiteboard",
                    alts=("A whiteboard with boxes for screen, parser and panel joined by arrows", "Image"))
    comp, _ = x.composer_row(y + 10, "Post your reply", replying="Replying to @asha_dev")
    return x.screen([x.toolbar(left="Cancel", right="Reply"), row] + comp)


X_OWN = [
    ("post", dict(name="Kiran", handle="kiran_test", time="3h",
                  text="Hot take: most apps do not need a settings screen with 40 toggles.")),
    ("post", dict(name="Ravi K", handle="ravi_k", time="2h", replying="Replying to @kiran_test",
                  text="Disagree, power users want control.")),
]


def x_reply_own_post(x):
    rows, y = x.stack(270, X_OWN)
    comp, _ = x.composer_row(y + 10, "Post your reply", account="Account @kiran_test", replying="Replying to @ravi_k")
    return x.screen([x.toolbar(left="Cancel", right="Reply")] + rows + comp)


def x_reply_link_card_no_line(x):
    row, y = x.post(270, name="Dev N", handle="dev_null_test", time="Nov 19", text="Good read on accessibility trees",
                    card=("How screen readers see your app", "From example.com"))
    comp, _ = x.composer_row(y + 10, "Post your reply")
    return x.screen([x.toolbar(left="Cancel", right="Reply"), row] + comp)


def x_quote_composer(x):
    comp, y = x.composer_row(280, "Add a comment", draft="This matches what we saw")
    quoted, _ = x.quote_card(180, y + 20, "Meera Iyer", "meera_writes", "1h",
                             "Per-field merge was worth the extra week.")
    return x.screen([x.toolbar(left="Cancel", right="Post")] + comp + [quoted])


def x_timeline(x):
    rows, y = x.stack(400, X_OWN)
    tabs = node("android.view.ViewGroup", b(0, 257, 1080, 380), children=[
        x.text(200, 290, 450, 350, "For you"), x.text(630, 290, 880, 350, "Following")])
    lst = node("androidx.recyclerview.widget.RecyclerView", b(0, 380, 1080, 2100), scrollable=True, children=rows)
    fab = node("android.widget.ImageButton", b(880, 1900, 1040, 2060), cd="New post", clickable=True)
    return x.screen([x.toolbar(left="Show navigation drawer"), tabs, lst, fab])


def x_post_detail(x):
    rows, y = x.stack(270, X_THREAD[2:])
    lst = node("androidx.recyclerview.widget.RecyclerView", b(0, 257, 1080, 2050), scrollable=True, children=rows)
    bar = x.text(180, 2080, 1040, 2180, "Post your reply", clickable=True)
    return x.screen([x.toolbar("Post"), lst, bar])


def x_search(x):
    field = node("android.widget.EditText", b(150, 130, 1040, 235), text="Search", hint="Search",
                 vid=x.vid("query"), editable=True, focused=True)
    rows, _ = x.stack(300, X_OWN)
    return x.screen([field] + rows)


def x_new_post(x):
    comp, _ = x.composer_row(280, "What’s happening?", draft="Thinking about")
    return x.screen([x.toolbar(left="Cancel", right="Post")] + comp)


def x_dm(x):
    def bubble(y, side, text):
        width = min(620, 60 + 18 * len(text))
        left, right = (150, 150 + width) if side == "in" else (1040 - width, 1040)
        return node("android.view.ViewGroup", b(0, y, 1080, y + 130), children=[
            node("android.widget.FrameLayout", b(left, y + 10, right, y + 120), children=[
                x.text(left + 40, y + 35, right - 40, y + 95, text, "dm_message_text")])])
    lst = node("androidx.recyclerview.widget.RecyclerView", b(0, 257, 1080, 2040), scrollable=True, children=[
        bubble(400, "in", "are you coming to the meetup?"), bubble(560, "out", "yes, see you there"),
        bubble(720, "in", "can you bring the demo phone")])
    header = node("android.view.ViewGroup", b(0, 110, 1080, 257), children=[
        node("android.widget.ImageButton", b(0, 120, 110, 247), cd="Navigate up", clickable=True),
        x.text(240, 125, 700, 185, "Asha Dev"), x.text(240, 190, 700, 240, "@asha_dev")])
    field = node("android.widget.EditText", b(140, 2060, 900, 2190), text="Start a message", hint="Start a message",
                 vid=x.vid("dm_composer_edit_text"), editable=True, focused=True)
    return x.screen([header, lst, field])


def write_x(out_dir):
    ids, bare, joined = X(), X(ids=False), X(ids=False, joined=True)
    write(out_dir, "x/synthetic-reply_thread.json", x_reply_thread(ids))
    write(out_dir, "x/synthetic-reply_thread-no_ids.json", x_reply_thread(joined))
    write(out_dir, "x/synthetic-reply_composer-image.json", x_reply_composer_image(ids))
    write(out_dir, "x/synthetic-reply_composer-image-no_ids.json", x_reply_composer_image(bare))
    write(out_dir, "x/synthetic-reply_own_post.json", x_reply_own_post(ids))
    write(out_dir, "x/synthetic-reply_link_card-no_replying_line.json", x_reply_link_card_no_line(ids))
    write(out_dir, "x/synthetic-quote_composer.json", x_quote_composer(ids))
    write(out_dir, "x/synthetic-quote_composer-no_ids.json", x_quote_composer(bare))
    write(out_dir, "x/synthetic-timeline.json", x_timeline(ids))
    write(out_dir, "x/synthetic-post_detail.json", x_post_detail(ids))
    write(out_dir, "x/synthetic-search.json", x_search(ids))
    write(out_dir, "x/synthetic-new_post.json", x_new_post(ids))
    write(out_dir, "x/synthetic-dm.json", x_dm(ids))


def write(out_dir, rel, snap):
    path = os.path.join(out_dir, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(snap, ensure_ascii=False, indent=2))
        f.write("\n")


def main(out_dir):
    wa = Wa("com.whatsapp")
    bare = Wa("com.whatsapp", ids=False)
    w4b = Wa("com.whatsapp.w4b")
    write(out_dir, "whatsapp/synthetic-one_to_one.json",
          wa.chat("Asha Patel", "online", WA_ONE_TO_ONE, 262, draft="Sure, I",
                  composer_quote=("Asha Patel", "Can you also pick up candles?"), fab="1"))
    write(out_dir, "whatsapp/synthetic-one_to_one-no_ids.json",
          bare.chat("Asha Patel", "online", WA_ONE_TO_ONE, 262, draft="Sure, I",
                    composer_quote=("Asha Patel", "Can you also pick up candles?"), fab="1"))
    group_sub = "Asha, Meera, Ravi, +91 98765 43210, You"
    write(out_dir, "whatsapp/synthetic-group.json", wa.chat("Weekend Trek", group_sub, WA_GROUP, 200, group=True))
    write(out_dir, "whatsapp/synthetic-group-no_ids.json",
          bare.chat("Weekend Trek", group_sub, WA_GROUP, 200, group=True))
    write(out_dir, "whatsapp/synthetic-same_text_twice.json",
          wa.chat("Dad", "last seen today at 9:01 pm", WA_SAME_TWICE, 900))
    write(out_dir, "whatsapp/synthetic-hinglish.json", wa.chat("Rohan Bhai", "typing…", WA_HINGLISH, 600))
    write(out_dir, "whatsapp/synthetic-chat_list.json", wa_chat_list("com.whatsapp"))
    write(out_dir, "whatsapp/synthetic-dates.json",
          wa.chat("Asha Patel", "online", WA_DATES, 262, captured=WA_DATES_CAPTURED))
    write(out_dir, "whatsapp/synthetic-dates-no_ids.json",
          bare.chat("Asha Patel", "online", WA_DATES, 262, captured=WA_DATES_CAPTURED))
    write(out_dir, "whatsapp/synthetic-no_separator.json", wa.chat("Dad", "online", WA_NO_SEPARATOR, 1400))
    write(out_dir, "whatsapp/synthetic-midnight.json",
          wa.chat("Meera", "online", WA_MIDNIGHT, 900, captured=WA_MIDNIGHT_CAPTURED))
    write(out_dir, "whatsapp/synthetic-stale_today.json",
          wa.chat("Meera", "online", WA_STALE_TODAY, 1400, captured=WA_MIDNIGHT_CAPTURED))
    write(out_dir, "whatsapp/synthetic-two_todays.json",
          wa.chat("Meera", "online", WA_TWO_TODAYS, 1200, captured=WA_MIDNIGHT_CAPTURED))
    write(out_dir, "whatsapp/synthetic-date_pill.json", wa.chat("Meera", "online", WA_DATE_PILL, 900))
    write(out_dir, "whatsapp/synthetic-date_pill-no_ids.json", bare.chat("Meera", "online", WA_DATE_PILL, 900))
    write(out_dir, "whatsapp/synthetic-dates-hindi.json", wa.chat("Rohan Bhai", "ऑनलाइन", WA_HINDI, 900))
    write(out_dir, "whatsapp_business/synthetic-dates-numeric.json",
          w4b.chat("Sharma Electronics", "Business account", WA_NUMERIC, 900))
    write(out_dir, "whatsapp_business/synthetic-hinglish.json",
          w4b.chat("Rohan Bhai", None, WA_HINGLISH, 600))
    # Business shares WhatsApp's ids under its own package: same screens, other package.
    write(out_dir, "whatsapp_business/synthetic-group.json",
          w4b.chat("Weekend Trek", group_sub, WA_GROUP, 200, group=True))
    write(out_dir, "whatsapp_business/synthetic-same_text_twice.json",
          w4b.chat("Dad", "last seen today at 9:01 pm", WA_SAME_TWICE, 900))
    write(out_dir, "telegram/synthetic-one_to_one.json", tg_chat("Asha Patel", "online", TG_ONE_TO_ONE))
    write(out_dir, "telegram/synthetic-group.json", tg_chat("Weekend Trek", "5 members, 2 online", TG_GROUP))
    write(out_dir, "telegram/synthetic-same_text_twice.json",
          tg_chat("Dad", "last seen recently", TG_SAME_TWICE))
    write(out_dir, "telegram/synthetic-hinglish.json", tg_chat("Rohan Bhai", "typing...", TG_HINGLISH))
    write(out_dir, "telegram/synthetic-geometry_fallback.json", tg_geometry_fallback())
    write(out_dir, "telegram/synthetic-dates.json",
          tg_chat("Rohan Bhai", "last seen recently", TG_DATES, TG_DATES_CAPTURED))
    write(out_dir, "telegram/synthetic-dates-headers_only.json",
          tg_chat("Asha Patel", "online", TG_HEADERS_ONLY, TG_DATES_CAPTURED))
    write(out_dir, "generic/synthetic-instagram_dm.json", instagram_dm())
    write(out_dir, "generic/synthetic-not_a_chat.json", generic_feed())
    write_x(out_dir)


if __name__ == "__main__":
    main(sys.argv[1])
