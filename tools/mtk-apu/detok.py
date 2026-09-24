"""Detokenise the ids of a probe e2eqc/e2e result: python detok.py <tag> [<tag>...]"""
import json, os, sys
from transformers import WhisperTokenizer
tok = WhisperTokenizer.from_pretrained(os.path.expanduser("~/mtk-whisper/models/whisper-large-v3-turbo"))
for tag in sys.argv[1:]:
    p = os.path.expanduser(f"~/.androidbuild/probe-logs/{tag}.json")
    r = json.load(open(p))
    print("==", tag, "ok" if r.get("ok") else "FAIL " + str(r.get("error")))
    for u in r.get("utterances", []):
        ids = u.get("ids", [])
        print(f"  utt {u.get(chr(105)+chr(110)+chr(100)+chr(101)+chr(120))} {u.get(chr(109)+chr(101)+chr(108))}: tokens={len(ids)} eot={u.get(chr(104)+chr(105)+chr(116)+chr(95)+chr(101)+chr(111)+chr(116))} encode={u.get(chr(101)+chr(110)+chr(99)+chr(111)+chr(100)+chr(101)+chr(95)+chr(109)+chr(115)):.0f}ms step={u.get(chr(115)+chr(116)+chr(101)+chr(112)+chr(95)+chr(109)+chr(115)+chr(95)+chr(109)+chr(101)+chr(97)+chr(110))}ms copy={u.get(chr(99)+chr(97)+chr(99)+chr(104)+chr(101)+chr(95)+chr(99)+chr(111)+chr(112)+chr(121)+chr(95)+chr(109)+chr(115)+chr(95)+chr(109)+chr(101)+chr(97)+chr(110))}ms")
        print("     ", repr(tok.decode(ids, skip_special_tokens=True)))
