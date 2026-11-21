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
CAPTURED = "2026-11-21T10:15:30Z"
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


def snapshot(pkg, root):
    return {
        "formatVersion": 1,
        "packageName": pkg,
        "capturedAt": CAPTURED,
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

    def chat(self, title, subtitle, items, start_y, group=False, draft=None, composer_quote=None, fab=None):
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
        return snapshot(self.pkg, root)


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


def tg_chat(title, subtitle, cells):
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
    return snapshot(TG, fragment)


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
    write(out_dir, "whatsapp_business/synthetic-hinglish.json",
          w4b.chat("Rohan Bhai", None, WA_HINGLISH, 600))
    write(out_dir, "telegram/synthetic-one_to_one.json", tg_chat("Asha Patel", "online", TG_ONE_TO_ONE))
    write(out_dir, "telegram/synthetic-group.json", tg_chat("Weekend Trek", "5 members, 2 online", TG_GROUP))
    write(out_dir, "telegram/synthetic-same_text_twice.json",
          tg_chat("Dad", "last seen recently", TG_SAME_TWICE))
    write(out_dir, "telegram/synthetic-hinglish.json", tg_chat("Rohan Bhai", "typing...", TG_HINGLISH))
    write(out_dir, "telegram/synthetic-geometry_fallback.json", tg_geometry_fallback())
    write(out_dir, "generic/synthetic-instagram_dm.json", instagram_dm())
    write(out_dir, "generic/synthetic-not_a_chat.json", generic_feed())


if __name__ == "__main__":
    main(sys.argv[1])
