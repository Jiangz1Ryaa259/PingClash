"""Compare the pinned core surface with the reviewed integration contract.

This is a drift guard and gap inventory, not proof of configuration equivalence.
Run with --module-dir <go list -m -f {{.Dir}} github.com/metacubex/mihomo>.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / 'docs' / 'mihomo-compatibility.json'
FILES = {
    'rules': ('rules/parser.go', None),
    'outbounds': ('adapter/parser.go', None),
    'groups': ('adapter/outboundgroup/parser.go', None),
    'configuration': ('config/config.go', 'RawConfig'),
    'dns': ('config/config.go', 'RawDNS'),
}


def surface(module):
    result = {}
    for category, (relative, structure) in FILES.items():
        source = (module / relative).read_text(encoding='utf-8')
        if structure:
            found = re.search(r'type\s+' + structure + r'\s+struct\s*\{(.*?)\n\}', source, re.S)
            if not found:
                raise SystemExit('Pinned core structure changed: ' + structure)
            keys = re.findall(r'yaml:"([^",]+)', found[1])
        else:
            keys = [key for row in re.findall(r'^\s*case\s+([^:]+):', source, re.M)
                    for key in re.findall(r'"([A-Za-z0-9_-]+)"', row)]
        result[category] = sorted(set(keys) - {'-'})
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--module-dir', required=True, type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    contract = json.loads(CONTRACT.read_text(encoding='utf-8'))
    observed = surface(args.module_dir)
    for category, keys in observed.items():
        reviewed = contract['surface'][category]
        if set(keys) != set(reviewed):
            raise SystemExit('Unreviewed Mihomo surface drift in ' + category + ': added=' +
                             ','.join(sorted(set(keys)-set(reviewed))) + '; removed=' +
                             ','.join(sorted(set(reviewed)-set(keys))))
        for key, item in reviewed.items():
            if item['state'] not in {'integrated', 'partial', 'app-managed', 'not-integrated', 'disabled'} or not item['reason']:
                raise SystemExit('Missing integration scope: ' + category + '/' + key)
    native = (ROOT/'core/mobile/native_options.go').read_text(encoding='utf-8')
    actual_nodes = set(re.findall(r'"([a-z0-9-]+)"\s*:\s*reflect.TypeOf', native))
    declared_nodes = {key for key, value in contract['surface']['outbounds'].items()
                      if value.get('registered',value['state']=='integrated')}
    if actual_nodes != declared_nodes:
        raise SystemExit('Outbound contract disagrees with actual native option registry')
    for relative in set(contract['sourceHashes']) | {value[0] for value in FILES.values()}:
        digest = hashlib.sha256((args.module_dir/relative).read_bytes()).hexdigest()
        if contract['sourceHashes'][relative] != digest:
            raise SystemExit('Pinned source changed and needs semantic review: ' + relative)
    counts = {category: {state: sum(item['state']==state for item in contract['surface'][category].values())
                        for state in ['integrated','partial','app-managed','not-integrated','disabled']}
              for category in observed}
    print('Mihomo compatibility surface reviewed:', json.dumps(counts, sort_keys=True))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({'core':contract['core'],'counts':counts,
                                          'scope':'Drift inventory, not user compatibility rate'},indent=2)+'\n',encoding='utf-8')


if __name__ == '__main__':
    main()
