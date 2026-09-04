#!/usr/bin/env python3
"""Strip personal data from uiautomator XML dumps so they can be committed as fixtures.

The dumps come from real Instagram and LinkedIn feeds and contain real people's
names, handles and post text. Structure is what the tests care about; identity is
not. This rewrites identity to stable synthetic values and drops long free text,
leaving resource-ids, classes and bounds untouched.
"""
import re, sys, os, glob, xml.etree.ElementTree as ET

IG = 'com.instagram.android:id/'

# Patterns that reveal an identity, and which capture group holds it.
HANDLE_PATTERNS = [
    re.compile(r'^([a-z0-9._]{2,30})\s+posted a '),
    re.compile(r'^Profile picture of ([a-z0-9._]{2,30})$'),
    re.compile(r'^([a-z0-9._]{2,30}) shared a note'),
    re.compile(r'^([a-z0-9._]{2,30}) · Original audio$'),
    # caption row: "<handle> \"caption text..." and the bare handle button beside it
    re.compile(r'^([a-z0-9._]{2,30})\s+["\u201c]'),
    re.compile(r'^([a-z0-9._]{3,30})$'),
    # Instagram on the mobile web, as Chrome reports it to a screen reader.
    re.compile(r'^Story by ([a-z0-9._]{2,30}),'),
    re.compile(r'^@([a-z0-9._]{2,30})$'),
    # ...and as Chrome reports it when the account's language is not English.
    re.compile(r'^Фото профиля ([a-z0-9._]{2,30})$'),
    re.compile(r'^История ([a-z0-9._]{2,30}),'),
    re.compile(r'^Подписаны ([a-z0-9._]{2,30}) и ещё'),
]
NAME_PATTERNS = [
    re.compile(r"^(.+?)'s profile picture$"),
    re.compile(r'^Photo by (.+?) on '),
    re.compile(r'^Photo shared by (.+?) on '),
    re.compile(r'^Follow (.+)$'),
    re.compile(r'^Invite (.+) to connect$'),
    re.compile(r'^View (.+?)(?:’s|\'s) profile'),
    re.compile(r'^View (.+?) profile image$'),
    re.compile(r'^(.+?) commented$'),
    re.compile(r'^(.+?) likes this$'),
    re.compile(r'^Фото профиля (.+)$'),
    # LinkedIn, as Chrome and the app report it when the interface is in Russian.
    re.compile(r'^(?:Про|По)смотреть профиль участника (.+)$'),
    re.compile(r'^Отслеживать участника (.+)$'),
    re.compile(r'^(.+?) отметил\(а\), что нравится этот контент$'),
    re.compile(r'^(.+?) прокомментировал'),
    re.compile(r'^Отреагировавшие: (.+?) и еще'),
    re.compile(r'^(.+?) и еще \d'),
    re.compile(r'^(.+?), профиль Подтверждено'),
    re.compile(r'^(.+?), В поиске работы'),
    re.compile(r'^(.+?)\s+•\s*\d'),
]
# "by X," can catch ordinary prose, so it stays shape-guarded; everything above is
# unambiguously an identity, whatever characters the person put in their display name.
LOOSE_NAME_PATTERNS = [
    re.compile(r'\bby ([A-Z][^,]{2,40}),'),
    re.compile(r'^Because you recently followed (.+)$'),
    re.compile(r'^(.+?)\s*Premium'),
]

LONG_TEXT = 90          # anything longer is treated as post body / bio
FAKE_HANDLES = ['aurora.pics','beacon_news','citrus.club','delta_lab','ember.studio',
                'fable_co','granite.works','harbor_daily','indigo.set','juniper_tv',
                'kestrel.io','lumen_press','marlow.art','nimbus_news','onyx.studio']
FAKE_NAMES = ['Alba Rivers','Bruno Vale','Cora Finch','Dara Holt','Elio Marsh',
              'Farah Quinn','Gus Tanner','Hana Reid','Ivo Blanc','Jodie Kerr',
              'Kai Lorne','Lena Voss','Milo Prat','Nora Sage','Oren Diaz']


# Nodes that hold something other than an identity and must be left alone. Chrome's
# address bar is the obvious one: "instagram.com" looks exactly like a handle, and
# scrubbing it turns the fixture into a page the analyzer cannot recognise.
SKIP_IDS = ('/url_bar',)


def skip(node):
    rid = node.attrib.get('resource-id', '')
    return any(rid.endswith(s) for s in SKIP_IDS)


def collect(paths):
    handles, names = {}, {}
    for p in paths:
        root = ET.parse(p).getroot()
        for n in root.iter('node'):
            if skip(n):
                continue
            for k in ('text', 'content-desc'):
                v = (n.attrib.get(k) or '').replace('\xa0', ' ').strip()
                if not v:
                    continue
                for rx in HANDLE_PATTERNS:
                    m = rx.match(v)
                    if m:
                        handles.setdefault(m.group(1), None)
                for rx in NAME_PATTERNS:
                    m = rx.match(v)
                    if m and len(m.group(1).strip()) < 60:
                        names.setdefault(m.group(1).strip(), None)
                for rx in LOOSE_NAME_PATTERNS:
                    m = rx.search(v)
                    if m:
                        cand = m.group(1).strip()
                        # a handle is lowercase+dots; a display name has a capital or space
                        if len(cand) < 60 and (' ' in cand or cand[:1].isupper()):
                            names.setdefault(cand, None)
    for i, h in enumerate(sorted(handles)):
        handles[h] = FAKE_HANDLES[i % len(FAKE_HANDLES)] + ('' if i < len(FAKE_HANDLES) else str(i))
    for i, nm in enumerate(sorted(names)):
        names[nm] = FAKE_NAMES[i % len(FAKE_NAMES)] + ('' if i < len(FAKE_NAMES) else f' {i}')
    return handles, names


def scrub(value, handles, names):
    if not value:
        return value
    out = value
    # longest first, so "Astro Alexandra" is replaced before "Astro", and bounded so a
    # short name cannot be rewritten out of the middle of an unrelated word.
    for real, fake in sorted(names.items(), key=lambda kv: -len(kv[0])):
        out = re.sub(r'(?<![A-Za-z0-9._])' + re.escape(real) + r'(?![A-Za-z0-9._])', fake, out)
    for real, fake in sorted(handles.items(), key=lambda kv: -len(kv[0])):
        out = re.sub(r'(?<![A-Za-z0-9._])' + re.escape(real) + r'(?![A-Za-z0-9._])', fake, out)
    stripped = out.replace('\xa0', ' ').strip()
    if len(stripped) > LONG_TEXT:
        out = f'[body text, {len(stripped)} chars]'
    return out


def main():
    src, dst = sys.argv[1], sys.argv[2]
    paths = sorted(glob.glob(os.path.join(src, '*.xml')))
    handles, names = collect(paths)
    os.makedirs(dst, exist_ok=True)
    for p in paths:
        tree = ET.parse(p)
        for n in tree.getroot().iter('node'):
            if skip(n):
                continue
            for k in ('text', 'content-desc'):
                if k in n.attrib:
                    n.attrib[k] = scrub(n.attrib[k], handles, names)
        tree.write(os.path.join(dst, os.path.basename(p)), encoding='utf-8', xml_declaration=True)
    print(f'{len(paths)} files, {len(handles)} handles, {len(names)} names replaced')

if __name__ == '__main__':
    main()
