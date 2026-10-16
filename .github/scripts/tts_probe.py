#!/usr/bin/env python3
"""Temporary diagnostic: sends subtitle lines to the Edge TTS server one by one, exactly like
the app's "Edge TTS Dub" button does, and reports which requests fail and why."""
import json
import os
import time

import requests

URL = os.environ.get("TTS_URL", "https://takumi0002-tts-kh.hf.space").rstrip("/") + "/api/process-multi"

LINES = [
    "សួស្តី តើអ្នកសុខសប្បាយជាទេ?",
    "ខ្ញុំសុខសប្បាយទេ អរគុណច្រើន។",
    "ថ្ងៃនេះអាកាសធាតុល្អណាស់។",
    "យើងត្រូវចេញដំណើរឥឡូវនេះ។",
    "កុំភ័យខ្លាចអី ខ្ញុំនៅទីនេះជាមួយអ្នក។",
    "តើអ្នកឃើញគាត់នៅឯណា?",
    "ខ្ញុំមិនដឹងទេ ប្រហែលជាគាត់ទៅផ្ទះហើយ។",
    "ចាំបន្តិច ខ្ញុំនឹងមកវិញភ្លាម។",
    "នេះជារឿងសំខាន់ណាស់សម្រាប់ពួកយើងទាំងអស់គ្នា។",
    "ហេតុអ្វីបានជាអ្នកមិនប្រាប់ខ្ញុំតាំងពីដំបូង?",
    "ខ្ញុំសុំទោស ខ្ញុំមិនចង់ធ្វើឱ្យអ្នកព្រួយបារម្ភទេ។",
    "ឆាប់ឡើង! ពួកគេកំពុងមកដល់ហើយ!",
    "យើងនឹងជួបគ្នានៅម៉ោងប្រាំពីរល្ងាច។",
    "ផ្លូវនេះវែងណាស់ ប៉ុន្តែយើងត្រូវតែទៅ។",
    "តើអ្នកចង់ញ៉ាំអ្វីសម្រាប់អាហារពេលល្ងាច?",
    "ខ្ញុំជឿជាក់លើអ្នកជានិច្ច។",
    "សូមអរគុណសម្រាប់ជំនួយរបស់អ្នក។",
    "គាត់ជាមនុស្សល្អ ហើយចិត្តល្អណាស់។",
    "យើងមិនអាចបោះបង់នៅពេលនេះបានទេ។",
    "ស្តាប់ខ្ញុំ! រឿងនេះមិនដូចអ្វីដែលអ្នកគិតទេ។",
    "ពេលវេលាកន្លងផុតទៅលឿនណាស់។",
    "ខ្ញុំនឹងការពារអ្នកដោយអស់ពីកម្លាំង។",
    "តើនរណាជាអ្នកធ្វើរឿងនេះ?",
    "ទៅ! ទៅឱ្យឆាប់ មុនពេលវាយឺតពេល!",
    "អ្វីៗនឹងល្អប្រសើរឡើងវិញ។",
    "ខ្ញុំចាំថ្ងៃនោះបានយ៉ាងច្បាស់ ដូចជាវាទើបតែកើតឡើងកាលពីម្សិលមិញ។",
    "OK ខ្ញុំយល់ហើយ។",
    "លេខ 15 និង 2026",
    "ទោះបីជាមានរឿងអ្វីកើតឡើងក៏ដោយ ខ្ញុំនឹងមិនចាកចេញពីអ្នកឡើយ ព្រោះអ្នកជាមនុស្សសំខាន់បំផុតក្នុងជីវិតរបស់ខ្ញុំ។",
    "លាហើយ មិត្តសម្លាញ់។",
]
EDGE_CASES = ["♪♪♪", "...", "(សើច)"]


def speed_for(text):
    n = len(text.replace(" ", ""))
    return 1.5 if n >= 60 else 1.4 if n >= 50 else 1.25 if n >= 35 else 1.15 if n >= 20 else 1.1 if n >= 10 else 1.0


def post(text, voice):
    data = json.dumps([{"text": text, "voiceName": voice, "speed": speed_for(text), "pitch": 0}], ensure_ascii=False)
    t0 = time.time()
    try:
        r = requests.post(URL, files={"data": (None, data), "autoSpeed": (None, "false")}, timeout=90)
        body = r.content
        ok = r.status_code == 200 and len(body) > 200
        note = "" if ok else body[:160].decode("utf-8", "replace").replace("\n", " ")
        extra = " retry-after=%s" % r.headers["retry-after"] if "retry-after" in r.headers else ""
        return ok, "%s %6d B %5.1fs%s %s" % (r.status_code, len(body), time.time() - t0, extra, note)
    except Exception as e:  # noqa: BLE001
        return False, "EXC %5.1fs %s" % (time.time() - t0, str(e)[:160])


def main():
    rows, fails = [], []
    start = time.time()
    for i, text in enumerate(LINES, start=1):
        ok, info = post(text, "sreymom" if i % 2 else "piseth")
        rows.append("%2d %s %s" % (i, "OK " if ok else "BAD", info))
        if not ok:
            fails.append(i)
        print(rows[-1], flush=True)
    for j, text in enumerate(EDGE_CASES, start=1):
        ok, info = post(text, "sreymom")
        rows.append("E%d %r %s %s" % (j, text, "OK " if ok else "BAD", info))
        print(rows[-1], flush=True)
    summary = "%d/%d lines OK in %.0fs; failed lines: %s" % (
        len(LINES) - len(fails), len(LINES), time.time() - start, fails or "none")
    print(summary)
    msg = summary + "%0A" + "%0A".join(r.replace("%", "%25") for r in rows)
    print("::notice title=TTS probe::" + msg[:4000])


if __name__ == "__main__":
    main()
