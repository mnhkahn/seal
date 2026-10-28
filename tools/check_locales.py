from pathlib import Path
import re
import xml.etree.ElementTree as ET
root=Path('app/src/main/res')
def read(path):
    return {x.attrib['name']:x.text or '' for x in ET.parse(path).getroot()}
base=read(root/'values/strings.xml')
required=set()
for path in Path('app/src/main/java').rglob('*.kt'):
    required.update(re.findall(r'R.string.([a-z_]+)',path.read_text()))
assert not (required-base.keys()),required-base.keys()
for path in root.glob('values*/strings.xml'):
    translated=read(path)
    assert translated.keys()==base.keys(),path
    for key,value in translated.items():
        assert value.strip(),(path,key)
        assert sorted(re.findall(r'%\d+\$[ds]',value))==sorted(re.findall(r'%\d+\$[ds]',base[key])),(path,key)
print(f'OK: {len(base)} strings; {len(list(root.glob("values*/strings.xml")))} complete locales; format arguments match.')
