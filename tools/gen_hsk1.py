# -*- coding: utf-8 -*-
"""Generate a small HSK1-level Chinese EPUB as a test fixture.

Authored here from the HSK1 (150-word) vocabulary so the fixture is legally
clean and exactly levelled - real HSK1 graded readers are app/web-only, and
the free Chinese EPUBs on Gutenberg are Ming-dynasty vernacular.

Usage: gen_hsk1.py <out.epub>
"""
import os
import sys
import zipfile

TITLE = "我的家 — HSK 1 阅读练习"

PARAGRAPHS = [
    "我叫小明。我是中国人。我家有四个人。",
    "爸爸、妈妈、姐姐和我。我们都住在北京。",
    "爸爸是医生，他在医院工作。",
    "妈妈是老师，她在学校工作。她教汉语。",
    "姐姐是学生。她学习英语，她很喜欢看书。",
    "我每天上午八点去学校。中午我在学校吃饭。",
    "我喜欢吃米饭和菜，也喜欢喝茶。我不喝咖啡。",
    "今天天气很好。不冷也不热，我和朋友去商店买东西。",
    "我买了一个杯子和一本书。杯子五块钱，书十块钱。",
    "商店里的人很多。有的买东西，有的看东西。",
    "下午我看电视，也听音乐。晚上我写汉字。",
    "明天是星期六，我不去学校。我想去饭馆吃饭。",
    "我有一个朋友叫小李。他家有两只猫，一只狗。",
    "小李很忙，但是他很高兴。我们常常一起玩儿。",
    "现在晚上十点。我很累，我想睡觉。明天见！",
]

CSS = """body { margin: 1em; line-height: 1.7; }
h1 { font-size: 1.3em; }
p { margin: 0.8em 0; text-indent: 0; }
"""


def xhtml(title: str, body: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!DOCTYPE html>\n'
        '<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh">\n'
        f"<head><title>{title}</title>"
        '<link rel="stylesheet" type="text/css" href="style.css"/></head>\n'
        f"<body>{body}</body>\n</html>\n"
    )


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else "hsk1.epub"
    paras = "".join(f"<p>{p}</p>\n" for p in PARAGRAPHS)

    with zipfile.ZipFile(out, "w") as z:
        # mimetype must be first and stored uncompressed.
        z.writestr(
            zipfile.ZipInfo("mimetype"), "application/epub+zip",
            compress_type=zipfile.ZIP_STORED,
        )
        z.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?>\n'
            '<container version="1.0" '
            'xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n'
            '  <rootfiles><rootfile full-path="OEBPS/content.opf" '
            'media-type="application/oebps-package+xml"/></rootfiles>\n'
            "</container>\n",
        )
        z.writestr(
            "OEBPS/content.opf",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" '
            'unique-identifier="id" xml:lang="zh">\n'
            '  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">\n'
            f'    <dc:identifier id="id">urn:uuid:hsk1-fixture</dc:identifier>\n'
            f"    <dc:title>{TITLE}</dc:title>\n"
            "    <dc:language>zh</dc:language>\n"
            '    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>\n'
            "  </metadata>\n"
            "  <manifest>\n"
            '    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" '
            'properties="nav"/>\n'
            '    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>\n'
            '    <item id="css" href="style.css" media-type="text/css"/>\n'
            "  </manifest>\n"
            '  <spine><itemref idref="c1"/></spine>\n'
            "</package>\n",
        )
        z.writestr("OEBPS/style.css", CSS)
        z.writestr(
            "OEBPS/nav.xhtml",
            xhtml("目录", '<nav xmlns:epub="http://www.idpf.org/2007/ops" '
                          'epub:type="toc"><ol><li><a href="chapter1.xhtml">'
                          f"{TITLE}</a></li></ol></nav>"),
        )
        z.writestr("OEBPS/chapter1.xhtml", xhtml(TITLE, f"<h1>{TITLE}</h1>\n{paras}"))

    print(f"wrote {out}  ({os.path.getsize(out)} bytes, {len(PARAGRAPHS)} paragraphs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
