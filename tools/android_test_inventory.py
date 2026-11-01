"""Inventory instrumentation tests and reject incomplete/unexpectedly skipped runs."""
import pathlib
import re


def test_inventory(root):
    result = []
    for source in sorted((pathlib.Path(root) / 'app/src/androidTest/java').rglob('*.kt')):
        text = source.read_text(encoding='utf-8-sig')
        count = len(re.findall(r'(?m)^\s*@(?:org\.junit\.)?Test\b', text))
        if not count:
            continue
        package = re.search(r'(?m)^package ([a-zA-Z0-9_.]+)', text)
        if not package or not re.search(r'\bclass\s+' + re.escape(source.stem) + r'\b', text):
            raise RuntimeError('Test inventory needs an explicit declaring class')
        result.append((package[1] + '.' + source.stem, count))
    if not result:
        raise RuntimeError('Empty instrumentation inventory')
    return result


def validate_output(output, expected, sdk):
    matched = re.search(r'OK \((\d+) tests?\)', output)
    if not matched or int(matched[1]) != expected:
        raise RuntimeError('Instrumentation failed or omitted tests')
    skips = [output[:m.start()].split('INSTRUMENTATION_STATUS_CODE:')[-1]
             for m in re.finditer(r'INSTRUMENTATION_STATUS_CODE: -[34]\b', output)]
    for block in skips:
        if not (sdk < 33 and len(skips) == 1
                and 'class=dev.pingclash.app.OceanShaderTest' in block
                and 'test=oceanCloudsAndNightRender' in block):
            raise RuntimeError('Unexpected instrumentation skip')
    completed = len(re.findall(r'INSTRUMENTATION_STATUS_CODE: 0\b', output))
    if completed + len(skips) != expected or re.search(r'INSTRUMENTATION_STATUS_CODE: -[12]\b', output):
        raise RuntimeError('Missing or failed per-test completion record')
    return {'passed': completed, 'platformSkipped': len(skips)}
