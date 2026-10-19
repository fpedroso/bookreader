# -*- coding: utf-8 -*-
"""Quantify pypinyin's real failure modes so we know what needs a correction layer."""
import jieba
from pypinyin import lazy_pinyin, Style

# Hand-verified standard Mandarin, incl. neutral-tone (轻声) second syllables.
EXPECTED = {
    "爸爸": "bà ba", "妈妈": "mā ma", "姐姐": "jiě jie", "哥哥": "gē ge",
    "弟弟": "dì di", "妹妹": "mèi mei", "爷爷": "yé ye", "奶奶": "nǎi nai",
    "谢谢": "xiè xie", "朋友": "péng you", "时候": "shí hou",
    "喜欢": "xǐ huan", "认识": "rèn shi", "便宜": "pián yi", "客气": "kè qi",
    "明白": "míng bai", "东西": "dōng xi", "休息": "xiū xi",
    # control group: NOT neutral tone, must not be 'corrected' wrongly
    "银行": "yín háng", "中心": "zhōng xīn", "学习": "xué xí",
    "工作": "gōng zuò", "汉语": "hàn yǔ", "学校": "xué xiào",
}

print("=== A. neutral tone (轻声) handling: pypinyin default ===")
bad = []
for w, want in EXPECTED.items():
    got = " ".join(lazy_pinyin(w, style=Style.TONE))
    ok = (got == want)
    if not ok:
        bad.append(w)
    print(f"  {'OK ' if ok else 'BAD'}  {w:<4} got={got:<14} want={want}")
print(f"\n  -> {len(bad)}/{len(EXPECTED)} wrong: {' '.join(bad)}")

print("\n=== B. does segmentation + per-word change anything? ===")
for w in ["爸爸", "妈妈", "谢谢"]:
    whole = " ".join(lazy_pinyin(w, style=Style.TONE))
    seg = " ".join(" ".join(lazy_pinyin(t, style=Style.TONE))
                   for t in jieba.cut(w) if t.strip())
    print(f"  {w}: whole={whole!r} segmented={seg!r}")

print("\n=== C. traditional Chinese: does disambiguation survive? ===")
cases = [("银行", "銀行"), ("中心", "中心"), ("头发", "頭髮"), ("觉得", "覺得")]
for simp, trad in cases:
    a = " ".join(lazy_pinyin(simp, style=Style.TONE))
    b = " ".join(lazy_pinyin(trad, style=Style.TONE))
    # per-word, after segmentation
    seg = " ".join(" ".join(lazy_pinyin(t, style=Style.TONE))
                   for t in jieba.cut(trad) if t.strip())
    print(f"  {simp}->{a:<12} | {trad}->{b:<12} | segmented->{seg}")
